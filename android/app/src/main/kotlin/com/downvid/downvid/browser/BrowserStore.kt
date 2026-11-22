package com.downvid.downvid.browser

import android.content.Context
import android.util.Log
import com.downvid.downvid.core.DvJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class PageEntry(val url: String, val title: String = "", val time: Long = 0)

@Serializable
data class SavedTabs(val urls: List<String> = emptyList(), val current: Int = 0)

/**
 * History (newest first, at most [HISTORY_MAX]), bookmarks and the open
 * tabs, as JSON files under files/browser. Kept in memory; writes are
 * coalesced and done off the main thread.
 */
object BrowserStore {
    private const val TAG = "DownVid"
    private const val HISTORY_MAX = 1000
    private const val WRITE_DELAY_MS = 1500L
    private lateinit var dir: File
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = HashMap<String, Job>()

    private val _history = MutableStateFlow<List<PageEntry>>(emptyList())
    private val _bookmarks = MutableStateFlow<List<PageEntry>>(emptyList())
    val history: StateFlow<List<PageEntry>> = _history.asStateFlow()
    val bookmarks: StateFlow<List<PageEntry>> = _bookmarks.asStateFlow()
    @Volatile private var loaded = false

    /** Loads the files (blocking; call once, off the main thread when possible). */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        dir = File(context.filesDir, "browser").apply { mkdirs() }
        _history.value = read("history.json") ?: emptyList()
        _bookmarks.value = read("bookmarks.json") ?: emptyList()
        loaded = true
    }

    fun tabs(): SavedTabs = runCatching {
        DvJson.decodeFromString(SavedTabs.serializer(), File(dir, "tabs.json").readText())
    }.getOrDefault(SavedTabs())

    fun saveTabs(tabs: SavedTabs) = write("tabs.json") { DvJson.encodeToString(SavedTabs.serializer(), tabs) }

    fun visit(url: String, title: String) {
        if (!loaded || !url.startsWith("http")) return
        val now = System.currentTimeMillis()
        val list = _history.value
        val rest = list.filter { it.url != url }
        _history.value = (listOf(PageEntry(url, title, now)) + rest).take(HISTORY_MAX)
        writeList("history.json", _history.value)
    }

    /** Updates the title of a page already in the history (titles arrive late). */
    fun retitle(url: String, title: String) {
        val list = _history.value
        val first = list.firstOrNull() ?: return
        if (first.url != url || first.title == title || title.isBlank()) return
        _history.value = listOf(first.copy(title = title)) + list.drop(1)
        writeList("history.json", _history.value)
    }

    fun clearHistory() {
        _history.value = emptyList()
        writeList("history.json", emptyList())
    }

    fun removeHistory(entry: PageEntry) {
        _history.value = _history.value - entry
        writeList("history.json", _history.value)
    }

    fun isBookmarked(url: String) = _bookmarks.value.any { it.url == url }

    fun toggleBookmark(url: String, title: String) {
        val list = _bookmarks.value
        _bookmarks.value = if (list.any { it.url == url }) {
            list.filter { it.url != url }
        } else {
            listOf(PageEntry(url, title.ifBlank { url }, System.currentTimeMillis())) + list
        }
        writeList("bookmarks.json", _bookmarks.value)
    }

    private fun read(name: String): List<PageEntry>? = try {
        val f = File(dir, name)
        if (f.exists()) DvJson.decodeFromString(ListSerializer(PageEntry.serializer()), f.readText()) else null
    } catch (e: Exception) {
        Log.w(TAG, "browser: cannot read $name: ${e.message}")
        null
    }

    private fun writeList(name: String, list: List<PageEntry>) =
        write(name) { DvJson.encodeToString(ListSerializer(PageEntry.serializer()), list) }

    @Synchronized
    private fun write(name: String, content: () -> String) {
        if (!loaded) return
        writes[name]?.cancel()
        writes[name] = io.launch {
            delay(WRITE_DELAY_MS)
            runCatching {
                val tmp = File(dir, "$name.tmp")
                // Flushed before the rename: a crash or power loss must not
                // leave an empty file in place of the history.
                java.io.FileOutputStream(tmp).use { out ->
                    out.write(content().toByteArray())
                    out.fd.sync()
                }
                tmp.renameTo(File(dir, name))
            }.onFailure { Log.w(TAG, "browser: cannot write $name: ${it.message}") }
        }
    }
}
