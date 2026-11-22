package com.downvid.downvid.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downvid.downvid.MediaSaver
import com.downvid.downvid.core.Job
import com.downvid.downvid.core.JobController
import com.downvid.downvid.extract.LinkRenewer
import kotlinx.coroutines.launch

/** Tabs of the downloads screen, by what the job produces. */
private enum class Category(val label: String, val icon: ImageVector, val empty: String, val hint: String) {
    VIDEO("Videos", Icons.Rounded.Movie, "No videos yet", "Share a video link with DownVid, paste it on the home screen, or download it in the browser."),
    MUSIC("Music", Icons.Rounded.MusicNote, "No music yet", "Pick \"Audio only\" in the download sheet to save M4A or MP3."),
    PHOTO("Photos", Icons.Rounded.Image, "No photos yet", "Instagram and Threads photos and carousels show up here.");

    companion object {
        fun of(j: Job): Category {
            val mime = j.saved?.mime.orEmpty()
            return when {
                j.kind == "audio" -> MUSIC
                j.kind == "image" -> PHOTO
                mime.startsWith("audio/") -> MUSIC
                mime.startsWith("image/") -> PHOTO
                else -> VIDEO
            }
        }
    }
}

/** The persistent queue (in progress / failed) and the history of saved files. */
@Composable
fun DownloadsScreen(jobs: List<Job>, loaded: Boolean, snackbar: SnackbarHostState) {
    val activity = currentActivity()
    val scope = rememberCoroutineScope()
    val renewing = remember { mutableStateSetOf<Long>() }
    var confirmDelete by remember { mutableStateOf<Job?>(null) }

    fun renew(j: Job) {
        renewing += j.id
        scope.launch {
            val ok = LinkRenewer.renew(activity, j)
            renewing -= j.id
            if (!ok) snackbar.showSnackbar("Could not renew the link of \"${j.title ?: "download"}\".")
        }
    }
    // Expired links are renewed automatically once per job.
    LaunchedEffect(jobs) {
        for (j in jobs) if (j.state == "expired" && LinkRenewer.shouldAutoRenew(j.id)) renew(j)
    }

    val visible = jobs.filter { it.state in Job.IN_PROGRESS || it.state == "saved" }
    val groups = Category.entries.associateWith { c -> visible.filter { Category.of(it) == c } }
    // Photos only get a tab when there are any.
    val tabs = listOfNotNull(Category.VIDEO, Category.MUSIC, Category.PHOTO.takeIf { groups[it]!!.isNotEmpty() })
    var tab by rememberSaveable { mutableStateOf(0) }
    if (tab >= tabs.size) tab = 0

    Column(Modifier.fillMaxSize()) {
        Text("Downloads", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
        PrimaryTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, c ->
                Tab(selected = tab == i, onClick = { tab = i }, text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(c.icon, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(c.label)
                        val n = groups[c]!!.size
                        if (n > 0) {
                            Spacer(Modifier.width(6.dp))
                            Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                Text("$n")
                            }
                        }
                    }
                })
            }
        }
        val c = tabs[tab]
        val list = groups[c]!!
        if (list.isEmpty()) {
            if (loaded) EmptyState(c.icon, c.empty, c.hint, Modifier.fillMaxSize())
        } else {
            val active = list.filter { it.state in Job.IN_PROGRESS }
            val saved = list.filter { it.state == "saved" }.sortedByDescending { it.id }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp)) {
                if (active.isNotEmpty()) {
                    item { SectionTitle("In progress · ${active.size}", PaddingValues(start = 4.dp, top = 16.dp, end = 4.dp, bottom = 8.dp)) }
                    items(active, key = { it.id }) { j ->
                        JobTile(j, renewing = j.id in renewing || LinkRenewer.isRenewing(j.id), onRenew = { renew(j) }, onDelete = { confirmDelete = j })
                    }
                }
                if (saved.isNotEmpty()) {
                    item { SectionTitle("Completed · ${saved.size}", PaddingValues(start = 4.dp, top = 16.dp, end = 4.dp, bottom = 8.dp)) }
                    items(saved, key = { it.id }) { j -> JobTile(j, renewing = false, onRenew = {}, onDelete = { confirmDelete = j }) }
                }
            }
        }
    }

    confirmDelete?.let { j ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete file?") },
            text = { Text("\"${j.saved?.displayName ?: j.title}\" will be deleted from the gallery.") },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = null
                    j.saved?.let {
                        // Our own MediaStore entries: deletable without user consent.
                        runCatching { activity.contentResolver.delete(Uri.parse(it.uri), null, null) }
                            .onFailure { e -> Log.w("DownVid", "delete failed", e) }
                    }
                    JobController.remove(j.id)
                }) { Text("Delete") }
            },
        )
    }
}

@Composable
private fun JobTile(j: Job, renewing: Boolean, onRenew: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val showProgress = j.state == "running" || j.state == "paused" || j.state == "waiting"
    val statusColor = when (j.state) {
        "error" -> scheme.error
        "expired", "waiting" -> scheme.warning
        "paused", "saved" -> scheme.onSurfaceVariant
        else -> scheme.primary
    }
    val open = { j.saved?.let { MediaSaver.open(context, Uri.parse(it.uri), it.mime, j.title) }; Unit }
    Card(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.clickable(enabled = j.state == "saved", onClick = open).padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No page thumbnail (a shared stream link): the saved video's frame.
            val thumb = j.thumbnail ?: j.saved?.uri?.takeIf { j.kind != "audio" && j.kind != "image" }
            Thumb(thumb, j.kind, done = j.state == "saved")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(j.title ?: "Download", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                if (showProgress) {
                    val color = if (j.state == "paused") scheme.outline else scheme.primary
                    if (j.percent > 0) {
                        LinearProgressIndicator(progress = { (j.percent / 100).toFloat() }, color = color, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(color = color, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(statusText(j, renewing), style = MaterialTheme.typography.bodySmall, color = statusColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Actions(j, renewing, onRenew, onDelete, open)
        }
    }
}

private fun statusText(j: Job, renewing: Boolean): String {
    val size = if (j.total > 0) "${formatBytes(j.bytes)} / ${formatBytes(j.total)}" else formatBytes(j.bytes)
    return when (j.state) {
        "queued" -> "Queued"
        "running" -> when (j.phase) {
            "muxing" -> "Converting to MP4…"
            "converting" -> "Converting audio… ${j.percent.toInt()}%"
            else -> buildList {
                if (j.percent > 0) add("${j.percent.toInt()}%")
                add(size)
                if (j.speedBps > 0) add("${formatBytes(j.speedBps)}/s")
                if (j.speedBps > 0 && j.total > j.bytes) add(formatEta((j.total - j.bytes) / j.speedBps))
            }.joinToString(" · ")
        }
        "waiting" -> "No connection — retrying"
        "paused" -> "Paused · $size"
        "done" -> "Saving to gallery…"
        "expired" -> if (renewing) "Renewing the link…" else "Link expired"
        "error" -> j.message ?: "Failed"
        "saved" -> listOf(j.saved?.location.orEmpty(), if (j.size > 0) formatBytes(j.size) else "").filter { it.isNotEmpty() }.joinToString(" · ")
        else -> j.state
    }
}

@Composable
private fun Actions(j: Job, renewing: Boolean, onRenew: () -> Unit, onDelete: () -> Unit, open: () -> Unit) {
    val context = LocalContext.current
    when (j.state) {
        "running", "queued", "waiting" -> Row {
            IconButton(onClick = { JobController.pause(j.id) }) { Icon(Icons.Rounded.Pause, "Pause") }
            IconButton(onClick = { JobController.cancel(j.id) }) { Icon(Icons.Rounded.Close, "Cancel") }
        }
        "paused", "error" -> Row {
            val label = when {
                j.state == "paused" -> "Resume"
                j.saveFailed -> "Save again"
                else -> "Retry"
            }
            IconButton(onClick = { JobController.resume(j.id) }) {
                Icon(if (j.state == "paused") Icons.Rounded.PlayArrow else Icons.Rounded.Refresh, label)
            }
            IconButton(onClick = { JobController.remove(j.id) }) { Icon(Icons.Outlined.DeleteOutline, "Remove") }
        }
        "expired" -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (renewing) {
                Box(Modifier.padding(12.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else {
                IconButton(onClick = onRenew) { Icon(Icons.Rounded.Refresh, "Renew link") }
            }
            IconButton(onClick = { JobController.remove(j.id) }) { Icon(Icons.Outlined.DeleteOutline, "Remove") }
        }
        "saved" -> Box {
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) }, onClick = { menu = false; open() })
                DropdownMenuItem(text = { Text("Open with…") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) }, onClick = {
                    menu = false
                    j.saved?.let { s -> MediaSaver.openWith(context, Uri.parse(s.uri), s.mime) }
                })
                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                    menu = false
                    j.saved?.let { s -> MediaSaver.share(context, Uri.parse(s.uri), s.mime) }
                })
                DropdownMenuItem(text = { Text("Remove from history") }, leadingIcon = { Icon(Icons.Rounded.History, null) }, onClick = {
                    menu = false
                    JobController.remove(j.id)
                })
                DropdownMenuItem(text = { Text("Delete file") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}

@Composable
private fun Thumb(url: String?, kind: String, done: Boolean) {
    val icon = when (kind) {
        "audio" -> Icons.Rounded.MusicNote
        "image" -> Icons.Rounded.Image
        else -> Icons.Rounded.Movie
    }
    // Videos keep their 16:9 frame; music covers and photos are square.
    val width = if (kind == "audio" || kind == "image") 56.dp else 88.dp
    Box(Modifier.size(width, 56.dp).clip(RoundedCornerShape(12.dp))) {
        RemoteImage(url, Modifier.fillMaxSize(), maxPx = 256) { Placeholder(icon) }
        if (done && kind != "image") {
            Icon(
                if (kind == "audio") Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow, null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(4.dp).size(18.dp),
            )
        }
    }
}
