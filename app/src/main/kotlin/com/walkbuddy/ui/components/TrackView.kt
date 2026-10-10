package com.walkbuddy.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.walkbuddy.domain.TrackCamera
import com.walkbuddy.domain.TrackCue
import com.walkbuddy.domain.TrackLayout
import com.walkbuddy.domain.TrackWalker
import com.walkbuddy.domain.UnitSystem
import com.walkbuddy.ui.theme.WbTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

/** What is currently drawn, eased toward what is true. Only the draw block reads it; [tick] makes the canvas redraw without recomposition. */
private class TrackAnim {
    val x = HashMap<String, Double>()
    val phase = HashMap<String, Float>()
    var left = Double.NaN
    var window = Double.NaN
    var glow = 0f
}

/**
 * The Track: a metro-platform picture of a walk. Every walker has a lane on the same line; the line scrolls past as you walk, stations
 * mark the distance, a bracket shows the gap, and a soft glow appears when everyone is within a few metres. All geometry comes from the
 * pure, tested [TrackLayout]; this only draws it.
 *
 * Cost: one canvas, redrawn at [fps] (12 by default, 4 on [lowPower]) while the screen is on and this UI is resumed. With reduce-motion on,
 * or [still], it draws once per change and never loops. Nothing here talks to the network.
 */
@Composable
fun TrackView(
    walkers: List<TrackWalker>,
    realGapM: Double?,
    unit: UnitSystem,
    modifier: Modifier = Modifier,
    lowPower: Boolean = false,
    /** Replay and previews: jump straight to the data, no easing and no loop. */
    still: Boolean = false,
) {
    val reduce = WbTheme.motion.reduceMotion
    val animate = !reduce && !still
    val fps = if (lowPower) 4 else 12
    val camera = remember { TrackCamera() }
    val frame = remember(walkers, realGapM, unit) { TrackLayout.frame(walkers, camera, realGapM, unit) }
    val frameNow = rememberUpdatedState(frame)
    val anim = remember { TrackAnim() }
    val tick = remember { mutableLongStateOf(0L) }
    val measurer = rememberTextMeasurer()
    val owner = LocalLifecycleOwner.current

    // Nothing animates: snap to the truth whenever it changes.
    LaunchedEffect(frame, animate) {
        if (!animate) {
            anim.x.clear()
            for (l in frame.lanes) l.walker.xM?.let { anim.x[l.walker.id] = it }
            anim.left = frame.leftM; anim.window = frame.windowM
            tick.longValue++
        }
    }
    // The loop: ease toward the data at a low frame rate, only while the screen shows this UI.
    LaunchedEffect(animate, fps, owner) {
        if (!animate) return@LaunchedEffect
        val frameMs = 1000L / fps
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = SystemClock.uptimeMillis()
            while (true) {
                val now = SystemClock.uptimeMillis()
                val dt = ((now - last) / 1000f).coerceIn(0f, 0.5f)
                last = now
                stepAnim(anim, frameNow.value, dt)
                tick.longValue++
                delay(frameMs)
            }
        }
    }

    val scene = LocalTrackScene.current
    val style = remember(scene) { sceneStyle(scene) }
    val ink = style.ink ?: MaterialTheme.colorScheme.onSurface
    val faint = if (style.ink != null) style.ink.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant
    val panel = style.panel ?: MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    val warm = WbTheme.colors.glow
    val rail = style.rail ?: MaterialTheme.colorScheme.onSurfaceVariant
    val bg = style.sky ?: MaterialTheme.colorScheme.surfaceContainerLow

    Canvas(
        modifier.semantics { contentDescription = "Track. ${frame.description}" },
    ) {
        if (tick.longValue < 0L) return@Canvas // read here: a new tick redraws only this canvas
        drawTrack(frame, anim, unit, measurer, ink, faint, panel, warm, rail, bg, scene, style)
    }
}

private fun stepAnim(a: TrackAnim, f: com.walkbuddy.domain.TrackFrame, dt: Float) {
    for (l in f.lanes) {
        val id = l.walker.id
        val target = l.walker.xM ?: continue
        val cur = a.x[id]
        val next = if (cur == null) target else TrackLayout.ease(cur, target, rate = 0.35)
        if (cur != null && abs(next - cur) > 0.05) {
            a.phase[id] = ((a.phase[id] ?: 0f) + 9.6f * dt) % (Math.PI.toFloat() * 2f)
        }
        a.x[id] = next
    }
    a.left = if (a.left.isNaN()) f.leftM else TrackLayout.ease(a.left, f.leftM, rate = 0.3, snapM = 0.2)
    a.window = if (a.window.isNaN()) f.windowM else TrackLayout.ease(a.window, f.windowM, rate = 0.25, snapM = 0.5)
    val glowTarget = if (f.together) 1f else 0f
    a.glow += (glowTarget - a.glow) * 0.25f
}

private fun DrawScope.drawTrack(
    f: com.walkbuddy.domain.TrackFrame, a: TrackAnim, unit: UnitSystem, m: TextMeasurer,
    ink: Color, faint: Color, panel: Color, warm: Color, rail: Color, bg: Color,
    scene: com.walkbuddy.domain.TrackScene = com.walkbuddy.domain.TrackScene.Metro, style: SceneStyle = sceneStyle(scene),
) {
    val w = size.width; val h = size.height
    if (w <= 0f || h <= 0f) return
    val dp = density
    drawRoundRect(bg, size = size, cornerRadius = CornerRadius(20f * dp))
    drawScene(scene, style)
    val left = if (a.left.isNaN()) f.leftM else a.left
    val win = if (a.window.isNaN()) f.windowM else a.window
    val padX = 28f * dp
    val laneCount = max(1, f.lanes.size)
    val topH = 52f * dp
    val bottomH = 22f * dp
    val laneH = ((h - topH - bottomH) / laneCount).coerceIn(44f * dp, 100f * dp)
    val usedH = topH + laneH * laneCount + bottomH
    val offsetY = max(0f, (h - usedH) / 2f)
    fun xOf(metres: Double): Float = padX + (((metres - left) / win).toFloat()) * (w - 2 * padX)
    fun fracX(metres: Double): Float = xOf(metres).coerceIn(padX * 0.6f, w - padX * 0.6f)
    val lanesTop = offsetY + topH

    // Together glow behind the group.
    val xs = f.lanes.mapNotNull { l -> a.x[l.walker.id] ?: l.walker.xM }
    if (xs.isNotEmpty() && a.glow > 0.02f) {
        val cx = fracX((xs.min() + xs.max()) / 2.0)
        val cy = lanesTop + laneH * laneCount / 2f
        val r = min(w * 0.42f, (laneH * laneCount) * 0.9f + 40f * dp)
        drawCircle(warm.copy(alpha = 0.22f * a.glow), r, Offset(cx, cy))
        drawCircle(warm.copy(alpha = 0.16f * a.glow), r * 0.65f, Offset(cx, cy))
    }

    // Stations: a dashed line across the lanes and a small label on top. The ones already passed are dim.
    val leader = xs.maxOrNull()
    for (s in TrackLayout.stations(left, win, leader, unit)) {
        val sx = xOf(s.m)
        if (sx < padX * 0.5f || sx > w - padX * 0.5f) continue
        val alpha = if (s.passed) 0.35f else 0.8f
        drawLine(
            faint.copy(alpha = alpha), Offset(sx, lanesTop - 6f * dp), Offset(sx, lanesTop + laneH * laneCount),
            1.5f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * dp, 5f * dp)),
        )
        val t = m.measure(s.label, TextStyle(color = ink.copy(alpha = alpha), fontSize = 11.sp, fontWeight = FontWeight.Medium))
        drawText(t, topLeft = Offset(sx - t.size.width / 2f, lanesTop - 8f * dp - t.size.height))
    }

    // Lanes: two rails and sleepers that scroll with the line, then the walker.
    f.lanes.forEachIndexed { i, lane ->
        val wk = lane.walker
        val laneTop = lanesTop + laneH * i
        val footY = laneTop + laneH * 0.78f
        // rails
        drawLine(rail.copy(alpha = 0.55f), Offset(padX * 0.4f, footY + 3f * dp), Offset(w - padX * 0.4f, footY + 3f * dp), 2f * dp)
        if (style.pathLike) {
            drawLine(rail.copy(alpha = 0.35f), Offset(padX * 0.4f, footY + 7f * dp), Offset(w - padX * 0.4f, footY + 7f * dp), 9f * dp, StrokeCap.Round)
        } else {
            drawLine(rail.copy(alpha = 0.3f), Offset(padX * 0.4f, footY + 8f * dp), Offset(w - padX * 0.4f, footY + 8f * dp), 2f * dp)
        }
        // sleepers every 5 m of the line
        val stepM = 5.0
        var k = Math.ceil(left / stepM) * stepM
        while (k < left + win) {
            val sx = xOf(k)
            drawLine(rail.copy(alpha = 0.22f), Offset(sx, footY + 1f * dp), Offset(sx, footY + 10f * dp), 1.5f * dp)
            k += stepM
        }
        val x = a.x[wk.id] ?: wk.xM
        if (x == null) {
            val t = m.measure("${if (wk.isMe) "You" else wk.name}: waiting for a location", TextStyle(color = ink.copy(alpha = 0.6f), fontSize = 12.sp))
            drawText(t, topLeft = Offset(padX, laneTop + laneH * 0.4f))
            return@forEachIndexed
        }
        val px = fracX(x)
        val alpha = if (wk.stale) 0.45f else 1f
        val pawnH = min(laneH * 0.78f, 70f * dp)
        drawWalker(wk.avatar, px, footY, pawnH, a.phase[wk.id] ?: 0.6f, ink, alpha)
        // name above the head, and the cue / last-seen note under the rails
        val label = if (wk.isMe) "You" else wk.name
        val t = m.measure(
            label, TextStyle(color = ink.copy(alpha = alpha), fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (96f * dp).toInt()),
        )
        val tx = (px - t.size.width / 2f).coerceIn(2f * dp, w - t.size.width - 2f * dp)
        drawText(t, topLeft = Offset(tx, footY - pawnH - t.size.height - 2f * dp))
        val note = when {
            wk.stale -> TrackLayout.lastSeen(wk.lastSeenSec)
            lane.cue == TrackCue.Front -> "Ahead"
            lane.cue == TrackCue.Back -> "Behind"
            lane.offscreen < 0 -> "Further back"
            lane.offscreen > 0 -> "Further ahead"
            else -> null
        }
        if (note != null) {
            val nt = m.measure(note, TextStyle(color = ink.copy(alpha = 0.7f * alpha), fontSize = 10.sp))
            val nx = (px - nt.size.width / 2f).coerceIn(2f * dp, w - nt.size.width - 2f * dp)
            drawText(nt, topLeft = Offset(nx, footY + 11f * dp))
        }
        if (lane.offscreen != 0) {
            // an arrow at the edge showing which way they went
            val ex = if (lane.offscreen < 0) padX * 0.5f else w - padX * 0.5f
            val d = if (lane.offscreen < 0) -1f else 1f
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(ex + d * 6f * dp, footY - 10f * dp); lineTo(ex - d * 4f * dp, footY - 4f * dp); lineTo(ex + d * 6f * dp, footY + 2f * dp); close()
            }
            drawPath(p, WalkerPalette.color(wk.avatar.colorIndex).copy(alpha = alpha))
        }
    }

    // The gap bracket and its words, above the lanes.
    val placed = f.lanes.mapNotNull { l -> (a.x[l.walker.id] ?: l.walker.xM) }
    val textColor = if (f.together) warm else ink
    val gt = m.measure(f.gapText, TextStyle(color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Bold))
    val by = offsetY + 12f * dp
    if (placed.size >= 2) {
        val x1 = fracX(placed.min()); val x2 = fracX(placed.max())
        if (x2 - x1 > 24f * dp) {
            drawLine(ink.copy(alpha = 0.55f), Offset(x1, by + gt.size.height + 4f * dp), Offset(x2, by + gt.size.height + 4f * dp), 2f * dp)
            drawLine(ink.copy(alpha = 0.55f), Offset(x1, by + gt.size.height - 2f * dp), Offset(x1, by + gt.size.height + 10f * dp), 2f * dp)
            drawLine(ink.copy(alpha = 0.55f), Offset(x2, by + gt.size.height - 2f * dp), Offset(x2, by + gt.size.height + 10f * dp), 2f * dp)
        }
        val mid = (x1 + x2) / 2f
        val gx = (mid - gt.size.width / 2f).coerceIn(4f * dp, w - gt.size.width - 4f * dp)
        drawRoundRect(panel, Offset(gx - 6f * dp, by - 2f * dp), Size(gt.size.width + 12f * dp, gt.size.height + 4f * dp), CornerRadius(10f * dp))
        drawText(gt, topLeft = Offset(gx, by))
    } else {
        drawText(gt, topLeft = Offset((w - gt.size.width) / 2f, by))
    }
    if (f.hidden > 0) {
        val ht = m.measure("+${f.hidden} more not shown", TextStyle(color = ink.copy(alpha = 0.65f), fontSize = 11.sp))
        drawText(ht, topLeft = Offset(w - ht.size.width - 10f * dp, h - ht.size.height - 4f * dp))
    }
}
