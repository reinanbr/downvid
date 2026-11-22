package com.downvid.downvid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.downvid.downvid.BuildConfig
import com.downvid.downvid.InstagramLoginActivity
import com.downvid.downvid.YtDlp
import com.downvid.downvid.core.GoCore
import com.downvid.downvid.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(snackbar: SnackbarHostState, onClearBrowsing: (() -> Unit)? = null) {
    val s by Settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var ytdlpVersion by remember { mutableStateOf<String?>(null) }
    var coreVersion by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var igLoggedIn by remember { mutableStateOf(false) }
    var threadsLoggedIn by remember { mutableStateOf(false) }
    var showNotice by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        igLoggedIn = InstagramLoginActivity.isLoggedIn(InstagramLoginActivity.Site.INSTAGRAM)
        threadsLoggedIn = InstagramLoginActivity.isLoggedIn(InstagramLoginActivity.Site.THREADS)
        coreVersion = withContext(Dispatchers.IO) { GoCore.version().removePrefix("v") }
        ytdlpVersion = withContext(Dispatchers.IO) { runCatching { YtDlp.version() }.getOrNull() } ?: "?"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, top = 16.dp))

        Group("Video") {
            var open by remember { mutableStateOf(false) }
            val qualities = listOf(0 to "Best", 2160 to "Up to 2160p", 1440 to "Up to 1440p", 1080 to "Up to 1080p", 720 to "Up to 720p", 480 to "Up to 480p")
            Item(Icons.Rounded.HighQuality, "Default quality", onClick = { open = true }, trailing = {
                Box {
                    TextButton(onClick = { open = true }) { Text(qualities.firstOrNull { it.first == s.videoMaxHeight }?.second ?: "Best") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for ((h, label) in qualities) {
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                open = false
                                Settings.update { it.copy(videoMaxHeight = h) }
                            })
                        }
                    }
                }
            })
        }

        Group("Audio") {
            for ((value, title, sub) in listOf(
                Triple("m4a_copy", "Original M4A", "No conversion: faster and lossless"),
                Triple("mp3_v0", "MP3 V0", "~245 kbps, smaller"),
                Triple("mp3_320", "MP3 320 kbps", "Maximum compatibility"),
            )) {
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = { Text(sub) },
                    leadingContent = { RadioButton(selected = s.audioFormat == value, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.selectable(s.audioFormat == value, role = Role.RadioButton) {
                        Settings.update { it.copy(audioFormat = value) }
                    },
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                Icons.Rounded.LibraryMusic, "Music links open in \"Audio only\"",
                "YouTube Music, SoundCloud, Spotify, Deezer, Apple Music", s.musicAudioMode,
            ) { v -> Settings.update { it.copy(musicAudioMode = v) } }
        }

        Group("Downloads") {
            Item(Icons.Rounded.Layers, "Simultaneous downloads", "The others wait in the queue")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                for (n in 1..4) {
                    SegmentedButton(
                        selected = s.maxConcurrent == n,
                        onClick = { Settings.update { it.copy(maxConcurrent = n) } },
                        shape = SegmentedButtonDefaults.itemShape(n - 1, 4),
                        icon = {},
                    ) { Text("$n") }
                }
            }
            // The slider moves freely; the value is applied when released.
            var conns by remember(s.connections) { mutableFloatStateOf(s.connections.toFloat()) }
            Item(
                Icons.Rounded.Speed, "Connections per download",
                "${conns.roundToInt()} · more connections are faster on servers that limit each one",
            )
            Slider(
                value = conns,
                onValueChange = { conns = it },
                onValueChangeFinished = { Settings.update { it.copy(connections = conns.roundToInt()) } },
                valueRange = 1f..16f,
                steps = 14,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SwitchItem(
                Icons.Rounded.ContentPasteGo, "Offer copied links", "When you open the app with a link in the clipboard",
                s.clipboardCheck,
            ) { v -> Settings.update { it.copy(clipboardCheck = v) } }
            Item(Icons.Rounded.Folder, "Folders", "Movies/DownVid · Music/DownVid · Pictures/DownVid")
        }

        Group("Browser") {
            var open by remember { mutableStateOf(false) }
            val engines = listOf("duckduckgo" to "DuckDuckGo", "google" to "Google", "bing" to "Bing", "brave" to "Brave")
            Item(Icons.Rounded.Search, "Search engine", onClick = { open = true }, trailing = {
                Box {
                    TextButton(onClick = { open = true }) { Text(engines.firstOrNull { it.first == s.searchEngine }?.second ?: "DuckDuckGo") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for ((id, label) in engines) {
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                open = false
                                Settings.update { it.copy(searchEngine = id) }
                            })
                        }
                    }
                }
            })
            SwitchItem(Icons.Rounded.Block, "Block ads and trackers", "Can be turned off per site in the browser menu", s.adBlock) { v ->
                Settings.update { it.copy(adBlock = v) }
            }
            SwitchItem(Icons.Rounded.DesktopWindows, "Desktop sites", "Ask sites for their desktop version", s.desktopMode) { v ->
                Settings.update { it.copy(desktopMode = v) }
            }
            SwitchItem(
                Icons.Rounded.OndemandVideo, "Open videos in the player",
                "A video you play on a page plays in DownVid's player, in the page", s.autoPlayer,
            ) { v -> Settings.update { it.copy(autoPlayer = v) } }
            SwitchItem(
                Icons.Rounded.Headphones, "Background playback", "Keep playing audio when you leave the player",
                s.playerBackgroundAudio,
            ) { v -> Settings.update { it.copy(playerBackgroundAudio = v) } }
            if (onClearBrowsing != null) {
                Item(Icons.Rounded.Shield, "Clear browsing data", "History, cookies and site data of the browser", onClick = {
                    onClearBrowsing()
                    scope.launch { snackbar.showSnackbar("Browsing data cleared") }
                })
            }
        }

        Group("Extractor") {
            Item(
                Icons.Rounded.Extension, "yt-dlp", "${ytdlpVersion ?: "loading…"} · updates itself every 3 days",
                trailing = {
                    if (updating) {
                        CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = {
                            updating = true
                            scope.launch {
                                val msg = try {
                                    val v = withContext(Dispatchers.IO) { YtDlp.update() }
                                    ytdlpVersion = v
                                    "yt-dlp updated: $v"
                                } catch (e: Exception) {
                                    "Could not update: ${e.message ?: e}"
                                }
                                updating = false
                                snackbar.showSnackbar(msg)
                            }
                        }) { Text("Update") }
                    }
                },
            )
        }

        if (igLoggedIn) {
            Group("Instagram") {
                SignedIn {
                    InstagramLoginActivity.logout(InstagramLoginActivity.Site.INSTAGRAM)
                    igLoggedIn = false
                }
            }
        }
        if (threadsLoggedIn) {
            Group("Threads") {
                SignedIn {
                    InstagramLoginActivity.logout(InstagramLoginActivity.Site.THREADS)
                    threadsLoggedIn = false
                }
            }
        }

        Group("About") {
            Row(Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                DownVidLogo(52.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("DownVid", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} (core ${coreVersion ?: "…"}) · open source, GPL-3.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Item(Icons.Rounded.VerifiedUser, "Usage notice", DISCLAIMER_TEXT, maxLines = 2, onClick = { showNotice = true })
            Item(Icons.Rounded.Shield, "Privacy", "No account, analytics or ads. Everything runs on this device.")
            Item(Icons.Rounded.Description, "Open-source licenses", onClick = { showLicenses = true })
        }
    }

    if (showNotice) DisclaimerDialog { showNotice = false }
    if (showLicenses) LicensesDialog { showLicenses = false }
}

@Composable
private fun SignedIn(onSignOut: () -> Unit) {
    Item(
        Icons.Rounded.LockOpen, "Signed in", "Session stored on this device only. No password is kept.",
        trailing = { TextButton(onClick = onSignOut) { Text("Sign out") } },
    )
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionTitle(title, PaddingValues(start = 8.dp, top = 20.dp, end = 8.dp, bottom = 8.dp))
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 6.dp), content = content)
    }
}

@Composable
private fun Item(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    maxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it, maxLines = maxLines, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { IconBadge(icon, 36.dp) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun SwitchItem(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { IconBadge(icon, 36.dp) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Switch) { onChange(!checked) },
    )
}

private val LICENSES = listOf(
    "DownVid core (Go)" to "GPL-3.0",
    "yt-dlp" to "Unlicense",
    "youtubedl-android" to "GPL-3.0",
    "FFmpeg" to "LGPL-2.1+ / GPL-2.0+",
    "Python" to "PSF License",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache-2.0",
    "AndroidX, Jetpack Compose, Media3 (ExoPlayer)" to "Apache-2.0",
    "StevenBlack hosts (ad blocker list)" to "MIT",
)

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licenses") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((name, license) in LICENSES) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.padding(4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
