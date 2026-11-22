package com.downvid.downvid.browser

import android.app.Activity
import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clipToBounds
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.downvid.downvid.core.Candidate
import com.downvid.downvid.core.ExtractResult
import com.downvid.downvid.core.GoCore
import com.downvid.downvid.core.Headers
import com.downvid.downvid.core.Settings
import com.downvid.downvid.extract.LinkParser
import com.downvid.downvid.player.PlayItem
import com.downvid.downvid.player.PlayerHub
import com.downvid.downvid.ui.Banner
import com.downvid.downvid.ui.BrowserSheetContent
import com.downvid.downvid.ui.DownVidLogo
import com.downvid.downvid.ui.SectionTitle
import com.downvid.downvid.ui.currentActivity
import com.downvid.downvid.ui.warning
import kotlinx.coroutines.launch

/** What sheet the browser shows over the page. */
private sealed interface BrowserSheet {
    data object Media : BrowserSheet
    data object Tabs : BrowserSheet
    data class Pages(val bookmarks: Boolean) : BrowserSheet
    data class Manual(val url: String, val headers: Headers?) : BrowserSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(model: BrowserModel, onHome: () -> Unit) {
    val activity = currentActivity()
    val tab = model.current
    var sheet by remember { mutableStateOf<BrowserSheet?>(null) }

    DisposableEffect(model) {
        model.onPlay = { req -> openPlayer(activity, model, req) }
        model.onDownloadLink = { url, headers -> sheet = BrowserSheet.Manual(url, headers) }
        onDispose {
            model.onPlay = {}
            model.onDownloadLink = { _, _ -> }
        }
    }
    // Pages run only while the browser is on screen (battery).
    LifecycleResumeEffect(model) {
        model.onResume()
        onPauseOrDispose { model.onPause() }
    }
    LaunchedEffect(tab == null) { if (tab == null) model.open(Settings.current.homePage.ifEmpty { null }, inNewTab = true) }

    // The in-page player's ExoPlayer (shared PlayerHub one). Something else
    // playing on it (the full player, a downloaded file) ends the in-page one.
    val playing by PlayerHub.item.collectAsStateWithLifecycle()
    val player = if (tab?.inline != null && playing === tab.inline?.item) PlayerHub.playerOrNull else null
    LaunchedEffect(playing) {
        model.tabs.forEach { t -> t.inline?.let { if (it.item !== playing) model.closeInline(t) } }
    }

    // Fullscreen page video (the page's own player).
    val full = model.fullscreen
    DisposableEffect(full) {
        val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (full != null) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    BackHandler(enabled = full != null) { model.exitFullscreen() }
    BackHandler(enabled = full == null && tab != null) {
        val wv = tab?.webView
        when {
            wv != null && wv.canGoBack() -> wv.goBack()
            tab != null && model.tabs.size > 1 && tab.url.isNotEmpty() && tab.title.isEmpty() -> model.close(tab)
            else -> onHome()
        }
    }

    // <input type=file>.
    val chooser = model.fileChooser
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        model.fileChooser?.first?.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        model.fileChooser = null
    }
    LaunchedEffect(chooser) {
        if (chooser != null) {
            runCatching { picker.launch(chooser.second.createIntent()) }.onFailure {
                chooser.first.onReceiveValue(null)
                model.fileChooser = null
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (tab != null) {
            Column(Modifier.fillMaxSize()) {
                AddressBar(model, tab)
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    if (tab.isBlank) StartPage(model, tab) else PageView(model, tab)
                    val v = tab.inline
                    if (v != null && !v.fullscreen && player != null) {
                        InlinePlayer(
                            tab, v, player,
                            onDownload = { sheet = BrowserSheet.Media },
                            onFullscreen = { v.fullscreen = true },
                            onClose = { model.closeInline(tab) },
                        )
                    }
                    PlayOfferPill(model, tab, Modifier.align(Alignment.BottomCenter))
                }
                Toolbar(model, tab, onHome = onHome, onSheet = { sheet = it })
            }
        }
        if (full != null) {
            AndroidView(
                factory = { ctx -> FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) } },
                update = { box ->
                    if (box.getChildAt(0) !== full) {
                        box.removeAllViews()
                        (full.parent as? ViewGroup)?.removeView(full)
                        box.addView(full, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                },
                onRelease = { it.removeAllViews() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val v = tab?.inline
        if (v != null && v.fullscreen && player != null) {
            InlineFullscreen(v, player, onExit = { v.fullscreen = false })
        }
    }

    sheet?.let { s ->
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = s !is BrowserSheet.Pages)
        val scope = rememberCoroutineScope()
        val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { sheet = null } }
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = state,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            when (s) {
                BrowserSheet.Media -> tab?.let { MediaSheet(model, it, close) }
                BrowserSheet.Tabs -> TabsSheet(model, close)
                is BrowserSheet.Pages -> PagesSheet(model, s.bookmarks, close)
                is BrowserSheet.Manual -> ManualSheet(model, s, close)
            }
        }
    }
}

private fun streamTypeOf(url: String) = when (MediaUrls.classify(url)) {
    MediaKind.HLS -> "hls"
    MediaKind.DASH -> "dash"
    else -> null
}

private fun openPlayer(activity: Activity, model: BrowserModel, req: PlayRequest) {
    val tab = model.tabs.firstOrNull { it.url == req.pageUrl } ?: model.current
    tab?.let { model.pausePage(it); it.playOffer = null }
    PlayerHub.open(
        activity,
        PlayItem(
            url = req.url,
            title = req.title,
            pageUrl = req.pageUrl,
            headers = req.headers,
            streamType = streamTypeOf(req.url),
            incognito = req.incognito,
            startSec = req.position,
            detected = tab?.detector?.state?.value?.asResult(tab.displayTitle),
        ),
    )
}

@Composable
private fun PageView(model: BrowserModel, tab: BrowserTab) {
    AndroidView(
        factory = { ctx -> FrameLayout(ctx) },
        update = { box ->
            val wv = model.webViewOf(tab) ?: return@AndroidView
            if (box.getChildAt(0) !== wv) {
                box.removeAllViews()
                (wv.parent as? ViewGroup)?.removeView(wv)
                box.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        // The WebView belongs to the tab, not to this view: keep it alive.
        onRelease = { it.removeAllViews() },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun AddressBar(model: BrowserModel, tab: BrowserTab) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var text by remember(tab.id) { mutableStateOf(TextFieldValue(tab.url)) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(tab.url, focused) { if (!focused) text = TextFieldValue(tab.url) }
    LaunchedEffect(tab.id) { if (tab.isBlank) runCatching { requester.requestFocus() } }

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (tab.incognito) Color(0xFF2B2930) else scheme.surfaceContainer)
            .statusBarsPadding(),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Search or type a web address") },
                leadingIcon = {
                    Icon(
                        when {
                            tab.incognito -> Icons.Rounded.VisibilityOff
                            tab.url.startsWith("https://") -> Icons.Rounded.Lock
                            else -> Icons.Rounded.Public
                        },
                        null,
                        Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    if (focused && text.text.isNotEmpty()) {
                        IconButton(onClick = { text = TextFieldValue("") }) { Icon(Icons.Rounded.Close, "Clear") }
                    } else if (!tab.isBlank) {
                        IconButton(onClick = { tab.webView?.let { if (tab.progress < 100) it.stopLoading() else it.reload() } }) {
                            Icon(if (tab.progress < 100) Icons.Rounded.Close else Icons.Rounded.Refresh, if (tab.progress < 100) "Stop" else "Reload")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = {
                    val url = model.normalize(text.text)
                    if (url.isNotEmpty()) model.load(tab, url)
                    keyboard?.hide()
                    focus.clearFocus()
                }),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = scheme.surfaceContainerHighest,
                    unfocusedContainerColor = scheme.surfaceContainerHighest,
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .focusRequester(requester)
                    .onFocusChanged {
                        if (it.isFocused && !focused) text = TextFieldValue(tab.url, TextRange(0, tab.url.length))
                        focused = it.isFocused
                    },
            )
        }
        if (tab.progress in 1..99 && !tab.isBlank) {
            LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun Toolbar(model: BrowserModel, tab: BrowserTab, onHome: () -> Unit, onSheet: (BrowserSheet) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val d by tab.detector.state.collectAsStateWithLifecycle()
    val bookmarks by BrowserStore.bookmarks.collectAsStateWithLifecycle()
    val settings by Settings.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val activity = currentActivity()

    Surface(color = if (tab.incognito) Color(0xFF2B2930) else scheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().height(56.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { tab.webView?.goBack() }, enabled = tab.canGoBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            IconButton(onClick = { tab.webView?.goForward() }, enabled = tab.canGoForward) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Forward") }
            MediaButton(d, onClick = { onSheet(BrowserSheet.Media) })
            IconButton(
                onClick = { onSheet(BrowserSheet.Tabs) },
                modifier = Modifier.semantics { contentDescription = "Tabs" },
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, scheme.onSurface),
                    modifier = Modifier.size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${model.tabs.size}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val host = LinkParser.hostOf(tab.url)
                    val bookmarked = bookmarks.any { it.url == tab.url }
                    MenuItem(Icons.Rounded.Add, "New tab") { menu = false; model.open(Settings.current.homePage.ifEmpty { null }, incognito = false, inNewTab = true) }
                    if (model.incognitoSupported) {
                        MenuItem(Icons.Rounded.VisibilityOff, "New private tab") { menu = false; model.open(null, incognito = true, inNewTab = true) }
                    }
                    HorizontalDivider()
                    if (!tab.isBlank) {
                        MenuItem(if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (bookmarked) "Remove bookmark" else "Bookmark") {
                            menu = false
                            BrowserStore.toggleBookmark(tab.url, tab.title)
                        }
                    }
                    MenuItem(Icons.Rounded.Bookmark, "Bookmarks") { menu = false; onSheet(BrowserSheet.Pages(bookmarks = true)) }
                    MenuItem(Icons.Rounded.History, "History") { menu = false; onSheet(BrowserSheet.Pages(bookmarks = false)) }
                    MenuItem(Icons.Rounded.Download, "Download link…") { menu = false; onSheet(BrowserSheet.Manual("", null)) }
                    if (!tab.isBlank) {
                        HorizontalDivider()
                        MenuItem(Icons.Rounded.DesktopWindows, if (tab.desktop) "Mobile site" else "Desktop site") {
                            menu = false
                            model.setDesktop(tab, !tab.desktop)
                        }
                        if (settings.adBlock && host != null) {
                            val allowed = host in settings.adBlockAllow
                            MenuItem(Icons.Rounded.Shield, if (allowed) "Block ads on this site" else "Allow ads on this site (${tab.blocked} blocked)") {
                                menu = false
                                Settings.update { s -> s.copy(adBlockAllow = if (allowed) s.adBlockAllow - host else s.adBlockAllow + host) }
                                tab.webView?.reload()
                            }
                        }
                        MenuItem(Icons.Rounded.Share, "Share page") {
                            menu = false
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url)
                            runCatching { activity.startActivity(Intent.createChooser(send, null)) }
                        }
                    }
                    HorizontalDivider()
                    MenuItem(Icons.Rounded.Home, "DownVid home") { menu = false; onHome() }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** "▶ N": videos found on the page. */
@Composable
private fun MediaButton(d: DetectedMedia, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val n = d.count
    IconButton(onClick = onClick) {
        BadgedBox(badge = {
            when {
                n > 0 -> Badge(containerColor = scheme.primary) { Text("$n") }
                d.pending > 0 -> Badge(containerColor = scheme.outline) { Text("…") }
            }
        }) {
            Icon(
                if (d.drm && n == 0) Icons.Rounded.Lock else Icons.Rounded.Download,
                "Videos on this page",
                tint = if (n > 0) scheme.primary else androidx.compose.material3.LocalContentColor.current,
            )
        }
    }
}

@Composable
private fun PlayOfferPill(model: BrowserModel, tab: BrowserTab, modifier: Modifier) {
    val offer = tab.playOffer
    AnimatedVisibility(
        visible = offer != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier.padding(bottom = 16.dp),
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primary, shadowElevation = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp)) {
                TextButton(onClick = { if (offer != null) model.playOffered(tab) }) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text("Play in DownVid", color = MaterialTheme.colorScheme.onPrimary)
                }
                IconButton(onClick = { tab.playOffer = null }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Close, "Dismiss", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun StartPage(model: BrowserModel, tab: BrowserTab) {
    val bookmarks by BrowserStore.bookmarks.collectAsStateWithLifecycle()
    val history by BrowserStore.history.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DownVidLogo(56.dp)
                Spacer(Modifier.height(12.dp))
                Text(if (tab.incognito) "Private tab" else "Browse and download", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (tab.incognito) "History, cookies and site data of private tabs are deleted when you close the last one."
                    else "Open a page with a video: the download button below counts what it finds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        if (bookmarks.isNotEmpty()) {
            item { SectionTitle("Bookmarks") }
            items(bookmarks.take(8), key = { "b" + it.url }) { e -> PageRow(e, onClick = { model.load(tab, e.url) }) }
        }
        if (!tab.incognito && history.isNotEmpty()) {
            item { SectionTitle("Recent") }
            items(history.take(8), key = { "h" + it.url }) { e -> PageRow(e, onClick = { model.load(tab, e.url) }) }
        }
    }
}

@Composable
private fun PageRow(e: PageEntry, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Public, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title.ifBlank { e.url }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                LinkParser.hostOf(e.url) ?: e.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun MediaSheet(model: BrowserModel, tab: BrowserTab, close: () -> Unit) {
    val activity = currentActivity()
    val d by tab.detector.state.collectAsStateWithLifecycle()
    // The sheet's extraction starts from what was detected when it opened;
    // reopen it to include later finds.
    val detected = remember(tab.id) { d.asResult(tab.displayTitle) }
    Column {
        if (d.drm && d.count == 0) {
            Box(Modifier.padding(horizontal = 20.dp)) {
                Banner(Icons.Rounded.Lock, MaterialTheme.colorScheme.warning, "This site protects its videos (DRM): they cannot be downloaded.")
            }
            Spacer(Modifier.height(8.dp))
        }
        if (tab.isBlank) {
            Text("Open a page first.", modifier = Modifier.padding(24.dp))
            return
        }
        BrowserSheetContent(
            pageUrl = tab.url,
            detected = detected,
            headers = model.headersFor(tab, null),
            onPlay = { o, x ->
                close()
                model.pausePage(tab)
                PlayerHub.open(
                    activity,
                    PlayItem(
                        url = o.url,
                        title = x.state.value.title ?: tab.displayTitle,
                        pageUrl = tab.url,
                        headers = x.headersFor(o),
                        audioUrl = o.audioUrl?.takeIf { o.kind == "merge" },
                        streamType = if (o.isHls) "hls" else streamTypeOf(o.url),
                        incognito = tab.incognito,
                        detected = detected,
                    ),
                )
            },
            onClose = close,
        )
    }
}

@Composable
private fun TabsSheet(model: BrowserModel, close: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(bottom = 16.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tabs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { model.open(Settings.current.homePage.ifEmpty { null }, incognito = false, inNewTab = true); close() }) {
                Icon(Icons.Rounded.Add, null)
                Text("New")
            }
            if (model.incognitoSupported) {
                TextButton(onClick = { model.open(null, incognito = true, inNewTab = true); close() }) {
                    Icon(Icons.Rounded.VisibilityOff, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Private")
                }
            }
        }
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(model.tabs.toList(), key = { it.id }) { t ->
                val selected = t === model.current
                Surface(
                    onClick = { model.select(t); close() },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (t.incognito) Icons.Rounded.VisibilityOff else Icons.Rounded.Public, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title.ifBlank { if (t.isBlank) "New tab" else t.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            if (!t.isBlank) {
                                Text(LinkParser.hostOf(t.url).orEmpty(), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { model.close(t) }) { Icon(Icons.Rounded.Close, "Close tab") }
                    }
                }
            }
        }
        if (model.tabs.any { it.incognito }) {
            TextButton(onClick = { model.closeAll(incognito = true) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Close private tabs")
            }
        }
    }
}

@Composable
private fun PagesSheet(model: BrowserModel, bookmarks: Boolean, close: () -> Unit) {
    val list by (if (bookmarks) BrowserStore.bookmarks else BrowserStore.history).collectAsStateWithLifecycle()
    Column(Modifier.padding(bottom = 16.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (bookmarks) "Bookmarks" else "History", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (!bookmarks && list.isNotEmpty()) TextButton(onClick = BrowserStore::clearHistory) { Text("Clear") }
        }
        if (list.isEmpty()) {
            Text(
                if (bookmarks) "No bookmarks yet." else "No history.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            items(list, key = { it.url + it.time }) { e ->
                PageRow(
                    e,
                    onClick = {
                        model.current?.let { model.load(it, e.url) } ?: model.open(e.url)
                        close()
                    },
                    trailing = {
                        IconButton(onClick = {
                            if (bookmarks) BrowserStore.toggleBookmark(e.url, e.title) else BrowserStore.removeHistory(e)
                        }) { Icon(Icons.Rounded.Close, "Remove") }
                    },
                )
            }
        }
    }
}

/** "Download link…": a media URL with optional Referer / User-Agent / Cookie. */
@Composable
private fun ManualSheet(model: BrowserModel, s: BrowserSheet.Manual, close: () -> Unit) {
    val tab = model.current
    val base = s.headers ?: tab?.let { model.headersFor(it, null) } ?: Headers()
    var url by remember { mutableStateOf(s.url) }
    var referer by remember { mutableStateOf(base.referer.orEmpty()) }
    var ua by remember { mutableStateOf(base.userAgent.orEmpty()) }
    var cookie by remember { mutableStateOf(s.headers?.cookie ?: tab?.takeIf { url.isNotEmpty() }?.let { model.cookieManager(it).getCookie(url) }.orEmpty()) }
    var result by remember { mutableStateOf<ExtractResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val headers = Headers(userAgent = ua.ifBlank { null }, referer = referer.ifBlank { null }, cookie = cookie.ifBlank { null })
    val activity = currentActivity()

    val r = result
    if (r != null) {
        BrowserSheetContent(
            pageUrl = referer.ifBlank { url },
            detected = r,
            headers = headers,
            onPlay = { o, x ->
                close()
                PlayerHub.open(
                    activity,
                    PlayItem(
                        url = o.url, title = x.state.value.title, pageUrl = referer.ifBlank { url }, headers = x.headersFor(o),
                        audioUrl = o.audioUrl?.takeIf { o.kind == "merge" }, streamType = if (o.isHls) "hls" else streamTypeOf(o.url),
                        incognito = tab?.incognito == true, detected = r,
                    ),
                )
            },
            onClose = close,
        )
        return
    }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Download link", style = MaterialTheme.typography.titleLarge)
        Text(
            "A video or stream address (.mp4, .m3u8…). Headers are filled in from the current page; some sites require them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(url, { url = it }, label = { Text("Link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(referer, { referer = it }, label = { Text("Referer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(ua, { ua = it }, label = { Text("User-Agent") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cookie, { cookie = it }, label = { Text("Cookie") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            enabled = url.startsWith("http") && !busy,
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val probe = GoCore.probe(
                            referer.ifBlank { url },
                            listOf(Candidate(url.trim(), "direct", referer = referer.ifBlank { null }, cookie = cookie.ifBlank { null })),
                            headers,
                            skipPage = true,
                        )
                        if (probe.options.isEmpty()) error = probe.skipped.firstOrNull()?.reason ?: "No video at this address"
                        else result = probe.copy(pageUrl = referer.ifBlank { url })
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Cannot open this link"
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Find video")
            }
        }
    }
}
