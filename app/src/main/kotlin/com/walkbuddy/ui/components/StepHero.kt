package com.walkbuddy.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkbuddy.domain.Confetti
import com.walkbuddy.domain.Hero
import com.walkbuddy.domain.RingBuddy
import com.walkbuddy.ui.theme.BigNumberStyle
import com.walkbuddy.ui.theme.NumberStyle
import com.walkbuddy.ui.theme.WbTheme
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Rough width of the number in em for the bundled display face: tabular digits 0.585, comma and point about 0.2, minus a hair of tracking. */
private fun numberEm(text: String): Float {
    val em = text.sumOf { if (it == ',' || it == '.') 0.2 else 0.585 } - 0.005 * text.length
    return em.toFloat().coerceAtLeast(0.6f)
}

/**
 * The hero of the app: a giant, perfectly centred step count that counts up inside a gradient ring. A small label sits
 * above the number and "to go" below it, both the same height, so the number itself is the visual centre. Buddies sit on
 * the ring as avatar dots at their own progress, and a soft glow warms the space behind it.
 */
@Composable
fun StepHero(
    steps: Int,
    goal: Int,
    buddies: List<RingBuddy>,
    walking: Boolean,
    modifier: Modifier = Modifier,
    caption: String = "steps today",
    maxSize: Dp = 380.dp,
) {
    val motion = WbTheme.motion
    val wb = WbTheme.colors
    val animSteps = remember { Animatable(0f) }
    val animFraction = remember { Animatable(0f) }
    val target = steps.coerceAtLeast(0)
    val fraction = Hero.fraction(target, goal).toFloat()
    LaunchedEffect(target) {
        if (motion.reduceMotion) animSteps.snapTo(target.toFloat())
        else animSteps.animateTo(target.toFloat(), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessVeryLow))
    }
    LaunchedEffect(fraction) {
        if (motion.reduceMotion) animFraction.snapTo(fraction)
        else animFraction.animateTo(fraction, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow))
    }
    val pulse = if (walking && !motion.reduceMotion) {
        rememberInfiniteTransition(label = "glow").animateFloat(
            initialValue = 0.25f, targetValue = 0.65f,
            animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glowAlpha",
        ).value
    } else if (Hero.goalReached(target, goal)) 0.45f else 0.0f

    val shown = animSteps.value.roundToInt()
    val numberText = Hero.thousands(shown)
    val fitText = Hero.thousands(target)
    val pct = (fraction * 100).roundToInt()
    val reached = Hero.goalReached(target, goal)
    val description = "$target steps today. Goal $goal, $pct percent." + if (reached) " Goal reached." else ""
    val onSurface = MaterialTheme.colorScheme.onSurface
    val wash = if (isLightSurface()) 0.13f else 0.2f

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val diameter = if (maxWidth < maxSize) maxWidth else maxSize
        val stroke = diameter * 0.068f
        val inner = (diameter - stroke * 2 - 52.dp).value.coerceAtLeast(80f)
        val density = LocalDensity.current
        val numberSp = with(density) { (inner / numberEm(fitText)).coerceIn(56f, 132f).dp.toSp() }
        val radiusPx = with(density) { (diameter - stroke).toPx() / 2f }

        Box(Modifier.size(diameter).semantics(mergeDescendants = true) { contentDescription = description }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = stroke.toPx()
                val topLeft = Offset(sw / 2, sw / 2)
                val arcSize = Size(size.width - sw, size.height - sw)
                val c = Offset(size.width / 2, size.height / 2)
                // Warm wash behind the ring: depth without a hard shadow.
                drawCircle(
                    Brush.radialGradient(listOf(wb.glow.copy(alpha = wash), Color.Transparent), center = c, radius = size.width * 0.62f),
                    radius = size.width * 0.62f, center = c,
                )
                drawArc(wb.ringTrack, 0f, 360f, false, topLeft, arcSize, style = Stroke(sw))
                val sweep = 360f * animFraction.value
                if (sweep > 0.5f) {
                    val f = (sweep / 360f).coerceAtLeast(0.06f)
                    val head = Offset(
                        c.x + radiusPx * cos(Math.toRadians((sweep - 90f).toDouble())).toFloat(),
                        c.y + radiusPx * sin(Math.toRadians((sweep - 90f).toDouble())).toFloat(),
                    )
                    if (pulse > 0f) {
                        val gr = sw * 1.9f
                        drawCircle(
                            brush = Brush.radialGradient(listOf(wb.glow.copy(alpha = pulse), Color.Transparent), center = head, radius = gr),
                            radius = gr, center = head,
                        )
                    }
                    rotate(-90f, c) {
                        // The gradient is stretched over the filled part only, and wraps back to the start colour, so there is no seam at 12 o'clock.
                        drawArc(
                            brush = Brush.sweepGradient(
                                0f to wb.ringStart, f * 0.55f to wb.ringMid, f to wb.ringEnd, 1f to wb.ringStart, center = c,
                            ),
                            startAngle = 0f, sweepAngle = sweep, useCenter = false, topLeft = topLeft, size = arcSize,
                            style = Stroke(sw, cap = StrokeCap.Round),
                        )
                    }
                    // A bright pip on the leading edge.
                    drawCircle(Color.White.copy(alpha = 0.85f), radius = sw * 0.16f, center = head)
                }
            }

            buddies.forEach { b ->
                val ang = Math.toRadians((360.0 * b.fraction) - 90.0)
                val dx = (radiusPx * cos(ang)).toFloat()
                val dy = (radiusPx * sin(ang)).toFloat()
                BuddyDot(b.initial, Modifier.align(Alignment.Center).offset { IntOffset(dx.roundToInt(), dy.roundToInt()) })
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    caption, Modifier.heightIn(min = 26.dp), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    numberText, style = BigNumberStyle, fontSize = numberSp, lineHeight = numberSp, maxLines = 1, softWrap = false,
                    color = onSurface, textAlign = TextAlign.Center,
                )
                Text(
                    Hero.toGoText(target, goal), Modifier.heightIn(min = 26.dp), style = MaterialTheme.typography.titleMedium,
                    color = if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun BuddyDot(initial: String, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val wb = WbTheme.colors
    Surface(
        modifier = modifier.size(size), shape = CircleShape, color = wb.buddy,
        border = BorderStroke(2.5.dp, MaterialTheme.colorScheme.background), shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(initial, color = wb.onBuddy, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** "Verified 5,247, raw sensor 5,390": the honest-steps line, with a small check. */
@Composable
fun VerifiedChip(verified: Int, raw: Int, modifier: Modifier = Modifier) {
    val diff = raw - verified
    val text = if (diff > 20) "Verified ${Hero.thousands(verified)}, raw sensor ${Hero.thousands(raw)}" else "All ${Hero.thousands(verified)} steps verified"
    val tint = MaterialTheme.colorScheme.secondary
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Verified steps $verified, raw sensor steps $raw" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(16.dp)) {
            drawCircle(tint.copy(alpha = 0.16f))
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.68f)
                lineTo(size.width * 0.74f, size.height * 0.34f)
            }
            drawPath(p, tint, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Buddy ahead/behind pills under the ring: an avatar and one plain sentence, e.g. "Meera is 873 steps ahead". */
@Composable
fun BuddyLeadRow(buddies: List<RingBuddy>, mySteps: Int, modifier: Modifier = Modifier) {
    if (buddies.isEmpty()) return
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        buddies.forEach { b ->
            Surface(shape = CircleShape, color = cardColor(), border = cardBorder()) {
                Row(Modifier.padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BuddyDot(b.initial, size = 30.dp)
                    Text(Hero.leadText(b.name, mySteps, b.steps), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** A single stat in a soft pill (kept for previews and small spots; screens use StatStrip). */
@Composable
fun StatPill(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label $value $unit" },
        shape = MaterialTheme.shapes.medium, color = cardColor(), border = cardBorder(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value $unit", style = NumberStyle, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Tasteful confetti from the ring when the goal is reached. Does nothing when [active] is false. */
@Composable
fun ConfettiBurst(active: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier, seed: Long = 11L) {
    val colors = WbTheme.colors.confetti
    val pieces = remember(seed) { Confetti.spawn(90, seed, colors.size) }
    val t = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            t.snapTo(0f)
            t.animateTo(Confetti.DURATION_S + 0.3f, tween(((Confetti.DURATION_S + 0.3f) * 1000).toInt(), easing = LinearEasing))
            onDone()
        }
    }
    if (!active) return
    Canvas(modifier.fillMaxSize()) {
        pieces.forEach { p ->
            val pos = Confetti.position(p, t.value) ?: return@forEach
            val w = p.size * size.width
            val center = Offset(pos.x * size.width, pos.y * size.height)
            rotate(pos.rotation, center) {
                drawRect(
                    colors[p.colorIndex % colors.size].copy(alpha = pos.alpha),
                    topLeft = Offset(center.x - w / 2, center.y - w / 4), size = Size(w, w / 2),
                )
            }
        }
    }
}
