package com.downvid.downvid

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Publishes finished downloads to shared storage: videos to Movies/DownVid,
 * audio (.mp3/.m4a) to Music/DownVid, photos to Pictures/DownVid.
 * Android 10+: MediaStore (scoped storage, no permission needed).
 * Android 7–9: direct file + media scan (needs WRITE_EXTERNAL_STORAGE).
 */
object MediaSaver {
    private const val TAG = "DownVid"
    const val FOLDER = "DownVid"

    data class Saved(val uri: Uri, val displayName: String, val location: String, val mime: String)

    private enum class Media { VIDEO, AUDIO, IMAGE }

    private data class Kind(val ext: String, val mime: String, val dir: String, val media: Media) {
        val audio get() = media == Media.AUDIO
    }

    private fun kindOf(file: File) = when (file.extension.lowercase()) {
        "mp3" -> Kind("mp3", "audio/mpeg", Environment.DIRECTORY_MUSIC, Media.AUDIO)
        "m4a" -> Kind("m4a", "audio/mp4", Environment.DIRECTORY_MUSIC, Media.AUDIO)
        "jpg", "jpeg" -> Kind("jpg", "image/jpeg", Environment.DIRECTORY_PICTURES, Media.IMAGE)
        "webp" -> Kind("webp", "image/webp", Environment.DIRECTORY_PICTURES, Media.IMAGE)
        "png" -> Kind("png", "image/png", Environment.DIRECTORY_PICTURES, Media.IMAGE)
        else -> Kind("mp4", "video/mp4", Environment.DIRECTORY_MOVIES, Media.VIDEO)
    }

    fun mimeOf(path: String) = kindOf(File(path)).mime

    fun save(context: Context, source: File, title: String): Saved {
        val kind = kindOf(source)
        val name = sanitize(title).ifBlank { "download_${System.currentTimeMillis()}" } + "." + kind.ext
        val size = source.length()
        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(context, source, name, kind)
        } else {
            saveLegacy(context, source, name, kind)
        }
        source.delete()
        Log.i(TAG, "saved ${size}B -> ${saved.uri} (${saved.location}/${saved.displayName})")
        return saved
    }

    private fun saveScoped(context: Context, source: File, name: String, kind: Kind): Saved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, kind.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${kind.dir}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (kind.audio) put(MediaStore.Audio.Media.IS_MUSIC, 1)
        }
        val collection = when (kind.media) {
            Media.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Media.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Media.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        // A save interrupted by a crash leaves a pending entry with this name
        // (only visible to us); remove it so the new file keeps the name.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val stale = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name, "${kind.dir}/$FOLDER/"))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            }
            runCatching { resolver.delete(collection, stale) }
                .onSuccess { if (it > 0) Log.i(TAG, "removed $it stale pending entr(ies) for $name") }
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore refused the insert")
        try {
            resolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out, 1 shl 20) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // MediaStore may have renamed it ("name (1).mp4").
        val finalName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        return Saved(uri, finalName, "${kind.dir}/$FOLDER", kind.mime)
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, source: File, name: String, kind: Kind): Saved {
        val dir = File(Environment.getExternalStoragePublicDirectory(kind.dir), FOLDER)
        dir.mkdirs()
        var target = File(dir, name)
        var n = 1
        while (target.exists()) target = File(dir, name.removeSuffix(".${kind.ext}") + " ($n).${kind.ext}").also { n++ }
        source.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(kind.mime), null)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        return Saved(uri, target.name, "${kind.dir}/$FOLDER", kind.mime)
    }

    fun open(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
            .trimEnd('.', ' ')
}
