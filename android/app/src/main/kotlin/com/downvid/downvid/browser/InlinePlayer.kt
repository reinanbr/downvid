package com.downvid.downvid.browser

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.downvid.downvid.player.PlayItem
import com.downvid.downvid.player.PlayerScreen
import com.downvid.downvid.ui.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** DownVid's player in place of the page video [id] (in its frame). */
class InlineVideo(val id: String, val item: PlayItem, internal val reply: (String) -> Unit) {
    /** Where the page's video is: top-frame document CSS px; null until known. */
    var rect by mutableStateOf<VideoRect?>(null)
    var fullscreen by mutableStateOf(false)
}

/**
 * The page's video element in document CSS px ([k] device px per CSS px),
 * and the part of it that shows ([cx], [cy], [cw], [ch]): its ancestors can
 * crop it (rounded or fixed-ratio containers, iframes).
 */
data class VideoRect(
    val x: Float, val y: Float, val w: Float, val h: Float, val k: Float,
    val cx: Float = x, val cy: Float = y, val cw: Float = w, val ch: Float = h,
) {
    val cropped get() = cx != x || cy != y || cw != w || ch != h
}

/**
 * The in-page player: drawn over the page's video element, following the
 * page as it scrolls (the WebView's scroll is applied at layout time, so
 * scrolling does not wait for the page script).
 */
@OptIn(UnstableApi::class)
@Composable
fun InlinePlayer(
    tab: BrowserTab,
    v: InlineVideo,
    player: ExoPlayer,
    onDownload: () -> Unit,
    onFullscreen: () -> Unit,
    onClose: () -> Unit,
) {
    val rect = v.rect ?: return
    val ui = rememberPlayerUi(player)
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    KeepScreenOn(ui.playing)
    LaunchedEffect(controls, ui.playing, interaction) {
        if (controls && ui.playing) {
            delay(3000)
            controls = false
        }
    }
    // The box is the visible part of the page's element; the video inside it
    // keeps the element's full size and position, cropped like the page's.
    Box(
        Modifier
            .layout { measurable, _ ->
                val w = (rect.cw * rect.k).roundToInt().coerceAtLeast(1)
                val h = (rect.ch * rect.k).roundToInt().coerceAtLeast(1)
                val p = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) {
                    p.place((rect.cx * rect.k).roundToInt() - tab.scrollX, (rect.cy * rect.k).roundToInt() - tab.scrollY)
                }
            }
            .clipToBounds()
            .background(Color.Black),
    ) {
        VideoSurface(
            player,
            Modifier.layout { measurable, _ ->
                val w = (rect.w * rect.k).roundToInt().coerceAtLeast(1)
                val h = (rect.h * rect.k).roundToInt().coerceAtLeast(1)
                val p = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) {
                    p.place(((rect.x - rect.cx) * rect.k).roundToInt(), ((rect.y - rect.cy) * rect.k).roundToInt())
                }
            },
        )
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    detectTapGestures(
                        onTap = { controls = !controls; interaction++ },
                        onDoubleTap = { o ->
                            when {
                                o.x < size.width / 3 -> player.seekBack()
                                o.x > size.width * 2 / 3 -> player.seekForward()
                                else -> if (player.isPlaying) player.pause() else player.play()
                            }
                            interaction++
                        },
                    )
                },
        )
        if (ui.buffering && ui.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White, strokeWidth = 3.dp)
        }
        ui.error?.let { err ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(err, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
                    TextButton(onClick = { ui.error = null; player.prepare(); player.play() }) { Text("Try again") }
                }
            }
        }
        AnimatedVisibility(controls && ui.error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                Row(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = onDownload) { Icon(Icons.Rounded.Download, "Download", tint = Color.White) }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Page's player", tint = Color.White) }
                }
                if (!ui.buffering) {
                    IconButton(
                        onClick = {
                            when {
                                ui.ended -> { player.seekTo(0); player.play() }
                                player.isPlaying -> player.pause()
                                else -> player.play()
                            }
                            interaction++
                        },
                        modifier = Modifier.align(Alignment.Center).size(56.dp),
                    ) {
                        Icon(
                            when {
                                ui.ended -> Icons.Rounded.Replay
                                ui.playing -> Icons.Rounded.Pause
                                else -> Icons.Rounded.PlayArrow
                            },
                            if (ui.playing) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${formatDuration(ui.position / 1000.0)} / ${formatDuration(ui.duration / 1000.0)}",
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    )
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = dragging ?: if (ui.duration > 0) (ui.position.toFloat() / ui.duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = it; interaction++ },
                        onValueChangeFinished = {
                            dragging?.let { player.seekTo((it * ui.duration).toLong()) }
                            dragging = null
                        },
                        enabled = ui.duration > 0,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = onFullscreen) { Icon(Icons.Rounded.Fullscreen, "Fullscreen", tint = Color.White) }
                }
            }
        }
    }
}

/**
 * The in-page player in fullscreen, still in the browser (the page stays
 * loaded underneath): the full player UI, starting in portrait.
 */
@Composable
fun InlineFullscreen(v: InlineVideo, player: ExoPlayer, onExit: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    DisposableEffect(v) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // The brightness gesture applies to the window.
            activity.window.attributes = activity.window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
    val ui = rememberPlayerUi(player)
    KeepScreenOn(ui.playing)
    BackHandler(onBack = onExit)
    PlayerScreen(
        player = player,
        item = v.item,
        inPip = false,
        onClose = onExit,
        onPip = null,
        onRotate = {
            val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: ExoPlayer, modifier: Modifier) {
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                this.player = player
            }
        },
        update = { if (it.player !== player) it.player = player },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

private class PlayerUi(player: Player) {
    var playing by mutableStateOf(player.isPlaying)
    var buffering by mutableStateOf(player.playbackState == Player.STATE_BUFFERING)
    var ended by mutableStateOf(player.playbackState == Player.STATE_ENDED)
    var duration by mutableLongStateOf(0L)
    var position by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun rememberPlayerUi(player: ExoPlayer): PlayerUi {
    val ui = remember(player) { PlayerUi(player) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                ui.playing = p.isPlaying
                ui.buffering = p.playbackState == Player.STATE_BUFFERING
                ui.ended = p.playbackState == Player.STATE_ENDED
                ui.duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0
                ui.position = p.currentPosition
                if (p.playbackState == Player.STATE_READY) ui.error = null
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("DownVid", "player: ${error.errorCodeName}: ${error.message}")
                ui.error = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The site refused the video"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection"
                    else -> "Cannot play this video"
                }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player, ui.playing) {
        while (true) {
            ui.position = player.currentPosition
            ui.duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
            delay(if (ui.playing) 500 else 1500)
        }
    }
    return ui
}
