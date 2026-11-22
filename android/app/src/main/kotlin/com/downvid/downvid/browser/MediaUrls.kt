package com.downvid.downvid.browser

/** What a request URL is, as far as the media detector cares. */
enum class MediaKind { HLS, DASH, PROGRESSIVE, SEGMENT, NONE }

/**
 * Classifies URLs seen by the browser (network requests, page scripts).
 * Manifests and whole files are candidates; segments of a stream are only
 * noise (the manifest lists them). Pure, unit-tested.
 */
object MediaUrls {
    private val HLS_RE = Regex("""\.m3u8?(?:$|[?#/])|mpegurl|[?&/]format=m3u8""", RegexOption.IGNORE_CASE)
    private val DASH_RE = Regex("""\.mpd(?:$|[?#/])""", RegexOption.IGNORE_CASE)
    private val FILE_RE = Regex("""\.(?:mp4|m4v|mov|webm|mkv|3gp)(?:$|[?#/])""", RegexOption.IGNORE_CASE)
    private val SEGMENT_EXT_RE = Regex("""\.(?:ts|m4s|aac|m4a|vtt|webvtt|cmfv|cmfa|key)(?:$|[?#])""", RegexOption.IGNORE_CASE)
    // The file itself is a poster, script or page, even under a ".../video.webm/" folder
    // (Wikimedia: .../Big_Buck_Bunny.webm/960px--Big_Buck_Bunny.webm.jpg).
    private val NOT_MEDIA_RE = Regex("""\.(?:jpe?g|png|gif|webp|avif|svg|ico|bmp|css|js|mjs|html?|json|txt|xml|woff2?)$""", RegexOption.IGNORE_CASE)

    // fMP4 segments and init sections that use ".mp4" (DASH/HLS players).
    private val SEGMENT_NAME_RE = Regex(
        """(?:^|[/_.-])(?:seg|segment|chunk|frag|fragment|init|part)[-_]?\d*[^/]*\.(?:mp4|m4v)(?:$|[?#])|/range/\d+-\d+|[?&](?:range|bytes)=\d+-\d+""",
        RegexOption.IGNORE_CASE,
    )

    fun classify(url: String, mime: String? = null): MediaKind {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return MediaKind.NONE
        val m = mime?.lowercase().orEmpty()
        when {
            "mpegurl" in m -> return MediaKind.HLS
            "dash+xml" in m -> return MediaKind.DASH
        }
        val path = url.substringBefore('#')
        val beforeQuery = path.substringBefore('?')
        if (NOT_MEDIA_RE.containsMatchIn(beforeQuery.substringAfterLast('/')) && !m.startsWith("video/")) return MediaKind.NONE
        return when {
            SEGMENT_EXT_RE.containsMatchIn(beforeQuery) -> MediaKind.SEGMENT
            HLS_RE.containsMatchIn(path) -> MediaKind.HLS
            DASH_RE.containsMatchIn(beforeQuery) -> MediaKind.DASH
            SEGMENT_NAME_RE.containsMatchIn(path) -> MediaKind.SEGMENT
            FILE_RE.containsMatchIn(beforeQuery) -> MediaKind.PROGRESSIVE
            m.startsWith("video/") -> MediaKind.PROGRESSIVE
            else -> MediaKind.NONE
        }
    }

    private val PREVIEW_RE = Regex("""[/_.-](?:pre_?videos?|previews?|teasers?|thumbs?|sprites?|trailers?)[/_.-]""", RegexOption.IGNORE_CASE)

    /** Hover previews / teasers of other videos (listed apart from real videos). */
    fun isPreview(url: String) = PREVIEW_RE.containsMatchIn(url.substringBefore('?'))

    fun isCandidate(kind: MediaKind) = kind == MediaKind.HLS || kind == MediaKind.DASH || kind == MediaKind.PROGRESSIVE
}
