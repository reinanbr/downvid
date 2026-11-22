package com.downvid.downvid.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.downvid.downvid.MediaSaver
import com.downvid.downvid.core.Job
import com.downvid.downvid.core.Settings
import com.downvid.downvid.extract.LinkParser
import com.downvid.downvid.extract.SharedLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Links already offered from the clipboard (never offered twice per process). */
private object ClipOffers {
    var seenAt = -1L
    val offered = HashSet<String>()
}

/**
 * Link in the clipboard when the app comes to the foreground. Reading the
 * content makes Android show a "pasted" toast, so only clips newer than the
 * last one examined are read (the description has no content).
 */
private fun clipboardLink(context: Context): SharedLink? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    val d = cm.primaryClipDescription ?: return null
    if (!d.hasMimeType("text/*")) return null
    val ts = if (Build.VERSION.SDK_INT >= 26) d.timestamp else 0L
    if (ts != 0L && ts == ClipOffers.seenAt) return null
    ClipOffers.seenAt = ts
    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    val link = LinkParser.parse(text) ?: return null
    return link.takeIf { ClipOffers.offered.add(it.url) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    jobs: List<Job>,
    onAnalyze: (String) -> Unit,
    onOpenBrowser: (String?) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var url by rememberSaveable { mutableStateOf("") }
    var clipLink by remember { mutableStateOf<SharedLink?>(null) }

    // The clipboard is readable only once the window has focus, a moment after resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!Settings.current.clipboardCheck) return@LifecycleEventEffect
        scope.launch {
            delay(500)
            runCatching { clipboardLink(context) }
                .onSuccess { if (it != null) clipLink = it }
                .onFailure { Log.w("DownVid", "home: clipboard check failed: ${it.message}") }
        }
    }
    val analyze = {
        val text = url.trim()
        if (text.isNotEmpty()) {
            focus.clearFocus()
            onAnalyze(text)
        }
    }
    val recent = remember(jobs) { jobs.filter { it.state == "saved" }.sortedByDescending { it.id }.take(8) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 32.dp)) {
        item {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DownVidLogo(36.dp)
                Spacer(Modifier.width(12.dp))
                Text("DownVid", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(12.dp))
        }
        clipLink?.let { link ->
            item {
                ClipboardCard(
                    link,
                    onOpen = {
                        clipLink = null
                        url = link.url
                        onAnalyze(link.url)
                    },
                    onDismiss = { clipLink = null },
                )
                Spacer(Modifier.height(16.dp))
            }
        }
        item {
            Hero(url, { url = it }, onPaste = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let { url = it }
            }, onSubmit = analyze, onBrowse = {
                val t = url.trim()
                onOpenBrowser(t.ifEmpty { null })
            })
        }
        if (recent.isNotEmpty()) {
            item {
                SectionTitle(
                    "Recent downloads",
                    PaddingValues(start = 4.dp, top = 20.dp, bottom = 4.dp),
                ) { TextButton(onClick = onOpenDownloads) { Text("See all") } }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { it.id }) { RecentItem(it) }
                }
            }
        }
        item {
            SectionTitle("Works with")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in listOf(
                    "YouTube", "YouTube Music", "Instagram", "Threads", "TikTok", "X", "Facebook",
                    "SoundCloud", "Spotify", "Deezer", "Any web page",
                )) {
                    SuggestionChip(onClick = {}, label = { Text(s) })
                }
            }
            SectionTitle("Tips")
            Tip(Icons.Rounded.Share, "Share from any app", "Tap Share on a video and pick \"Download with DownVid\". A small sheet opens right there.")
            Tip(Icons.Rounded.Language, "Built-in browser", "Open any site in the Browser tab: the videos it plays are listed, ready to download or watch in the player.")
            Tip(Icons.Rounded.GridView, "Quick Settings tile", "Add \"Paste & download\" to your quick settings to grab a copied link from anywhere.")
            Tip(Icons.Rounded.MusicNote, "Audio only", "Switch to Audio only to save M4A or MP3 with cover art, title, artist and album.")
            Spacer(Modifier.height(20.dp))
            Text(
                "Use only with your own, public or authorized content.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Gradient card with the link field and the main action. */
@Composable
private fun Hero(
    url: String,
    onUrl: (String) -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    onBrowse: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brand.gradient).padding(start = 20.dp, top = 22.dp, end = 20.dp, bottom = 20.dp),
    ) {
        Text(
            "Save videos, music\nand photos",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Paste a link or share it from any app. Videos are saved as MP4.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.88f),
        )
        Spacer(Modifier.height(18.dp))
        TextField(
            value = url,
            onValueChange = onUrl,
            singleLine = true,
            placeholder = { Text("Paste a link", color = Color.Black.copy(alpha = 0.45f)) },
            leadingIcon = { Icon(Icons.Rounded.Link, null, tint = Color.Black.copy(alpha = 0.45f)) },
            trailingIcon = {
                IconButton(onClick = onPaste) { Icon(Icons.Rounded.ContentPaste, "Paste", tint = Brand.Violet) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedTextColor = Color.Black.copy(alpha = 0.87f),
                unfocusedTextColor = Color.Black.copy(alpha = 0.87f),
                cursorColor = Brand.Violet,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.22f), contentColor = Color.White)
            Button(onClick = onSubmit, colors = colors, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Rounded.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("Find media")
            }
            Button(onClick = onBrowse, colors = colors, modifier = Modifier.height(52.dp)) {
                Icon(Icons.Rounded.Language, "Open in browser")
            }
        }
    }
}

@Composable
private fun ClipboardCard(link: SharedLink, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.ContentPasteGo, 36.dp, scheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Link in your clipboard", style = MaterialTheme.typography.titleSmall)
                    Text(
                        link.url, style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                TextButton(onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open")
                }
            }
        }
    }
}

@Composable
private fun RecentItem(job: Job) {
    val context = LocalContext.current
    val saved = job.saved
    val mime = saved?.mime?.ifEmpty { null } ?: "video/mp4"
    val icon = when {
        job.kind == "audio" || mime.startsWith("audio/") -> Icons.Rounded.MusicNote
        mime.startsWith("image/") -> Icons.Rounded.Image
        else -> Icons.Rounded.Movie
    }
    Column(
        Modifier.width(140.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = saved != null) {
            saved?.let { MediaSaver.open(context, Uri.parse(it.uri), it.mime, job.title) }
        },
    ) {
        Box(Modifier.size(140.dp, 84.dp).clip(RoundedCornerShape(16.dp))) {
            RemoteImage(job.thumbnail, Modifier.fillMaxSize()) { Placeholder(icon) }
            Icon(
                icon, null, tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.54f), RoundedCornerShape(8.dp)).padding(3.dp).size(14.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            job.title ?: "Download", style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Tip(icon: ImageVector, title: String, text: String) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            IconBadge(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
