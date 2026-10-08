package com.walkbuddy.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.sp
import com.walkbuddy.ui.theme.NumberStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.walkbuddy.domain.FlameInfo
import com.walkbuddy.domain.FlameLevel
import com.walkbuddy.ui.theme.WbTheme

private fun flamePath(cx: Float, top: Float, h: Float, lean: Float): Path {
    val w = h * 0.62f
    val bottom = top + h
    return Path().apply {
        moveTo(cx + lean, top)
        cubicTo(cx + w * 0.15f + lean * 0.6f, top + h * 0.25f, cx + w * 0.55f, top + h * 0.45f, cx + w * 0.5f, top + h * 0.72f)
        cubicTo(cx + w * 0.45f, top + h * 0.95f, cx + w * 0.2f, bottom, cx, bottom)
        cubicTo(cx - w * 0.2f, bottom, cx - w * 0.45f, top + h * 0.95f, cx - w * 0.5f, top + h * 0.72f)
        cubicTo(cx - w * 0.55f, top + h * 0.45f, cx - w * 0.15f - lean * 0.6f, top + h * 0.25f, cx + lean, top)
        close()
    }
}

private fun DrawScope.drawFlame(level: FlameLevel, outer: Color, inner: Color, outline: Color, flicker: Float) {
    val h = size.height * when (level) {
        FlameLevel.Spark -> 0.6f
        FlameLevel.Ember -> 0.7f
        FlameLevel.Flame -> 0.8f
        FlameLevel.Blaze -> 0.9f
        FlameLevel.Inferno -> 1.0f
    } * (0.96f + 0.04f * flicker)
    val cx = size.width / 2
    val top = size.height - h
    val lean = (flicker - 0.5f) * size.width * 0.08f
    if (level == FlameLevel.Spark) {
        // Not lit yet: just the outline of a flame waiting for you.
        drawPath(flamePath(cx, top, h, 0f), outline, style = Stroke(width = size.width * 0.06f))
        return
    }
    if (level == FlameLevel.Blaze || level == FlameLevel.Inferno) {
        val gr = size.width * (if (level == FlameLevel.Inferno) 0.95f else 0.8f)
        drawCircle(Brush.radialGradient(listOf(outer.copy(alpha = 0.32f), Color.Transparent), center = Offset(cx, top + h * 0.62f), radius = gr), gr, Offset(cx, top + h * 0.62f))
    }
    drawPath(flamePath(cx, top, h, lean), Brush.verticalGradient(listOf(inner, outer), startY = top, endY = top + h))
    drawPath(flamePath(cx, top + h * 0.38f, h * 0.62f, lean * 0.5f), inner.copy(alpha = 0.95f))
}

@Composable
fun FlameIcon(level: FlameLevel, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val wb = WbTheme.colors
    // Flicker at ~10 fps, only while resumed, read inside the Canvas (draw only). The old version re-composed the whole icon every frame, forever.
    val animate = level != FlameLevel.Spark && !WbTheme.motion.reduceMotion
    val flicker = rememberPulse(periodMs = 900, enabled = animate, fps = 10)
    val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier.size(size)) { drawFlame(level, wb.flameOuter, wb.flameInner, outline, flicker.value) }
}

/** The streak: a flame that grows with it, the count in the display face, and rest tokens as small labelled pips. */
@Composable
fun StreakCard(info: FlameInfo, modifier: Modifier = Modifier) {
    val wb = WbTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${info.title}. ${info.subtitle}" },
        shape = CardShape, color = cardColor(), border = cardBorder(),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FlameIcon(info.level, size = 60.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(info.title, style = MaterialTheme.typography.titleLarge)
                Text(info.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(2) { i ->
                        val lit = i < info.tokens
                        Surface(
                            shape = CircleShape, color = if (lit) wb.ringStart else Color.Transparent,
                            border = if (lit) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.size(14.dp),
                        ) {}
                    }
                }
                Text("rest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The flame and the day count in a small pill, for headers. */
@Composable
fun StreakChip(info: FlameInfo, modifier: Modifier = Modifier) {
    Surface(
        modifier.semantics(mergeDescendants = true) { contentDescription = info.title },
        shape = CircleShape, color = cardColor(), border = cardBorder(),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            FlameIcon(info.level, size = 32.dp)
            Text("${info.days}", style = NumberStyle.copy(fontSize = 20.sp))
        }
    }
}
