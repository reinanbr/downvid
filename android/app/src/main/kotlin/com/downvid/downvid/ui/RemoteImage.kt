package com.downvid.downvid.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Minimal thumbnail loader (instead of an image library): memory LRU of
 * downsampled bitmaps + a small disk cache, so thumbnails of saved downloads
 * still show after their CDN links expire. At most 4 fetches at a time.
 */
object ImageCache {
    private const val TAG = "DownVid"
    private const val MAX_BYTES = 6 shl 20
    private const val DISK_FILES = 400
    private lateinit var dir: File
    private lateinit var app: Context
    private val mem = object : LruCache<String, ImageBitmap>(16 shl 20) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val net = Semaphore(4)

    fun init(context: Context) {
        app = context.applicationContext
        dir = File(context.cacheDir, "thumbs")
    }

    fun cached(url: String, maxPx: Int): ImageBitmap? = mem.get("$maxPx|$url")

    suspend fun load(url: String, maxPx: Int): ImageBitmap? {
        val key = "$maxPx|$url"
        mem.get(key)?.let { return it }
        if (url in failed) return null
        val mine = CompletableDeferred<ImageBitmap?>()
        val existing = inflight.putIfAbsent(key, mine)
        if (existing != null) return existing.await()
        var result: ImageBitmap? = null
        try {
            result = withContext(Dispatchers.IO) { fetch(url, maxPx) }
            if (result != null) mem.put(key, result) else failed += url
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "thumb: ${e.message} ($url)")
            failed += url
        } finally {
            // Also on cancellation: other waiters must not hang.
            inflight.remove(key)
            mine.complete(result)
        }
        return result
    }

    private suspend fun fetch(url: String, maxPx: Int): ImageBitmap? {
        if (url.startsWith("content:")) return frameOf(Uri.parse(url), maxPx)?.asImageBitmap()
        val file = File(dir, sha1(url))
        val bytes = if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        } else {
            net.withPermit { download(url) }?.also { save(file, it) }
        } ?: return null
        return decode(bytes, maxPx)?.asImageBitmap()
    }

    /** A saved video's frame (MediaStore keeps its own thumbnail cache). */
    private fun frameOf(uri: Uri, maxPx: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 29) {
            app.contentResolver.loadThumbnail(uri, Size(maxPx, maxPx), null)
        } else {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(app, uri)
                    getFrameAtTime(1_000_000)?.let { b ->
                        val k = maxPx.toFloat() / maxOf(b.width, b.height)
                        if (k < 1) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
                    }
                } finally {
                    release()
                }
            }
        }

    private fun download(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) return null
            val len = c.contentLengthLong
            if (len > MAX_BYTES) return null
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(if (len > 0) len.toInt() else 64 shl 10)
                val buf = ByteArray(32 shl 10)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun save(file: File, bytes: ByteArray) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
            val files = dir.listFiles() ?: return
            if (files.size > DISK_FILES) {
                files.sortBy { it.lastModified() }
                for (f in files.take(files.size - DISK_FILES * 3 / 4)) f.delete()
            }
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Remote image with [placeholder] while loading or on failure. */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxPx: Int = 320,
    placeholder: @Composable () -> Unit,
) {
    var image by remember(url) { mutableStateOf(url?.let { ImageCache.cached(it, maxPx) }) }
    if (url != null && image == null) {
        LaunchedEffect(url) { image = ImageCache.load(url, maxPx) }
    }
    Box(modifier) {
        val img = image
        if (img != null) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = contentScale)
        } else {
            placeholder()
        }
    }
}
