package com.downvid.downvid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The DownVid logo (same geometry as assets/brand/logo.svg, 108-unit grid):
 * a downward "play" triangle (a download arrow) over a tray.
 */
@Composable
fun DownVidLogo(size: Dp = 40.dp, background: Boolean = true, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 108f
        scale(k, k, pivot = Offset.Zero) {
            if (background) {
                val tl = Offset(2f, 2f)
                val sz = Size(104f, 104f)
                val r = CornerRadius(26f)
                drawRoundRect(Brush.linearGradient(listOf(Brand.Violet, Brand.Pink), tl, Offset(106f, 106f)), tl, sz, r)
                drawRoundRect(
                    Brush.radialGradient(listOf(Color(0x38FFFFFF), Color(0x00FFFFFF)), Offset(33f, 22f), 83f),
                    tl, sz, r,
                )
            }
            val triangle = Path().apply {
                moveTo(36f, 37f); lineTo(72f, 37f); lineTo(54f, 60f); close()
            }
            drawPath(triangle, Color.White, style = Fill)
            drawPath(triangle, Color.White, style = Stroke(7f, join = StrokeJoin.Round, cap = StrokeCap.Round))
            drawLine(Color.White, Offset(37f, 72.5f), Offset(71f, 72.5f), 7f, StrokeCap.Round)
        }
    }
}

/** Rounded square with a soft tinted background, for icons in lists and tips. */
@Composable
fun IconBadge(icon: ImageVector, size: Dp = 40.dp, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.size(size).background(color.copy(alpha = 0.12f), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

/** Large icon + title + hint, centered; for empty lists. */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBadge(icon, 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SectionTitle(
    title: String,
    padding: PaddingValues = PaddingValues(start = 4.dp, top = 24.dp, end = 4.dp, bottom = 8.dp),
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(padding), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** The hosting activity, also inside dialogs and bottom sheets (whose context is a wrapper). */
@Composable
fun currentActivity(): android.app.Activity {
    androidx.activity.compose.LocalActivity.current?.let { return it }
    var c = androidx.compose.ui.platform.LocalContext.current
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    error("no activity")
}
