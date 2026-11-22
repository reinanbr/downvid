package com.downvid.downvid.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User settings in SharedPreferences "downvid_settings" (same keys as the
 * Flutter version, so an update keeps them). One immutable snapshot is
 * published as a StateFlow; UI and services read it without disk access.
 */
data class AppSettings(
    /** Default video quality: 0 = best available, else max height (720...). */
    val videoMaxHeight: Int = 0,
    /** m4a_copy | mp3_v0 | mp3_320 */
    val audioFormat: String = "m4a_copy",
    /** Music links (YouTube Music, SoundCloud, Spotify...) open in "audio only". */
    val musicAudioMode: Boolean = true,
    /** Simultaneous downloads (1–4). */
    val maxConcurrent: Int = 2,
    /** Connections per download (1–16). */
    val connections: Int = 8,
    /** Offer the copied link when the app comes to the foreground. */
    val clipboardCheck: Boolean = true,
    /** The first-use notice was acknowledged. */
    val disclaimerAccepted: Boolean = false,
    // Browser
    val searchEngine: String = "duckduckgo",
    val homePage: String = "",
    val adBlock: Boolean = true,
    /** Sites (hosts) where the ad blocker is off. */
    val adBlockAllow: Set<String> = emptySet(),
    val desktopMode: Boolean = false,
    /** Open page videos in the native player as soon as they start playing. */
    val autoPlayer: Boolean = true,
    val playerBackgroundAudio: Boolean = true,
) {
    fun searchUrl(query: String): String {
        val q = android.net.Uri.encode(query)
        return when (searchEngine) {
            "google" -> "https://www.google.com/search?q=$q"
            "bing" -> "https://www.bing.com/search?q=$q"
            "brave" -> "https://search.brave.com/search?q=$q"
            else -> "https://duckduckgo.com/?q=$q"
        }
    }
}

object Settings {
    private const val PREFS = "downvid_settings"
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current get() = _state.value

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = read()
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        val p = prefs
        return AppSettings(
            videoMaxHeight = p.getInt("videoMaxHeight", d.videoMaxHeight),
            audioFormat = p.getString("audioFormat", d.audioFormat) ?: d.audioFormat,
            musicAudioMode = p.getBoolean("musicAudioMode", d.musicAudioMode),
            maxConcurrent = p.getInt("maxConcurrent", d.maxConcurrent).coerceIn(1, 4),
            connections = p.getInt("connections", d.connections).coerceIn(1, 16),
            clipboardCheck = p.getBoolean("clipboardCheck", d.clipboardCheck),
            disclaimerAccepted = p.getBoolean("disclaimerAccepted", d.disclaimerAccepted),
            searchEngine = p.getString("searchEngine", d.searchEngine) ?: d.searchEngine,
            homePage = p.getString("homePage", d.homePage) ?: d.homePage,
            adBlock = p.getBoolean("adBlock", d.adBlock),
            adBlockAllow = p.getStringSet("adBlockAllow", null)?.toSet() ?: d.adBlockAllow,
            desktopMode = p.getBoolean("desktopMode", d.desktopMode),
            autoPlayer = p.getBoolean("openInPlayer", d.autoPlayer),
            playerBackgroundAudio = p.getBoolean("playerBackgroundAudio", d.playerBackgroundAudio),
        )
    }

    /** Applies a change, persists it asynchronously and tells the Go core when needed. */
    fun update(change: (AppSettings) -> AppSettings) {
        val old = _state.value
        val new = change(old)
        if (new == old) return
        _state.value = new
        prefs.edit {
            putInt("videoMaxHeight", new.videoMaxHeight)
            putString("audioFormat", new.audioFormat)
            putBoolean("musicAudioMode", new.musicAudioMode)
            putInt("maxConcurrent", new.maxConcurrent)
            putInt("connections", new.connections)
            putBoolean("clipboardCheck", new.clipboardCheck)
            putBoolean("disclaimerAccepted", new.disclaimerAccepted)
            putString("searchEngine", new.searchEngine)
            putString("homePage", new.homePage)
            putBoolean("adBlock", new.adBlock)
            putStringSet("adBlockAllow", new.adBlockAllow)
            putBoolean("desktopMode", new.desktopMode)
            putBoolean("openInPlayer", new.autoPlayer)
            putBoolean("playerBackgroundAudio", new.playerBackgroundAudio)
        }
        if (new.maxConcurrent != old.maxConcurrent || new.connections != old.connections) {
            GoCore.settingsAsync(new.maxConcurrent, new.connections)
        }
    }
}
