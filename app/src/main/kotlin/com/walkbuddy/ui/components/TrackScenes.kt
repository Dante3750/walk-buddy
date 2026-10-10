package com.walkbuddy.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import com.walkbuddy.domain.TrackScene

/** The scenery behind the Track. Set once near the root from Settings; previews and tests can override it. */
val LocalTrackScene = staticCompositionLocalOf { TrackScene.Metro }

/** Colours a scene needs on top of the app theme. Night keeps its own dark sky in both light and dark mode, so it brings its own ink. */
class SceneStyle(val sky: Color?, val ink: Color?, val panel: Color?, val rail: Color?, val pathLike: Boolean)

fun sceneStyle(scene: TrackScene): SceneStyle = when (scene) {
    TrackScene.Metro -> SceneStyle(null, null, null, null, false)
    TrackScene.Train -> SceneStyle(Color(0xFFE7D6C0), Color(0xFF3A2D26), Color(0xE6FFF6EA), Color(0xFF5B4636), false)
    TrackScene.Trail -> SceneStyle(Color(0xFFCFE6C9), Color(0xFF1F3A26), Color(0xE6F4FFF0), Color(0xFF8A6A48), true)
    TrackScene.NightSky -> SceneStyle(Color(0xFF151A3C), Color(0xFFF1EEFF), Color(0xCC252B5C), Color(0xFFB9B4E8), false)
    TrackScene.Spring -> SceneStyle(Color(0xFFE5F3DD), Color(0xFF26402C), Color(0xE6FBFFF6), Color(0xFF9A7B5C), true)
    TrackScene.Summer -> SceneStyle(Color(0xFFCDE9F6), Color(0xFF1B3A4A), Color(0xE6F4FCFF), Color(0xFFB08850), true)
    TrackScene.Autumn -> SceneStyle(Color(0xFFF2DABB), Color(0xFF4A2C16), Color(0xE6FFF4E6), Color(0xFF8A5A36), true)
    TrackScene.Winter -> SceneStyle(Color(0xFFDDE8F3), Color(0xFF243447), Color(0xE6F8FBFF), Color(0xFF7C8FA3), true)
}

private fun hash(i: Int): Float {
    var x = i * 374761393 + 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xffff) / 65535f
}

/** Static, original scenery drawn behind the lanes. It never animates, so Reduce motion and Battery saver need no special case. */
fun DrawScope.drawScene(scene: TrackScene, style: SceneStyle) {
    val sky = style.sky ?: return
    val w = size.width; val h = size.height
    val dp = density
    val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, CornerRadius(20f * dp))) }
    clipPath(clip) {
        val top = if (scene == TrackScene.NightSky) Color(0xFF0C1030) else sky
        drawRect(Brush.verticalGradient(listOf(top, sky)), size = size)
        when (scene) {
            TrackScene.NightSky -> {
                for (i in 0 until 34) drawCircle(Color.White.copy(alpha = 0.35f + 0.5f * hash(i + 99)), (0.8f + 1.2f * hash(i + 7)) * dp, Offset(hash(i) * w, hash(i + 40) * h * 0.7f))
                val moon = Offset(w * 0.84f, h * 0.2f)
                drawCircle(Color(0xFFFFF4C9), 13f * dp, moon)
                drawCircle(Color(0xFF151A3C), 11f * dp, moon + Offset(5f * dp, -3f * dp))
                hills(Color(0xFF0E1230), 0.78f, 0.06f, 1)
            }
            TrackScene.Train -> {
                // telegraph poles with sagging wires along the top
                val gap = 92f * dp
                var x = gap / 2
                var prev: Offset? = null
                while (x < w + gap) {
                    val tp = Offset(x, h * 0.20f)
                    drawLine(Color(0xFF6B5546), Offset(x, h * 0.12f), Offset(x, h * 0.46f), 2.5f * dp, StrokeCap.Round)
                    drawLine(Color(0xFF6B5546), Offset(x - 8f * dp, h * 0.15f), Offset(x + 8f * dp, h * 0.15f), 2f * dp, StrokeCap.Round)
                    prev?.let { p ->
                        val wire = Path().apply { moveTo(p.x, p.y); quadraticBezierTo((p.x + tp.x) / 2, p.y + 14f * dp, tp.x, tp.y) }
                        drawPath(wire, Color(0xFF6B5546).copy(alpha = 0.7f), style = Stroke(1.2f * dp))
                    }
                    prev = Offset(x, h * 0.15f); x += gap
                }
                hills(Color(0xFFCDB89C), 0.82f, 0.05f, 2)
            }
            TrackScene.Trail -> {
                drawCircle(Color(0xFFFFE9A8), 16f * dp, Offset(w * 0.82f, h * 0.2f))
                hills(Color(0xFFA9D3A0), 0.7f, 0.08f, 3)
                hills(Color(0xFF7DB77A), 0.82f, 0.06f, 4)
                trees(Color(0xFF3F7D4C), 5)
            }
            TrackScene.Spring -> {
                hills(Color(0xFFBFE2B0), 0.78f, 0.07f, 5)
                for (i in 0 until 16) drawCircle(Color(0xFFF7B9CF), (2f + 2f * hash(i + 3)) * dp, Offset(hash(i + 11) * w, h * (0.15f + 0.6f * hash(i + 21))))
                trees(Color(0xFF6DB06F), 4)
            }
            TrackScene.Summer -> {
                drawCircle(Color(0xFFFFD25E), 18f * dp, Offset(w * 0.8f, h * 0.22f))
                drawCircle(Color(0xFFFFD25E).copy(alpha = 0.25f), 28f * dp, Offset(w * 0.8f, h * 0.22f))
                hills(Color(0xFF9ED08C), 0.78f, 0.07f, 6)
                trees(Color(0xFF3F9A55), 4)
            }
            TrackScene.Autumn -> {
                hills(Color(0xFFD9A25F), 0.78f, 0.07f, 7)
                for (i in 0 until 18) {
                    val c = listOf(Color(0xFFD9602A), Color(0xFFE0A030), Color(0xFFB5452B))[i % 3]
                    drawCircle(c.copy(alpha = 0.85f), (2.5f + 2f * hash(i + 1)) * dp, Offset(hash(i + 31) * w, h * (0.1f + 0.7f * hash(i + 51))))
                }
                trees(Color(0xFFB5652B), 4)
            }
            TrackScene.Winter -> {
                hills(Color(0xFFF4F8FC), 0.74f, 0.07f, 8)
                for (i in 0 until 26) drawCircle(Color.White.copy(alpha = 0.9f), (1.2f + 1.8f * hash(i + 5)) * dp, Offset(hash(i + 61) * w, h * 0.8f * hash(i + 71)))
                trees(Color(0xFF5E7F8F), 3)
            }
            TrackScene.Metro -> Unit
        }
    }
}

private fun DrawScope.hills(color: Color, baseFrac: Float, amp: Float, seed: Int) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(0f, h)
        lineTo(0f, h * baseFrac)
        val steps = 6
        for (i in 1..steps) {
            val x = w * i / steps
            val y = h * (baseFrac - amp * (hash(i + seed * 13) - 0.3f))
            quadraticBezierTo(x - w / steps / 2, y - h * amp * 0.6f, x, y)
        }
        lineTo(w, h)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.trees(color: Color, seed: Int) {
    val w = size.width; val h = size.height; val dp = density
    for (i in 0 until 5) {
        val x = w * (0.08f + 0.2f * i + 0.05f * hash(i + seed))
        val baseY = h * 0.80f
        val th = (22f + 14f * hash(i + seed * 3)) * dp
        val t = Path().apply { moveTo(x, baseY - th); lineTo(x - th * 0.32f, baseY); lineTo(x + th * 0.32f, baseY); close() }
        drawPath(t, color)
        drawLine(Color(0xFF6B4A32), Offset(x, baseY), Offset(x, baseY + 4f * dp), 2f * dp)
    }
}
