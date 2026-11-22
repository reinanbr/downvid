package com.downvid.downvid.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.HttpEngine
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpEngineDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import com.downvid.downvid.core.ExtractResult
import com.downvid.downvid.core.Headers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.Executors

/** What the player plays: a URL (or content://) with the session of the page it came from. */
data class PlayItem(
    val url: String,
    val title: String?,
    val pageUrl: String = "",
    val headers: Headers = Headers(),
    /** Separate audio track (yt-dlp "merge" formats). */
    val audioUrl: String? = null,
    /** "hls" / "dash" when the URL does not say it (format=m3u8, extensionless). */
    val streamType: String? = null,
    val incognito: Boolean = false,
    /** Start position in seconds (where the page's player was); 0 = saved/none. */
    val startSec: Double = 0.0,
    /** What the page offers for download (the browser's detector). */
    val detected: ExtractResult? = null,
) {
    val local get() = url.startsWith("content:") || url.startsWith("file:")
    /** Identity for the remembered position: the page (CDN URLs carry expiring tokens). */
    val resumeKey get() = pageUrl.ifEmpty { url.substringBefore('?') }
}

/**
 * The one in-process ExoPlayer, shared by PlayerActivity (video surface,
 * controls) and PlaybackService (media session: notification, lock screen,
 * Bluetooth, background audio). Created on first use, released when both
 * are done with it. Main thread only.
 */
@OptIn(UnstableApi::class)
object PlayerHub {
    private const val TAG = "DownVid"
    private var exo: ExoPlayer? = null
    private val _item = MutableStateFlow<PlayItem?>(null)
    val item: StateFlow<PlayItem?> = _item.asStateFlow()

    fun player(context: Context): ExoPlayer = exo ?: ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()
        .also { exo = it }

    val playerOrNull: ExoPlayer? get() = exo

    /**
     * The current item plays in a browser page (the in-page player), not in
     * PlayerActivity: the activity (or its PiP window) closes, and neither it
     * nor PlaybackService releases the player when they go.
     */
    @Volatile var inPage = false
        private set

    /** Starts [item] on the shared player ([inPage]: the in-page player shows it). */
    fun play(context: Context, item: PlayItem, inPage: Boolean = false) {
        this.inPage = inPage
        _item.value = item
        val p = player(context)
        p.setMediaSource(sourceFor(context, item))
        val resume = (item.startSec * 1000).toLong().takeIf { it > 1000 } ?: Positions.get(context, item.resumeKey)
        if (resume > 0) p.seekTo(resume)
        p.prepare()
        p.playWhenReady = true
        Log.i(TAG, "player: play ${if (item.local) "local file" else item.url.take(120)} type=${item.streamType} at ${resume}ms")
    }

    /** Opens the player activity on [item]. */
    fun open(context: Context, item: PlayItem) {
        play(context, item)
        val intent = Intent(context, PlayerActivity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun sourceFor(context: Context, item: PlayItem): MediaSource {
        val h = item.headers
        val props = buildMap {
            h.referer?.let { browserReferer(it, item.url) }?.let { (ref, origin) ->
                put("Referer", ref)
                origin?.let { put("Origin", it) }
            }
            h.cookie?.takeIf { it.isNotEmpty() }?.let { put("Cookie", it) }
            h.extra?.let { putAll(it) }
        }
        val http = httpFactory(context, h.userAgent, props)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        val meta = MediaMetadata.Builder().setTitle(item.title ?: hostOf(item.pageUrl.ifEmpty { item.url })).build()
        fun mediaItem(url: String, type: String?) = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(meta)
            .setMimeType(
                when (type) {
                    "hls" -> MimeTypes.APPLICATION_M3U8
                    "dash" -> MimeTypes.APPLICATION_MPD
                    else -> null
                },
            )
            .build()
        val video = factory.createMediaSource(mediaItem(item.url, item.streamType))
        val audio = item.audioUrl ?: return video
        return MergingMediaSource(video, factory.createMediaSource(mediaItem(audio, null)))
    }

    /**
     * HTTP/2 + QUIC through the system's Cronet (API 34+), like the page's
     * own player: some CDNs refuse HTTP/1.1 (410). HttpURLConnection otherwise.
     */
    private fun httpFactory(context: Context, userAgent: String?, props: Map<String, String>): HttpDataSource.Factory {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { engine(context) }.onSuccess { engine ->
                return HttpEngineDataSource.Factory(engine, engineExecutor)
                    .setUserAgent(userAgent)
                    .setConnectionTimeoutMs(15_000)
                    .setReadTimeoutMs(20_000)
                    .setDefaultRequestProperties(props)
            }.onFailure { Log.w(TAG, "player: HttpEngine unavailable: $it") }
        }
        return DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(props)
    }

    private var httpEngine: Any? = null
    private val engineExecutor by lazy { Executors.newSingleThreadExecutor() }

    @RequiresApi(34)
    private fun engine(context: Context): HttpEngine =
        httpEngine as? HttpEngine ?: HttpEngine.Builder(context.applicationContext)
            .setEnableHttp2(true)
            .setEnableQuic(true)
            .setEnableBrotli(true)
            .build()
            .also { httpEngine = it }

    /**
     * The Referer/Origin a browser sends from [page] to [target]: only the
     * page's origin when cross-origin (Chrome's default policy). A full page
     * URL next to an Origin header gets 410 from some CDNs.
     */
    internal fun browserReferer(page: String, target: String): Pair<String, String?> {
        val origin = originOf(page) ?: return page to null
        return if (originOf(target) == origin) page to origin else "$origin/" to origin
    }

    private fun originOf(url: String): String? {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val scheme = u.scheme ?: return null
        val host = u.host ?: return null
        return if (u.port == -1) "$scheme://$host" else "$scheme://$host:${u.port}"
    }

    private fun hostOf(url: String) = runCatching { Uri.parse(url).host }.getOrNull() ?: "Video"

    /** Remembers where the current item stopped (not in private tabs). */
    fun savePosition(context: Context) {
        val p = exo ?: return
        val item = _item.value ?: return
        if (item.incognito) return
        val dur = p.duration
        val pos = p.currentPosition
        // Finished (or nearly): start over next time.
        val keep = dur > 0 && pos > 10_000 && pos < dur - 15_000
        Positions.put(context, item.resumeKey, if (keep) pos else 0)
    }

    /** Stops playback and frees the decoder and buffers. */
    fun release(context: Context) {
        savePosition(context)
        exo?.release()
        exo = null
        inPage = false
        _item.value = null
    }
}

/** Last positions of up to [MAX] videos, in SharedPreferences ("hash" -> "posMs:time"). */
private object Positions {
    private const val MAX = 300
    private fun prefs(c: Context) = c.getSharedPreferences("player_positions", Context.MODE_PRIVATE)

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    fun get(c: Context, key: String): Long =
        prefs(c).getString(hash(key), null)?.substringBefore(':')?.toLongOrNull() ?: 0

    fun put(c: Context, key: String, pos: Long) {
        val p = prefs(c)
        val e = p.edit()
        if (pos <= 0) {
            e.remove(hash(key))
        } else {
            e.putString(hash(key), "$pos:${System.currentTimeMillis()}")
            val all = p.all
            if (all.size > MAX) {
                all.entries.sortedBy { (it.value as? String)?.substringAfter(':')?.toLongOrNull() ?: 0 }
                    .take(all.size - MAX).forEach { e.remove(it.key) }
            }
        }
        e.apply()
    }
}
