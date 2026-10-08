package com.walkbuddy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.walkbuddy.domain.AvatarStyle
import kotlin.math.cos
import kotlin.math.sin
import com.walkbuddy.domain.Avatar as WalkerAvatar

/**
 * The little walkers of the Track: three original, simple characters drawn with Canvas paths (no images, no fonts, nothing from
 * anywhere else). A boy with short hair, a girl with a ponytail and a dress, and a round neutral pawn. The colour is the walker's own pick.
 * Heads are a light tint of the walker's colour so no skin tone is implied; the faces are left blank on purpose.
 */
object WalkerPalette {
    /** Twelve colours that read on light and dark surfaces. Index = [WalkerAvatar.colorIndex]. */
    val colors: List<Color> = listOf(
        Color(0xFFE2603F), Color(0xFFF0A02C), Color(0xFFC9A227), Color(0xFF5E9E6E), Color(0xFF1F9A8E), Color(0xFF3A86D1),
        Color(0xFF5A5FD0), Color(0xFF9055D8), Color(0xFFD24E9E), Color(0xFFE5486A), Color(0xFF8A6A58), Color(0xFF5F7482),
    )
    val names: List<String> = listOf(
        "Coral", "Amber", "Gold", "Green", "Teal", "Blue", "Indigo", "Violet", "Magenta", "Rose", "Brown", "Slate",
    )

    fun color(index: Int): Color = colors[((index % colors.size) + colors.size) % colors.size]
}

/**
 * Draws one walker standing on [baseY] (their feet), centred on [cx], [h] pixels tall. [phase] (radians) swings the legs and arms;
 * pass 0 for a still pose. [bob] (0..1) lifts the body a little at mid-stride. [alpha] fades the whole figure (a buddy gone quiet).
 */
fun DrawScope.drawWalker(
    avatar: WalkerAvatar, cx: Float, baseY: Float, h: Float, phase: Float, ink: Color, alpha: Float = 1f, faceRight: Boolean = true,
) {
    val dir = if (faceRight) 1f else -1f
    val c = WalkerPalette.color(avatar.colorIndex).copy(alpha = alpha)
    val skin = lerp(WalkerPalette.color(avatar.colorIndex), Color.White, 0.55f).copy(alpha = alpha)
    val dark = ink.copy(alpha = 0.85f * alpha)
    val bob = (sin(phase * 2f) * 0.5f + 0.5f) * h * 0.02f
    val sw = (h * 0.075f).coerceAtLeast(2f)
    val footY = baseY
    val swing = sin(phase)
    when (avatar.style) {
        AvatarStyle.Round -> {
            // A round pawn: a soft body, a smaller head, and two little feet that take turns.
            val bodyR = h * 0.27f
            val bodyCy = footY - h * 0.16f - bodyR - bob
            val headR = h * 0.15f
            drawCircle(dark, h * 0.07f, Offset(cx + dir * swing * h * 0.12f, footY - h * 0.05f))
            drawCircle(dark, h * 0.07f, Offset(cx - dir * swing * h * 0.12f, footY - h * 0.05f))
            drawCircle(c, bodyR, Offset(cx, bodyCy))
            drawCircle(skin, headR, Offset(cx + dir * h * 0.02f, bodyCy - bodyR - headR * 0.55f))
        }
        AvatarStyle.Boy -> {
            val headR = h * 0.13f
            val headC = Offset(cx + dir * h * 0.02f, footY - h + headR - bob)
            val neckY = headC.y + headR
            val hipY = footY - h * 0.40f - bob
            val hipX = cx
            // legs
            val legLen = h * 0.40f
            for (side in intArrayOf(1, -1)) {
                val a = swing * 0.55f * side
                drawLine(dark, Offset(hipX, hipY), Offset(hipX + dir * sin(a) * legLen, hipY + cos(a) * legLen), sw, StrokeCap.Round)
            }
            // torso
            drawLine(c, Offset(cx, neckY + h * 0.01f), Offset(hipX, hipY + h * 0.02f), h * 0.20f, StrokeCap.Round)
            // arms
            val shoulderY = neckY + h * 0.07f
            for (side in intArrayOf(1, -1)) {
                val a = -swing * 0.5f * side
                drawLine(c, Offset(cx, shoulderY), Offset(cx + dir * sin(a) * h * 0.26f, shoulderY + cos(a) * h * 0.26f), sw * 0.8f, StrokeCap.Round)
            }
            drawCircle(skin, headR, headC)
            // short hair: a cap over the top of the head
            drawArc(dark, 180f, 180f, true, Offset(headC.x - headR * 1.05f, headC.y - headR * 1.05f), androidx.compose.ui.geometry.Size(headR * 2.1f, headR * 1.5f))
        }
        AvatarStyle.Girl -> {
            val headR = h * 0.13f
            val headC = Offset(cx + dir * h * 0.02f, footY - h + headR - bob)
            val neckY = headC.y + headR
            val hipY = footY - h * 0.36f - bob
            // legs below the dress
            val legLen = h * 0.36f
            for (side in intArrayOf(1, -1)) {
                val a = swing * 0.5f * side
                drawLine(dark, Offset(cx, hipY), Offset(cx + dir * sin(a) * legLen, hipY + cos(a) * legLen), sw, StrokeCap.Round)
            }
            // dress: a soft trapezoid from the shoulders to above the knees
            val top = neckY + h * 0.03f
            val dress = Path().apply {
                moveTo(cx - h * 0.09f, top)
                lineTo(cx + h * 0.09f, top)
                lineTo(cx + h * 0.20f, hipY + h * 0.02f)
                lineTo(cx - h * 0.20f, hipY + h * 0.02f)
                close()
            }
            drawPath(dress, c)
            // arms
            for (side in intArrayOf(1, -1)) {
                val a = -swing * 0.5f * side
                drawLine(skin, Offset(cx, top + h * 0.04f), Offset(cx + dir * sin(a) * h * 0.24f, top + h * 0.04f + cos(a) * h * 0.24f), sw * 0.7f, StrokeCap.Round)
            }
            drawCircle(skin, headR, headC)
            // hair cap and a ponytail that swings behind
            drawArc(dark, 180f, 180f, true, Offset(headC.x - headR * 1.05f, headC.y - headR * 1.05f), androidx.compose.ui.geometry.Size(headR * 2.1f, headR * 1.4f))
            val tailBase = Offset(headC.x - dir * headR * 0.9f, headC.y - headR * 0.1f)
            val sway = cos(phase) * h * 0.04f
            val tail = Path().apply {
                moveTo(tailBase.x, tailBase.y)
                quadraticBezierTo(tailBase.x - dir * h * 0.14f, tailBase.y + sway, tailBase.x - dir * h * 0.10f, tailBase.y + h * 0.15f)
            }
            drawPath(tail, dark, style = Stroke(width = sw * 1.1f, cap = StrokeCap.Round))
        }
    }
}

/** A small still walker for lists, pickers and the history. [description] is read by screen readers. */
@Composable
fun WalkerBadge(avatar: WalkerAvatar, modifier: Modifier = Modifier, size: Dp = 40.dp, description: String? = null) {
    val ink = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier.size(size).let { m -> if (description != null) m.semantics { contentDescription = description } else m },
    ) {
        drawWalker(avatar, cx = this.size.width / 2f, baseY = this.size.height * 0.96f, h = this.size.height * 0.92f, phase = 0.6f, ink = ink)
    }
}
