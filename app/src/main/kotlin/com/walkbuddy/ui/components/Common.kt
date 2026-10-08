@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.walkbuddy.domain.Hero
import com.walkbuddy.ui.theme.NumberStyle
import com.walkbuddy.ui.theme.WbTheme
import kotlinx.coroutines.delay
import com.walkbuddy.domain.QrCode
import com.walkbuddy.domain.QrEncoder

/** True in the light themes. Used to pick card surfaces: white on pale plum by day, lifted charcoal on true black at night. */
@Composable
fun isLightSurface(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

/** The one card look used everywhere: 24 dp corners, a hairline border instead of a heavy fill, no stacked tints. */
@Composable
fun cardColor(): Color = if (isLightSurface()) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainer

@Composable
fun cardBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isLightSurface()) 0.7f else 0.55f))

val CardShape = RoundedCornerShape(24.dp)

@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    container: Color = cardColor(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = CardShape, color = container, border = cardBorder()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

/** A screen heading: big, in the display face, with an optional one-line subtitle. */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A thick, rounded progress bar. Pass a content description through [modifier] so it is announced. */
@Composable
fun WbProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondary, height: Dp = 10.dp) {
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(color.copy(alpha = 0.16f))) {
        if (f > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(CircleShape).background(color))
    }
}

/** A row of 2 to 4 numbers inside one card: value and unit on one line, the label under it, hairline dividers between. */
@Composable
fun StatStrip(items: List<StatItem>, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = CardShape, color = cardColor(), border = cardBorder()) {
        Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { i, it ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
                Column(
                    Modifier.weight(1f).padding(horizontal = 8.dp).semantics(mergeDescendants = true) { contentDescription = "${it.label} ${it.value} ${it.unit}" },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(it.value, style = NumberStyle, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                        if (it.unit.isNotEmpty()) Text(it.unit, Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Text(it.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Immutable
data class StatItem(val label: String, val value: String, val unit: String = "")

/** A soft entrance for stacked cards: fades and rises a few dp, staggered by [index]. Skipped when reduce-motion is on. */
@Composable
fun Modifier.staggerIn(index: Int): Modifier {
    val reduce = WbTheme.motion.reduceMotion
    val progress = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduce) {
            delay(60L * index.coerceAtMost(8))
            progress.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        }
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 14.dp.toPx()
    }
}

@Composable
fun ProgressRing(
    fraction: Float,
    centerTop: String,
    centerBottom: String,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    stroke: Dp = 16.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
            val sweep = 360f * fraction.coerceIn(0f, 1f)
            if (sweep > 0f) drawArc(color, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTop, style = MaterialTheme.typography.headlineMedium)
            Text(centerBottom, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val wb = WbTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(56.dp)) {
            val sw = 7.dp.toPx()
            drawArc(wb.ringTrack, 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
            drawArc(
                Brush.sweepGradient(listOf(wb.ringStart, wb.ringMid, wb.ringEnd, wb.ringStart)), -90f, 100f, false,
                Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round),
            )
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) action()
    }
}

@Composable
fun Disclaimer(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = NumberStyle.copy(fontSize = 18.sp, lineHeight = 22.sp))
    }
}

/** Rounded-top bars with the chosen bar in the brand gradient, its value above it, and an optional dashed goal line. */
@Composable
fun BarChart(
    values: List<Float>, labels: List<String>, description: String, modifier: Modifier = Modifier,
    highlight: Int = -1, goal: Float? = null,
) {
    val wb = WbTheme.colors
    val measurer = rememberTextMeasurer()
    val max = maxOf(values.maxOrNull() ?: 0f, goal ?: 0f, 1f) * 1.18f
    val dim = MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val valueStyle = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    val goalStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(148.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = slot * 0.56f
            val r = barW / 2f
            if (goal != null && goal > 0f) {
                val y = size.height * (1f - goal / max)
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                val t = measurer.measure("goal", goalStyle)
                drawText(t, topLeft = Offset(size.width - t.size.width, (y - t.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
            }
            values.forEachIndexed { i, v ->
                val h = (size.height * (v / max)).coerceAtLeast(6f)
                val x = i * slot + (slot - barW) / 2
                val top = size.height - h
                val brush = if (i == highlight) Brush.verticalGradient(listOf(wb.ringStart, wb.ringMid, wb.ringEnd), startY = top, endY = size.height) else Brush.verticalGradient(listOf(dim, dim))
                drawRoundRect(brush, Offset(x, top), Size(barW, h), CornerRadius(r, r))
                if (i == highlight) {
                    val t = measurer.measure(Hero.thousands(v.toInt()), valueStyle)
                    drawText(t, topLeft = Offset((x + barW / 2 - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), (top - t.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(
                    l, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    color = if (i == highlight) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (i == highlight) FontWeight.Bold else null,
                )
            }
        }
    }
}

/** Renders our own pure-Kotlin QR encoder's output (see domain Qr.kt). */
@Composable
fun QrView(text: String, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val qr: QrCode? = remember(text) { runCatching { QrEncoder.encode(text) }.getOrNull() }
    if (qr == null) return
    Canvas(modifier.size(size).semantics { contentDescription = "QR code for the join link" }) {
        val quiet = 4
        val total = qr.size + quiet * 2
        val cell = this.size.width / total
        drawRect(Color.White, Offset.Zero, this.size)
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr.isDark(x, y)) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell, cell))
        }
    }
}
