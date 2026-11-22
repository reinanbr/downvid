package com.downvid.downvid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.downvid.downvid.browser.BrowserModel
import com.downvid.downvid.browser.BrowserScreen
import com.downvid.downvid.core.JobController
import com.downvid.downvid.core.Settings
import com.downvid.downvid.ui.DisclaimerDialog
import com.downvid.downvid.ui.DownVidTheme
import com.downvid.downvid.ui.DownloadsScreen
import com.downvid.downvid.ui.HomeScreen
import com.downvid.downvid.ui.SettingsScreen
import com.downvid.downvid.ui.ShareSheetContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What an incoming intent asks the main screen to show. */
sealed interface MainRequest {
    data object Downloads : MainRequest
    data class Analyze(val text: String) : MainRequest
    data class Browse(val url: String?) : MainRequest
}

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    BROWSER("Browser", Icons.Rounded.Public),
    DOWNLOADS("Downloads", Icons.Rounded.Download),
    SETTINGS("Settings", Icons.Rounded.Tune),
}

class MainActivity : ComponentActivity() {
    private val requests = MutableStateFlow<MainRequest?>(null)

    /** Created when the browser is first opened (WebView start-up is not free). */
    private var browser: BrowserModel? = null
    private fun browser(): BrowserModel = browser ?: BrowserModel(this).also { browser = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        JobController.start(this)
        YtDlp.warmUp(this)
        if (savedInstanceState == null) handle(intent)
        setContent {
            DownVidTheme {
                val req by requests.collectAsStateWithLifecycle()
                MainScreen(req, onHandled = { requests.value = null }, browser = ::browser)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        requests.value = when (intent?.action) {
            ACTION_DOWNLOADS -> MainRequest.Downloads
            Intent.ACTION_VIEW -> intent.dataString?.let { MainRequest.Browse(it) }
            else -> null
        } ?: requests.value
    }

    override fun onDestroy() {
        browser?.destroy()
        browser = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_DOWNLOADS = "com.downvid.downvid.DOWNLOADS"
    }
}

@Composable
private fun MainScreen(request: MainRequest?, onHandled: () -> Unit, browser: () -> BrowserModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var sheetText by rememberSaveable { mutableStateOf<String?>(null) }
    var disclaimer by remember { mutableStateOf(!Settings.current.disclaimerAccepted) }
    val snackbar = remember { SnackbarHostState() }
    val jobs by JobController.jobs.collectAsStateWithLifecycle()
    val loaded by JobController.loaded.collectAsStateWithLifecycle()
    val active = jobs.count { it.state in setOf("queued", "running", "waiting", "done", "expired") }

    LaunchedEffect(request) {
        when (request) {
            MainRequest.Downloads -> tab = Tab.DOWNLOADS
            is MainRequest.Analyze -> sheetText = request.text
            is MainRequest.Browse -> {
                tab = Tab.BROWSER
                browser().open(request.url, incognito = false, inNewTab = request.url != null)
            }
            null -> return@LaunchedEffect
        }
        onHandled()
    }
    BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // The browser has its own toolbar.
            if (tab != Tab.BROWSER) NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            if (t == Tab.DOWNLOADS && active > 0) {
                                BadgedBox(badge = { Badge { Text("$active") } }) { Icon(t.icon, null) }
                            } else {
                                Icon(t.icon, null)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(if (tab == Tab.BROWSER) androidx.compose.foundation.layout.PaddingValues() else padding)) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    jobs,
                    onAnalyze = { sheetText = it },
                    onOpenBrowser = { url ->
                        tab = Tab.BROWSER
                        if (url != null) browser().open(url, incognito = false, inNewTab = true)
                    },
                    onOpenDownloads = { tab = Tab.DOWNLOADS },
                )
                Tab.BROWSER -> BrowserScreen(browser(), onHome = { tab = Tab.HOME })
                Tab.DOWNLOADS -> DownloadsScreen(jobs, loaded, snackbar)
                Tab.SETTINGS -> SettingsScreen(snackbar, onClearBrowsing = { browser().clearBrowsingData() })
            }
        }
    }

    sheetText?.let { text -> LinkSheet(text) { sheetText = null } }
    if (disclaimer) DisclaimerDialog { disclaimer = false }
}

/** The download sheet over the main screen. */
@Composable
fun LinkSheet(text: String, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ShareSheetContent(text, onClose = {
            scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
        })
    }
}
