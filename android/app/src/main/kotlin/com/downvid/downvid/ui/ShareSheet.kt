package com.downvid.downvid.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PlayCircleFilled
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.downvid.downvid.MediaSaver
import com.downvid.downvid.core.ExtractResult
import com.downvid.downvid.core.Headers
import com.downvid.downvid.core.MediaOption
import com.downvid.downvid.core.MusicMeta
import com.downvid.downvid.core.Settings
import com.downvid.downvid.core.Skipped
import com.downvid.downvid.extract.DownloadPhase
import com.downvid.downvid.extract.ExtractState
import com.downvid.downvid.extract.Extractor
import com.downvid.downvid.extract.LinkParser
import com.downvid.downvid.extract.MediaMode
import com.downvid.downvid.extract.SharedLink
import java.util.Locale

/**
 * Content of the download sheet for a shared/pasted link: finds the media on
 * the page, lists it, and queues the chosen one. Leaving the composition
 * (sheet closed or replaced) disposes the extraction; a running download
 * continues in DownloadService.
 */
@Composable
fun ShareSheetContent(rawText: String?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val activity = currentActivity()
    val link = remember(rawText) { LinkParser.parse(rawText) }
    val extractor = remember(link) {
        link?.let { Extractor(activity, it, preferAudio = it.prefersAudio && Settings.current.musicAudioMode) }
    }
    DisposableEffect(extractor) {
        extractor?.start()
        onDispose { extractor?.dispose() }
    }
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        if (link == null || extractor == null) {
            Message(Icons.Rounded.LinkOff, "No link found", "The shared or copied text has no http(s) link.")
        } else {
            val s by extractor.state.collectAsStateWithLifecycle()
            SheetBody(link, extractor, s, onClose)
        }
    }
}

/**
 * The download sheet for a page open in the in-app browser: starts from the
 * media the tab already detected ([detected]) with the tab's session
 * ([headers]); [onPlay] opens an option in the player.
 */
@Composable
fun BrowserSheetContent(
    pageUrl: String,
    detected: ExtractResult,
    headers: Headers,
    /** The URL of the video being watched, listed first ([Extractor.focus]). */
    focus: String? = null,
    onPlay: (MediaOption, Extractor) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = currentActivity()
    val link = remember(pageUrl) { LinkParser.parse(pageUrl) }
    val extractor = remember(link) {
        link?.let {
            Extractor(
                activity, it.url,
                platform = if (it.isPlatform) it.platform.label else null,
                isPlatform = it.isPlatform,
                pageHeaders = headers,
            )
        }
    }
    DisposableEffect(extractor) {
        extractor?.focus = focus
        extractor?.startDetected(detected)
        onDispose { extractor?.dispose() }
    }
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        if (link == null || extractor == null) {
            Message(Icons.Rounded.LinkOff, "Nothing to download", "This page has no web address.")
        } else {
            val s by extractor.state.collectAsStateWithLifecycle()
            SheetBody(link, extractor, s, onClose, onPlay = { onPlay(it, extractor) })
        }
    }
}

/** Options the player can open directly. */
val MediaOption.playable get() = kind == "mp4" || kind == "hls" || kind == "merge" || kind == "audio"

@Composable
private fun SheetBody(
    link: SharedLink,
    x: Extractor,
    s: ExtractState,
    onClose: () -> Unit,
    onPlay: ((MediaOption) -> Unit)? = null,
) {
    val activity = currentActivity()
    val maxList = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    val showList = s.phase == DownloadPhase.IDLE || s.phase == DownloadPhase.FAILED || s.phase == DownloadPhase.CANCELED
    val audio = s.mode == MediaMode.AUDIO
    val meta = s.musicMeta
    val onStart = { requestNotificationPermission(activity) }

    if (audio && meta != null) MusicHeader(meta) else Header(link, s.title, s.thumbnail)
    Spacer(Modifier.height(16.dp))
    if (s.hasAudio && s.hasVideo && showList) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !audio, onClick = { x.setMode(MediaMode.VIDEO) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Rounded.Movie, null, Modifier.size(18.dp)) },
            ) { Text("Video") }
            SegmentedButton(
                selected = audio, onClick = { x.setMode(MediaMode.AUDIO) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Rounded.MusicNote, null, Modifier.size(18.dp)) },
            ) { Text("Audio only") }
        }
        Spacer(Modifier.height(12.dp))
    }
    ScanStatus(x, s)
    if (showList && !audio && s.isCarousel) {
        Spacer(Modifier.height(8.dp))
        CarouselPicker(x, s, Modifier.heightIn(max = maxList))
    } else if (showList && s.modeOptions.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        val hidden = if (audio) emptyList() else s.skipped + s.options.filter { it.small && it !in s.visibleOptions }
            .map { Skipped(it.url, "preview/fragment (${formatBytes(it.size)})") }
        val focused = if (audio) emptySet() else s.focusKeys
        LazyColumn(Modifier.heightIn(max = maxList)) {
            s.modeOptions.forEachIndexed { i, o ->
                if (focused.isNotEmpty()) {
                    val inFocus = o.key in focused
                    if (i == 0 && inFocus) item(key = "h-focus") { GroupHeader("Playing on this page") }
                    if (!inFocus && (i == 0 || s.modeOptions[i - 1].key in focused)) {
                        item(key = "h-other") { GroupHeader("Other videos on this page") }
                    }
                }
                item(key = o.key) {
                    OptionTile(o, o == s.selected, onPlay = onPlay?.takeIf { o.playable }?.let { { it(o) } }) { x.select(o) }
                }
            }
            if (hidden.isNotEmpty()) item { SkippedTile(hidden) }
        }
    } else if (showList && s.skipped.isNotEmpty() && !s.scanning) {
        SkippedTile(s.skipped)
    }
    if ((s.needsInstagramLogin || s.needsThreadsLogin) && s.visibleOptions.isEmpty()) {
        val site = if (s.needsThreadsLogin) "Threads" else "Instagram"
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (s.needsThreadsLogin) x.loginToThreads() else x.loginToInstagram() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Login, null)
            Spacer(Modifier.width(8.dp))
            Text("Sign in to $site (optional)")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Optional, only for private content. You sign in on $site's official page, in this phone's own " +
                "browser engine. Your password is not stored and nothing is sent to any server or cloud: the " +
                "session stays on this device and can be removed with \"Sign out\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    } else if (s.ytdlpError != null && !s.genericStarted && s.visibleOptions.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = x::startGeneric, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Rounded.Search, null)
            Spacer(Modifier.width(8.dp))
            Text("Find videos on the page")
        }
    }
    Spacer(Modifier.height(12.dp))
    DownloadArea(x, s, onStart, onClose)
    if (s.busy) {
        Text(
            "You can hide this: the download continues in the notification bar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun Header(link: SharedLink, title: String?, thumbnail: String?) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        RemoteImage(
            thumbnail,
            Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(14.dp)),
            maxPx = 384,
        ) { Placeholder(Icons.Rounded.Movie) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title ?: link.host, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(
                link.sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.background(scheme.primaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** Square cover + "Title" / "Artist · Album · Year" for audio downloads. */
@Composable
private fun MusicHeader(meta: MusicMeta) {
    val sub = listOfNotNull(meta.artist, meta.album, meta.year).filter { it.isNotEmpty() }.joinToString(" · ")
    Row(verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(
            meta.coverUrl,
            Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)),
            // Video thumbnails are center-cropped, as in the file.
            contentScale = if (meta.coverSquare) ContentScale.Fit else ContentScale.Crop,
        ) { Placeholder(Icons.Outlined.Album) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(meta.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta.track != 0) {
                Text("Track ${meta.track}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun Placeholder(icon: ImageVector) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ScanStatus(x: Extractor, s: ExtractState) {
    val n = s.modeOptions.size
    val text = when {
        s.matchStatus != null -> s.matchStatus
        s.ytdlpRunning && x.isPlatform -> "Extracting from ${x.platform}…"
        s.resolvingMeta && s.mode == MediaMode.AUDIO -> "Fetching cover and song info…"
        s.staticScanning -> "Scanning the page…"
        s.sniffing -> "Opening the page to detect the player… (${s.sniffedCount} ${if (s.sniffedCount == 1) "link" else "links"} found)"
        s.scanning -> "Checking the links found…"
        n == 0 && s.ytdlpError != null -> s.ytdlpError
        n == 0 -> s.scanError?.let { "Could not open the page: $it" } ?: "No MP4/HLS video found on this page."
        s.mode == MediaMode.AUDIO -> if (n == 1) "1 audio format" else "$n audio formats"
        s.modeOptions.all { it.isImage } -> if (n == 1) "1 photo found" else "$n photos found"
        else -> if (n == 1) "1 video found" else "$n options found"
    }
    val done = !s.scanning && !(s.resolvingMeta && s.mode == MediaMode.AUDIO)
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!done) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                if (n > 0) Icons.Rounded.CheckCircleOutline else Icons.Outlined.Info, null,
                Modifier.size(18.dp), tint = if (n > 0) scheme.success else scheme.outline,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (!done && n > 0) Text("$n", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
    )
}

@Composable
private fun OptionTile(o: MediaOption, selected: Boolean, onPlay: (() -> Unit)? = null, onTap: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val parts = buildList {
        if (o.durationSec > 0) add(formatDuration(o.durationSec))
        // yt-dlp URLs point at CDN hosts (googlevideo...), meaningless here.
        if (o.source == "instagram" || o.source == "threads") add(o.sourceLabel)
        if (o.source !in setOf("ytdlp", "instagram", "threads")) {
            add(o.sourceLabel)
            add(LinkParser.hostOf(o.url).orEmpty())
        }
    }.filter { it.isNotEmpty() }
    val icon = when {
        o.isAudio -> Icons.Rounded.MusicNote
        o.isImage -> Icons.Rounded.Image
        o.isHls -> Icons.Rounded.Stream
        else -> Icons.Rounded.Movie
    }
    Surface(
        onClick = onTap,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
        border = BorderStroke(1.5.dp, if (selected) scheme.primary else Color.Transparent),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    o.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                )
                if (parts.isNotEmpty()) {
                    Text(
                        parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (o.size > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    Extractor.sizeLabel(o), style = MaterialTheme.typography.labelLarge,
                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                )
            }
            if (onPlay != null) {
                IconButton(onClick = onPlay, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.PlayCircle, "Play", tint = scheme.primary)
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                Modifier.size(22.dp), tint = if (selected) scheme.primary else scheme.outlineVariant,
            )
        }
    }
}

/** Carousel post: one row per photo/video with a checkbox. */
@Composable
private fun CarouselPicker(x: Extractor, s: ExtractState, modifier: Modifier) {
    val items = s.carouselItems
    val n = s.checkedItems.size
    val all = n == items.size
    LazyColumn(modifier) {
        item {
            Row(Modifier.fillMaxWidth().clickable { x.setAllItems(!all) }, verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(
                    state = when {
                        all -> ToggleableState.On
                        n == 0 -> ToggleableState.Off
                        else -> ToggleableState.Indeterminate
                    },
                    onClick = { x.setAllItems(!all) },
                )
                Text("Select all (${items.size})", style = MaterialTheme.typography.titleSmall)
            }
        }
        items(items, key = { it.key }) { o ->
            Row(
                Modifier.fillMaxWidth().clickable { x.toggleItem(o.item) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(o.item in s.checkedItems, onCheckedChange = { x.toggleItem(o.item) })
                Column(Modifier.weight(1f)) {
                    Text(if (o.isImage) "Photo ${o.item}" else "Video ${o.item}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOf(
                            o.label.replace(Regex("""^\d+/\d+ · """), ""),
                            Extractor.sizeLabel(o),
                            formatDuration(o.durationSec),
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))) {
                    RemoteImage(o.itemThumb, Modifier.fillMaxSize(), maxPx = 160) {
                        Placeholder(if (o.isImage) Icons.Rounded.Image else Icons.Rounded.Movie)
                    }
                    if (!o.isImage) {
                        Icon(
                            Icons.Rounded.PlayCircleFilled, null, tint = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkippedTile(skipped: List<Skipped>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val small = MaterialTheme.typography.bodySmall
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${skipped.size} ${if (skipped.size == 1) "skipped link" else "skipped links"}", style = small, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(20.dp))
        }
        if (open) {
            for (s in skipped) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(s.url, style = small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(s.reason, style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DownloadArea(x: Extractor, s: ExtractState, onStart: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val wide = Modifier.fillMaxWidth().height(52.dp)
    when (s.phase) {
        DownloadPhase.DOWNLOADING, DownloadPhase.MUXING, DownloadPhase.CONVERTING, DownloadPhase.SAVING -> Column {
            val label = when (s.phase) {
                DownloadPhase.MUXING -> "Converting to MP4…"
                DownloadPhase.CONVERTING ->
                    "Converting to ${s.selected?.audioFormat?.uppercase(Locale.ROOT) ?: "audio"}… ${s.percent?.toInt() ?: ""}%"
                DownloadPhase.SAVING -> "Saving to gallery…"
                else -> progressText(s)
            }
            val determinate = s.phase == DownloadPhase.DOWNLOADING || s.phase == DownloadPhase.CONVERTING
            if (determinate && s.percent != null) {
                LinearProgressIndicator(progress = { (s.percent / 100).toFloat() }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.bodySmall)
            if (s.error != null) Text(s.error, style = MaterialTheme.typography.bodySmall, color = scheme.warning)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = x::cancelDownload,
                    enabled = s.phase == DownloadPhase.DOWNLOADING,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Rounded.Close, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Cancel")
                }
                FilledTonalButton(onClick = onClose, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Outlined.Notifications, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Hide")
                }
            }
        }
        DownloadPhase.SAVED -> Column {
            val saved = s.saved
            Banner(
                Icons.Rounded.CheckCircle, scheme.success,
                if (s.itemsTotal > 1) {
                    "${s.itemsSaved} of ${s.itemsTotal} items saved to the gallery (photos in Pictures/DownVid, videos in Movies/DownVid)"
                } else {
                    "Saved to ${saved?.location.orEmpty()}/${saved?.displayName.orEmpty()} (${formatBytes(s.bytes)})"
                },
            )
            if (s.warning != null) {
                Spacer(Modifier.height(6.dp))
                Banner(Icons.Rounded.WarningAmber, scheme.warning, s.warning)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(52.dp)) { Text("Close") }
                Button(
                    onClick = { saved?.let { MediaSaver.open(context, android.net.Uri.parse(it.uri), it.mime, it.displayName) } },
                    enabled = saved != null,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Open")
                }
            }
        }
        DownloadPhase.IDLE, DownloadPhase.FAILED, DownloadPhase.CANCELED -> Column {
            if (s.phase == DownloadPhase.FAILED) {
                Banner(Icons.Outlined.ErrorOutline, scheme.error, s.error ?: "Failed")
                Spacer(Modifier.height(8.dp))
            }
            if (s.phase == DownloadPhase.CANCELED) {
                Banner(Icons.Outlined.Cancel, scheme.outline, "Download canceled")
                Spacer(Modifier.height(8.dp))
            }
            if (s.isCarousel && s.mode == MediaMode.VIDEO) {
                val n = s.checkedItems.size
                Button(onClick = { x.downloadItems(onStart) }, enabled = n > 0, modifier = wide) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (n) {
                            0 -> "Select items"
                            1 -> "Download 1 item"
                            else -> "Download $n items"
                        },
                    )
                }
            } else {
                val sel = s.selected
                Button(onClick = { x.download(onStart) }, enabled = sel != null, modifier = wide) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            s.phase != DownloadPhase.IDLE -> "Try again"
                            sel?.isAudio == true -> "Download ${sel.audioFormat?.uppercase(Locale.ROOT) ?: "audio"}"
                            sel?.isImage == true -> "Download photo"
                            else -> "Download MP4"
                        },
                    )
                }
            }
        }
    }
}

private fun progressText(s: ExtractState): String {
    if (s.itemsTotal > 1) {
        return "${s.itemsSaved + s.itemsFailed} of ${s.itemsTotal} items · ${(s.percent ?: 0.0).toInt()}%"
    }
    return buildList {
        s.percent?.let { add("${it.toInt()}%") }
        add(if (s.total > 0) "${formatBytes(s.bytes)} / ${if (s.totalEstimate) "~" else ""}${formatBytes(s.total)}" else formatBytes(s.bytes))
        if (s.speedBps > 0) add("${formatBytes(s.speedBps)}/s")
        if (s.segmentsTotal > 0) add("seg ${s.segmentsDone}/${s.segmentsTotal}")
    }.joinToString(" · ")
}

@Composable
fun Banner(icon: ImageVector, color: Color, text: String) {
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Message(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Progress is shown in a notification; Android 13+ needs the permission. */
fun requestNotificationPermission(activity: Activity) {
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
        activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4201)
    }
}
