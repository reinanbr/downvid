package com.downvid.downvid.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color as AColor
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.downvid.downvid.core.Candidate
import com.downvid.downvid.core.ExtractResult
import com.downvid.downvid.core.GoCore
import com.downvid.downvid.core.Settings
import com.downvid.downvid.ui.BrowserSheetContent
import com.downvid.downvid.ui.DownVidTheme
import com.downvid.downvid.ui.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen player for PlayerHub's item: gestures (double tap to seek,
 * swipe to seek / brightness / volume, hold for 2x), speed, lock, fit/fill/
 * zoom, picture-in-picture, remembered position and a download button.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val inPip = mutableStateOf(false)
    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = PlayerHub.playerOrNull
        if (p == null || PlayerHub.item.value == null) {
            // Process restarted with the player in recents: nothing to play.
            finish()
            return
        }
        player = p
        // Always opens in portrait; the rotate button switches to landscape.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                updatePipParams()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                updatePipParams()
            }
        }.also(p::addListener)
        setContent {
            DownVidTheme(dark = true) {
                val item by PlayerHub.item.collectAsStateWithLifecycle()
                val it = item
                // The browser's in-page player took the player over.
                LaunchedEffect(it) { if (it != null && PlayerHub.inPage) finish() }
                if (it != null && !PlayerHub.inPage) {
                    PlayerScreen(
                        player = p,
                        item = it,
                        inPip = inPip.value,
                        onClose = ::finish,
                        onPip = ::enterPip,
                        onRotate = ::rotate,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Another video replaced the current one: start it in portrait too.
        if (!inPip.value) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    private fun rotate() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val p = player ?: return null
        val vs = p.videoSize
        val b = PictureInPictureParams.Builder()
        if (vs.width > 0 && vs.height > 0) {
            val r = (vs.width.toFloat() / vs.height).coerceIn(1 / 2.39f, 2.39f)
            b.setAspectRatio(Rational((r * 1000).roundToInt(), 1000))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(p.isPlaying && vs.width > 0)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= 26) pipParams()?.let { runCatching { setPictureInPictureParams(it) } }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        pipParams()?.let { runCatching { enterPictureInPictureMode(it) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP by itself (setAutoEnterEnabled).
        val p = player ?: return
        if (Build.VERSION.SDK_INT in 26..30 && p.isPlaying && p.videoSize.width > 0) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        // The PiP window was closed (not expanded): stop.
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) {
            player?.pause()
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        if (player != null) PlaybackService.start(this)
    }

    override fun onStop() {
        super.onStop()
        visible = false
        PlayerHub.savePosition(this)
        val p = player ?: return
        if (!isFinishing && !inPip.value && !Settings.current.playerBackgroundAudio) p.pause()
    }

    override fun onDestroy() {
        listener?.let { player?.removeListener(it) }
        if (isFinishing && player != null) {
            Log.i(TAG, "player: closed")
            if (!PlayerHub.inPage) PlayerHub.release(this)
            PlaybackService.stop(this)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownVid"
        @Volatile var visible = false
            private set
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

private enum class Fit(val mode: Int, val label: String) {
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT, "Fit"),
    FILL(AspectRatioFrameLayout.RESIZE_MODE_FILL, "Stretch"),
    ZOOM(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Zoom"),
}

/** Player state for the UI (positions polled while visible). */
private class UiState(player: Player) {
    var playing by mutableStateOf(player.isPlaying)
    var buffering by mutableStateOf(player.playbackState == Player.STATE_BUFFERING)
    var ended by mutableStateOf(player.playbackState == Player.STATE_ENDED)
    var duration by mutableLongStateOf(0L)
    var position by mutableLongStateOf(0L)
    var live by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/**
 * The full player UI (gestures, controls, download sheet), in
 * PlayerActivity and in the browser's fullscreen in-page player
 * ([onPip] null: no picture-in-picture button).
 */
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerScreen(
    player: ExoPlayer,
    item: PlayItem,
    inPip: Boolean,
    onClose: () -> Unit,
    onPip: (() -> Unit)?,
    onRotate: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val activity = ctx as ComponentActivity
    val ui = remember(player) { UiState(player) }
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var fit by remember { mutableStateOf(Fit.FIT) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var speedMenu by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<Pair<ImageVector, String>?>(null) }
    var hintSeq by remember { mutableIntStateOf(0) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var showDownload by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) } // restarts the auto-hide timer
    val scope = rememberCoroutineScope()

    fun flash(icon: ImageVector, text: String) {
        hint = icon to text
        hintSeq++
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                ui.playing = p.isPlaying
                ui.buffering = p.playbackState == Player.STATE_BUFFERING
                ui.ended = p.playbackState == Player.STATE_ENDED
                ui.live = p.isCurrentMediaItemLive
                ui.duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0
                ui.position = p.currentPosition
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("DownVid", "player: ${error.errorCodeName}: ${error.message}")
                ui.error = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The site refused the video (link expired?)"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection"
                    PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
                    PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED -> "This video is protected (DRM)"
                    else -> "Cannot play this video (${error.errorCodeName})"
                }
                controls = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) ui.error = null
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    // Position ticker, only while something shows it.
    LaunchedEffect(controls, ui.playing, inPip) {
        while (controls && !inPip) {
            ui.position = player.currentPosition
            ui.duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
            delay(500)
        }
    }
    // Auto-hide.
    LaunchedEffect(controls, ui.playing, interaction, speedMenu) {
        if (controls && ui.playing && !speedMenu && ui.error == null) {
            delay(3500)
            controls = false
        }
    }
    LaunchedEffect(hintSeq) {
        if (hint != null) {
            delay(700)
            hint = null
        }
    }
    BackHandler(enabled = locked) { flash(Icons.Rounded.Lock, "Locked") }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    setShutterBackgroundColor(AColor.BLACK)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                }
            },
            update = { it.resizeMode = fit.mode },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (inPip) return@Box

        // Gestures.
        val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { controls = !controls; interaction++ },
                        onDoubleTap = { o ->
                            if (locked) return@detectTapGestures
                            val third = size.width / 3
                            when {
                                o.x < third -> { player.seekBack(); flash(Icons.Rounded.FastRewind, "−10 s") }
                                o.x > size.width - third -> { player.seekForward(); flash(Icons.Rounded.FastForward, "+10 s") }
                                else -> if (player.isPlaying) player.pause() else player.play()
                            }
                        },
                        onLongPress = {
                            if (!locked && player.isPlaying) {
                                player.setPlaybackSpeed(2f)
                                flash(Icons.Rounded.FastForward, "2×")
                            }
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
                        },
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var axis = 0 // 0 undecided, 1 horizontal, 2 vertical
                    var acc = 0f
                    var startPos = 0L
                    var leftSide = false
                    var level = 0f
                    detectDragGestures(
                        onDragStart = { o ->
                            axis = 0
                            acc = 0f
                            startPos = player.currentPosition
                            leftSide = o.x < size.width / 2
                        },
                        onDragEnd = {
                            seekPreview?.let { player.seekTo(it) }
                            seekPreview = null
                        },
                        onDragCancel = { seekPreview = null },
                        onDrag = { change, d ->
                            change.consume()
                            if (axis == 0) {
                                axis = if (abs(d.x) > abs(d.y)) 1 else 2
                                if (axis == 2) {
                                    level = if (leftSide) {
                                        activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: systemBrightness(ctx)
                                    } else {
                                        audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    }
                                }
                            }
                            if (axis == 1) {
                                val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return@detectDragGestures
                                acc += d.x
                                // A full-width swipe = 90 s (or the whole video if shorter).
                                val span = minOf(dur, 90_000L)
                                seekPreview = (startPos + (acc / size.width * span).toLong()).coerceIn(0, dur)
                            } else {
                                level = (level - d.y / (size.height * 0.8f)).coerceIn(0f, 1f)
                                if (leftSide) {
                                    val lp = activity.window.attributes
                                    lp.screenBrightness = level.coerceAtLeast(0.01f)
                                    activity.window.attributes = lp
                                    flash(Icons.Rounded.BrightnessMedium, "${(level * 100).roundToInt()}%")
                                } else {
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
                                    flash(Icons.AutoMirrored.Rounded.VolumeUp, "${(level * 100).roundToInt()}%")
                                }
                            }
                        },
                    )
                },
        )

        seekPreview?.let { pos ->
            CenterHint("${formatDuration(pos / 1000.0)} / ${formatDuration(ui.duration / 1000.0)}", null)
        }
        hint?.let { (icon, text) -> if (seekPreview == null) CenterHint(text, icon) }
        if (ui.buffering && !controls) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White)
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            if (locked) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    IconButton(onClick = { locked = false; interaction++ }, modifier = Modifier.align(Alignment.CenterStart).padding(16.dp)) {
                        Icon(Icons.Rounded.Lock, "Unlock", tint = Color.White)
                    }
                }
                return@AnimatedVisibility
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                // Top bar.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                        .safeDrawingPadding()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close", tint = Color.White) }
                    Text(
                        item.title ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        TextButton(onClick = { speedMenu = true }) {
                            Text("${speed.toString().removeSuffix(".0")}×", color = Color.White)
                        }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            for (s in SPEEDS) {
                                DropdownMenuItem(
                                    text = { Text("${s.toString().removeSuffix(".0")}×" + if (s == speed) "  ✓" else "") },
                                    onClick = {
                                        speed = s
                                        player.setPlaybackSpeed(s)
                                        speedMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = {
                        fit = Fit.entries[(fit.ordinal + 1) % Fit.entries.size]
                        flash(Icons.Rounded.AspectRatio, fit.label)
                        interaction++
                    }) { Icon(Icons.Rounded.AspectRatio, "Fit: ${fit.label}", tint = Color.White) }
                    IconButton(onClick = onRotate) { Icon(Icons.Rounded.ScreenRotation, "Rotate", tint = Color.White) }
                    if (Build.VERSION.SDK_INT >= 26 && onPip != null) {
                        IconButton(onClick = onPip) { Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                    }
                    if (!item.local) {
                        IconButton(onClick = { showDownload = true }) { Icon(Icons.Rounded.Download, "Download", tint = Color.White) }
                    }
                }

                // Center buttons.
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(Icons.Rounded.Replay10, "Back 10 s", 52) { player.seekBack(); interaction++ }
                    if (ui.buffering) {
                        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(48.dp), color = Color.White)
                        }
                    } else {
                        RoundButton(
                            when {
                                ui.ended -> Icons.Rounded.Replay
                                ui.playing -> Icons.Rounded.Pause
                                else -> Icons.Rounded.PlayArrow
                            },
                            if (ui.playing) "Pause" else "Play",
                            72,
                        ) {
                            when {
                                ui.error != null -> { ui.error = null; player.prepare(); player.play() }
                                ui.ended -> { player.seekTo(0); player.play() }
                                ui.playing -> player.pause()
                                else -> player.play()
                            }
                            interaction++
                        }
                    }
                    RoundButton(Icons.Rounded.Forward10, "Forward 10 s", 52) { player.seekForward(); interaction++ }
                }

                ui.error?.let { err ->
                    Column(
                        Modifier.align(Alignment.Center).padding(top = 150.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(err, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.size(8.dp))
                        FilledTonalButton(onClick = { ui.error = null; player.prepare(); player.play() }) { Text("Try again") }
                    }
                }

                // Bottom bar.
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                        .safeDrawingPadding()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { locked = true; controls = true; interaction++; flash(Icons.Rounded.Lock, "Locked") }) {
                            Icon(Icons.Rounded.LockOpen, "Lock controls", tint = Color.White)
                        }
                        if (ui.live) {
                            Surface(color = Color(0xFFE53935), shape = RoundedCornerShape(6.dp)) {
                                Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        val pos = seekPreview ?: ui.position
                        Text(
                            if (ui.duration > 0) "${formatDuration(pos / 1000.0)} / ${formatDuration(ui.duration / 1000.0)}" else formatDuration(pos / 1000.0),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (ui.duration > 0) {
                        var dragging by remember { mutableStateOf<Float?>(null) }
                        Slider(
                            value = dragging ?: (ui.position.toFloat() / ui.duration).coerceIn(0f, 1f),
                            onValueChange = {
                                dragging = it
                                seekPreview = (it * ui.duration).toLong()
                                interaction++
                            },
                            onValueChangeFinished = {
                                dragging?.let { player.seekTo((it * ui.duration).toLong()) }
                                dragging = null
                                seekPreview = null
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            ),
                        )
                    }
                }
            }
        }
    }

    if (showDownload) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var detected by remember { mutableStateOf<ExtractResult?>(null) }
        LaunchedEffect(item) { detected = detectedFor(item) }
        ModalBottomSheet(
            onDismissRequest = { showDownload = false },
            sheetState = state,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            val d = detected
            if (d == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                BrowserSheetContent(
                    pageUrl = item.pageUrl.ifEmpty { item.url },
                    detected = d,
                    headers = item.headers,
                    onPlay = { o, x ->
                        scope.launch { state.hide() }.invokeOnCompletion { showDownload = false }
                        PlayerHub.open(activity, o.toPlayItem(item, x.headersFor(o)))
                    },
                    onClose = { scope.launch { state.hide() }.invokeOnCompletion { showDownload = false } },
                )
            }
        }
    }
}

/** The page's detected media, plus the playing URL resolved by the core when missing. */
private suspend fun detectedFor(item: PlayItem): ExtractResult {
    val d = item.detected ?: ExtractResult(pageUrl = item.pageUrl.ifEmpty { item.url }, title = item.title)
    if (d.options.any { it.url == item.url || it.group == item.url }) return d
    return try {
        val r = GoCore.probe(
            d.pageUrl,
            listOf(Candidate(item.url, "webview", referer = item.headers.referer, cookie = item.headers.cookie)),
            item.headers,
            skipPage = true,
        )
        d.copy(options = r.options + d.options, skipped = d.skipped + r.skipped)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        d
    }
}

private fun systemBrightness(ctx: Context): Float =
    runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }
        .getOrDefault(0.5f)

@Composable
private fun BoxScope.CenterHint(text: String, icon: ImageVector?) {
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.align(Alignment.Center),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, sizeDp: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.4f), modifier = Modifier.size(sizeDp.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size((sizeDp * 0.6f).dp))
        }
    }
}

/** A download option as a player item (same page session). */
fun com.downvid.downvid.core.MediaOption.toPlayItem(from: PlayItem, headers: com.downvid.downvid.core.Headers) = PlayItem(
    url = url,
    title = from.title,
    pageUrl = from.pageUrl,
    headers = headers,
    audioUrl = audioUrl?.takeIf { kind == "merge" },
    streamType = if (isHls) "hls" else null,
    incognito = from.incognito,
    detected = from.detected,
)
