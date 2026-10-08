package com.walkbuddy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkbuddy.domain.BadgeId
import com.walkbuddy.domain.Hero
import com.walkbuddy.domain.HourlyHistogram
import com.walkbuddy.domain.HourlyProfile
import com.walkbuddy.domain.MonthGridModel
import com.walkbuddy.domain.WeeklyRecapBuilder
import com.walkbuddy.ui.theme.WbTheme

/** 24 bars, one per hour of the day, with the best walking hour in the full gradient. */
@Composable
fun HourlyChart(profile: HourlyProfile, is24h: Boolean, modifier: Modifier = Modifier) {
    val wb = WbTheme.colors
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val max = profile.max.coerceAtLeast(1)
    val desc = HourlyHistogram.bestText(profile, is24h)
    Column(modifier.fillMaxWidth().semantics { contentDescription = "Steps by hour of day. $desc" }) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / 24f
            val barW = slot * 0.62f
            profile.perHour.forEachIndexed { h, v ->
                val bh = (size.height * (v.toFloat() / max)).coerceAtLeast(3f)
                val x = h * slot + (slot - barW) / 2
                val best = h == profile.bestHour
                drawRoundRect(
                    brush = if (best) Brush.verticalGradient(listOf(wb.ringStart, wb.ringEnd), startY = size.height - bh, endY = size.height) else Brush.verticalGradient(listOf(dim, dim)),
                    topLeft = Offset(x, size.height - bh), size = Size(barW, bh), cornerRadius = CornerRadius(barW / 2, barW / 2),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18).forEach {
                Text(HourlyHistogram.hourLabel(it, is24h), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val DOW = listOf("M", "T", "W", "T", "F", "S", "S")

/** A month as a grid of rounded cells; the warmer the colour, the closer to (or past) the goal. Tap a day for its steps. */
@Composable
fun MonthHeatmap(model: MonthGridModel, monthLabel: String, dayLabel: (Long) -> String, modifier: Modifier = Modifier) {
    val wb = WbTheme.colors
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val outline = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val numberStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    var selected by remember(model.year, model.month) { mutableStateOf<Int?>(null) }
    val rows = (model.leadingBlanks + model.cells.size + 6) / 7
    val levelColors = listOf(track, wb.ringStart.copy(alpha = 0.35f), wb.ringStart.copy(alpha = 0.8f), wb.ringMid, wb.ringEnd)
    Column(
        modifier.fillMaxWidth().semantics {
            contentDescription = "$monthLabel: ${model.daysWithSteps} days with steps, ${model.goalDays} days reaching the goal"
        },
    ) {
        Row(Modifier.fillMaxWidth()) {
            DOW.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(7f / rows).pointerInput(model) {
                detectTapGestures { p ->
                    val cell = size.width / 7f
                    val col = (p.x / cell).toInt().coerceIn(0, 6)
                    val row = (p.y / cell).toInt()
                    val idx = row * 7 + col - model.leadingBlanks
                    selected = if (idx in model.cells.indices && !model.cells[idx].isFuture) idx else null
                }
            },
        ) {
            val cell = size.width / 7f
            val pad = cell * 0.08f
            model.cells.forEachIndexed { i, c ->
                val pos = i + model.leadingBlanks
                val x = (pos % 7) * cell
                val y = (pos / 7) * cell
                val color = if (c.isFuture) track.copy(alpha = 0.03f) else levelColors[c.level.coerceIn(0, 4)]
                drawRoundRect(color, Offset(x + pad, y + pad), Size(cell - pad * 2, cell - pad * 2), CornerRadius(cell * 0.28f, cell * 0.28f))
                if (c.isToday || selected == i) {
                    drawRoundRect(outline, Offset(x + pad, y + pad), Size(cell - pad * 2, cell - pad * 2), CornerRadius(cell * 0.28f, cell * 0.28f), style = Stroke(2.dp.toPx()))
                }
                val layout = measurer.measure(c.dayOfMonth.toString(), numberStyle)
                drawText(layout, topLeft = Offset(x + (cell - layout.size.width) / 2f, y + (cell - layout.size.height) / 2f))
            }
        }
        val sel = selected?.let { model.cells.getOrNull(it) }
        Text(
            if (sel == null) "Tap a day to see its steps." else "${dayLabel(sel.epochDay)}: ${Hero.thousands(sel.steps)} steps",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A medal: gradient when earned, a quiet outline with a progress arc when still locked. */
@Composable
fun BadgeMedal(id: BadgeId, earned: Boolean, fraction: Float, modifier: Modifier = Modifier, size: Dp = 76.dp) {
    val wb = WbTheme.colors
    val locked = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = 5.dp.toPx()
            val tl = Offset(sw / 2, sw / 2)
            val sz = Size(this.size.width - sw, this.size.height - sw)
            if (earned) {
                drawCircle(Brush.linearGradient(listOf(wb.ringStart, wb.ringMid, wb.ringEnd)), radius = this.size.width / 2)
                drawCircle(Color.White.copy(alpha = 0.22f), radius = this.size.width / 2 - sw * 1.2f, style = Stroke(1.5.dp.toPx()))
            } else {
                drawCircle(fill, radius = this.size.width / 2)
                drawArc(locked, 0f, 360f, false, tl, sz, style = Stroke(sw))
                if (fraction > 0f) {
                    drawArc(wb.ringMid, -90f, 360f * fraction.coerceIn(0f, 1f), false, tl, sz, style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
        }
        Text(
            id.glyph, style = MaterialTheme.typography.titleMedium,
            color = if (earned) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Day labels such as "Thu 8 Oct" come from the weekday math in the domain module. */
fun shortDayName(epochDay: Long): String = WeeklyRecapBuilder.dayName(epochDay).take(3)
