package com.downvid.downvid.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON settings shared by every Go call. Defaults are encoded: Go fills a
 * missing field with its zero value, not with the Kotlin default (a
 * `SavedFile` of a video used to reach the core without its "video/mp4"
 * and came back with an empty MIME type, which no app would open).
 */
val DvJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/** One downloadable choice (scan.Option in Go). */
@Serializable
data class MediaOption(
    val id: String = "",
    val kind: String = "mp4", // mp4 | hls | merge | audio | image
    val label: String = "",
    val url: String = "",
    val audioUrl: String? = null,
    val height: Int = 0,
    val bandwidth: Long = 0,
    val size: Long = -1,
    val sizeExact: Boolean = false,
    val durationSec: Double = 0.0,
    val source: String = "", // html | meta | jsonld | iframe | webview | direct | ytdlp | instagram | threads
    val group: String = "", // candidate URL it came from
    val referer: String? = null,
    val cookie: String? = null,
    /** Under 1 MB: likely a preview of another video or a stream fragment. */
    val small: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val chunkSize: Long = 0,
    val vcodec: String? = null,
    val fps: Double = 0.0,
    val audioFormat: String? = null,
    val audioQuality: String? = null,
    val sourceHls: Boolean = false,
    /** Carousel item (1-based) / total / per-item thumbnail. */
    val item: Int = 0,
    val itemCount: Int = 0,
    val itemThumb: String? = null,
) {
    val isAudio get() = kind == "audio"
    val isImage get() = kind == "image"
    val isHls get() = kind == "hls"

    /**
     * Identifies "the same choice" in a later extraction of the same link
     * (URLs change; quality/format/item don't): used to renew expired links.
     */
    val optionKey get() = listOf(kind, height, vcodec ?: "", audioFormat ?: "", audioQuality ?: "", item, label).joinToString("|")

    /** Stable identity across probe rounds. */
    val key get() = "$kind|$url|${audioUrl ?: ""}|${audioFormat ?: ""}|${audioQuality ?: ""}"

    val sourceLabel
        get() = when (source) {
            "webview" -> "browser"
            "iframe" -> "embedded player"
            "meta", "jsonld" -> "metadata"
            "direct" -> "direct link"
            "ytdlp" -> "yt-dlp"
            "instagram" -> "Instagram"
            "threads" -> "Threads"
            else -> "page"
        }
}

@Serializable
data class Skipped(val url: String = "", val reason: String = "")

/** What yt-dlp / the page / Instagram know about a song (music.Basic). */
@Serializable
data class MusicBasic(
    val title: String = "",
    val track: String = "",
    val artist: String = "",
    val album: String = "",
    val releaseYear: Int = 0,
    val uploader: String = "",
    val duration: Double = 0.0,
    val thumbnail: String = "",
    val thumbSquare: Boolean = false,
    val musicContext: Boolean = false,
)

/** Tags + cover for an audio file (music.Meta). */
@Serializable
data class MusicMeta(
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val track: Int = 0,
    val duration: Double = 0.0,
    val isrc: String? = null,
    val coverUrl: String? = null,
    val coverSquare: Boolean = false,
    val source: String? = null,
)

/** Result of probe / ytdlp / instaParse / threadsParse. */
@Serializable
data class ExtractResult(
    val pageUrl: String = "",
    val title: String? = null,
    val thumbnail: String? = null,
    val options: List<MediaOption> = emptyList(),
    val skipped: List<Skipped> = emptyList(),
    val extractor: String? = null,
    val uploader: String? = null,
    val audioOptions: List<MediaOption> = emptyList(),
    val music: MusicBasic? = null,
)

@Serializable
data class MusicLinkResult(val meta: MusicMeta = MusicMeta(), val searchUrl: String = "")

@Serializable
data class MusicPickResult(val candidates: List<String> = emptyList())

/** Where DownloadService published a file (jobs.Saved). */
@Serializable
data class SavedFile(
    val uri: String = "",
    val displayName: String = "",
    val location: String = "",
    val mime: String = "video/mp4",
)

/** Request headers of a download (netx.Headers). */
@Serializable
data class Headers(
    val userAgent: String? = null,
    val referer: String? = null,
    val cookie: String? = null,
    val extra: Map<String, String>? = null,
)

/** A job as the app sees it (jobs.Status: record + last event). */
@Serializable
data class Job(
    val id: Long,
    val state: String = "",
    val kind: String = "",
    val created: Long = 0,
    val updated: Long = 0,
    val saveFailed: Boolean = false,
    val saved: SavedFile? = null,
    // jobs.Meta
    val title: String? = null,
    val pageUrl: String? = null,
    val optionKey: String? = null,
    val thumbnail: String? = null,
    // jobs.Event (last)
    val type: String = "",
    val bytes: Long = 0,
    val total: Long = -1,
    val totalEstimate: Boolean = false,
    val percent: Double = 0.0,
    val speedBps: Double = 0.0,
    val segmentsDone: Int = 0,
    val segmentsTotal: Int = 0,
    val phase: String = "",
    val path: String? = null,
    val size: Long = 0,
    val durationMs: Long = 0,
    val warning: String? = null,
    val message: String? = null,
) {
    /** States DownloadService works on: it runs while any job is in one. */
    val needsService get() = state in SERVICE_STATES

    companion object {
        val SERVICE_STATES = setOf("queued", "running", "waiting", "done")
        val IN_PROGRESS = setOf("queued", "running", "waiting", "done", "paused", "error", "expired")
    }
}

/** jobs.Changes: what changed since a version. */
@Serializable
data class JobChanges(
    val version: Long = 0,
    val full: Boolean = false,
    val jobs: List<Job> = emptyList(),
    val removed: List<Long> = emptyList(),
)

/** A download request (jobs.Request + meta). */
@Serializable
data class DownloadRequest(
    val kind: String,
    val url: String,
    val audioUrl: String? = null,
    val headers: Headers = Headers(),
    val outPath: String,
    val tmpDir: String? = null,
    val chunkSize: Long = 0,
    val connections: Int = 0,
    val ffmpeg: FFmpegPaths? = null,
    val audioFormat: String? = null,
    val audioQuality: String? = null,
    val sourceHls: Boolean = false,
    val meta: JobMeta = JobMeta(),
    /** Tags of an audio job (music.Meta); "meta" above is the job's display info. */
    @kotlinx.serialization.SerialName("musicMeta") val musicMeta: MusicMeta? = null,
)

@Serializable
data class JobMeta(
    val title: String? = null,
    val pageUrl: String? = null,
    val optionKey: String? = null,
    val thumbnail: String? = null,
)

@Serializable
data class FFmpegPaths(val path: String = "", val libraryPath: String? = null)
