package com.downvid.downvid.extract

/** Platforms with dedicated handling (error messages, quality defaults). */
enum class SourcePlatform(val label: String) {
    YOUTUBE("YouTube"),
    INSTAGRAM("Instagram"),
    THREADS("Threads"),
    TIKTOK("TikTok"),
    KWAI("Kwai"),
    X("X / Twitter"),
    FACEBOOK("Facebook"),
    SOUNDCLOUD("SoundCloud"),
    SPOTIFY("Spotify"),
    DEEZER("Deezer"),
    APPLE_MUSIC("Apple Music"),
    GENERIC("Link"),
}

data class SharedLink(val url: String, val host: String, val platform: SourcePlatform) {
    val isPlatform get() = platform != SourcePlatform.GENERIC

    /** Spotify/Deezer/Apple Music: audio is DRM-protected; only metadata is
     *  read and the song is found on YouTube Music. */
    val isMusicService
        get() = platform == SourcePlatform.SPOTIFY || platform == SourcePlatform.DEEZER || platform == SourcePlatform.APPLE_MUSIC

    /** Links that are about the song, not the video: open in "audio only". */
    val prefersAudio get() = isMusicService || platform == SourcePlatform.SOUNDCLOUD || host == "music.youtube.com"

    /** What the sheet shows as the source. */
    val sourceLabel get() = if (isPlatform) platform.label else host
}

object LinkParser {
    private val URL_RE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    // Trailing punctuation that share texts often glue onto the URL.
    private val TRAILING_RE = Regex("""[).,;:!?\]}>]+$""")
    private val HOST_RE = Regex("""^https?://(?:[^/?#@]*@)?(\[[^\]]+]|[^/?#:]+)""", RegexOption.IGNORE_CASE)

    /** The first http(s) URL in arbitrary shared text, e.g.
     *  "Olha isso https://youtu.be/abc?si=x !" -> https://youtu.be/abc?si=x. */
    fun parse(text: String?): SharedLink? {
        if (text == null) return null
        val raw = URL_RE.find(text)?.value?.replace(TRAILING_RE, "") ?: return null
        val host = hostOf(raw) ?: return null
        return SharedLink(raw, host, detect(host))
    }

    fun hostOf(url: String): String? = HOST_RE.find(url)?.groupValues?.get(1)?.lowercase()?.ifEmpty { null }

    fun detect(host: String): SourcePlatform {
        val h = host.lowercase()
        fun isHost(domain: String) = h == domain || h.endsWith(".$domain")
        return when {
            isHost("youtube.com") || isHost("youtu.be") || isHost("youtube-nocookie.com") -> SourcePlatform.YOUTUBE
            isHost("instagram.com") || isHost("instagr.am") -> SourcePlatform.INSTAGRAM
            isHost("threads.net") || isHost("threads.com") -> SourcePlatform.THREADS
            isHost("tiktok.com") -> SourcePlatform.TIKTOK
            isHost("kwai.com") || isHost("kw.ai") || isHost("kwai.net") -> SourcePlatform.KWAI
            isHost("x.com") || isHost("twitter.com") || isHost("t.co") -> SourcePlatform.X
            isHost("facebook.com") || isHost("fb.watch") || isHost("fb.com") -> SourcePlatform.FACEBOOK
            isHost("soundcloud.com") || isHost("snd.sc") -> SourcePlatform.SOUNDCLOUD
            isHost("spotify.com") || isHost("spotify.link") -> SourcePlatform.SPOTIFY
            isHost("deezer.com") || isHost("deezer.page.link") || isHost("dzr.page.link") -> SourcePlatform.DEEZER
            isHost("music.apple.com") -> SourcePlatform.APPLE_MUSIC
            else -> SourcePlatform.GENERIC
        }
    }
}
