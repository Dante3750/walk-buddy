package com.walkbuddy.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.walkbuddy.R
import com.walkbuddy.domain.SummaryCardPlan
import com.walkbuddy.ui.components.WalkerPalette
import java.io.File

/**
 * Draws the walk summary card (alpha 2.0) with plain android.graphics: a small Track strip of how the walkers moved, four numbers, and,
 * only when the person switched it on, an outline of their own route. No map tiles, no place names and no street names are ever drawn.
 */
object SummaryCardRenderer {
    const val W = 1080
    const val H = 1350

    fun render(plan: SummaryCardPlan, labels: Map<String, String>, footer: String, togetherLabel: String): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), Color.parseColor("#1D2B3A"), Color.parseColor("#0B1219"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = null

        p.textAlign = Paint.Align.CENTER
        p.color = Color.parseColor("#FFD9A0")
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = 44f
        c.drawText(plan.dateText, W / 2f, 120f, p)
        p.color = Color.WHITE
        p.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        p.textSize = fit(p, plan.title, W - 140f, 84f)
        c.drawText(plan.title, W / 2f, 215f, p)

        // The Track strip: one lane per walker, a dot at each sampled moment, joined by a soft line.
        val left = 110f; val right = W - 110f
        var top = 300f
        val laneH = if (plan.laneAvatars.size > 4) 70f else 96f
        val lanes = plan.laneAvatars.size.coerceAtLeast(1)
        val stripH = laneH * lanes + 40f
        p.color = Color.argb(28, 255, 255, 255)
        c.drawRoundRect(RectF(60f, top - 30f, W - 60f, top - 30f + stripH + 30f), 36f, 36f, p)
        for (lane in 0 until lanes) {
            val y = top + laneH * lane + laneH / 2f
            val col = WalkerPalette.color(plan.laneAvatars.getOrNull(lane)?.colorIndex ?: lane).toArgb()
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = Color.argb(70, 255, 255, 255)
            c.drawLine(left, y, right, y, p)
            p.style = Paint.Style.FILL
            val n = plan.strip.size.coerceAtLeast(2)
            plan.strip.forEachIndexed { i, step ->
                val v = step.getOrNull(lane) ?: return@forEachIndexed
                // Where this walker was relative to the group at that moment (0 = back, 1 = front); later moments are bolder.
                val x = left + (right - left) * v
                val a = 60 + (195 * i / (n - 1))
                p.color = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))
                c.drawCircle(x, y, if (i == n - 1) 22f else 10f + 8f * i / n, p)
            }
        }
        top += stripH + 20f

        // Optional route outline.
        val route = plan.route
        if (route != null && route.size >= 2) {
            val box = RectF(W / 2f - 230f, top + 10f, W / 2f + 230f, top + 470f)
            p.style = Paint.Style.STROKE; p.strokeWidth = 9f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            p.color = Color.parseColor("#7FD6C2")
            val path = Path()
            route.forEachIndexed { i, (x, y) ->
                val px = box.left + x * box.width(); val py = box.top + y * box.height()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            c.drawPath(path, p)
            p.style = Paint.Style.FILL
            top += 500f
        } else {
            top += 40f
        }

        // Four numbers.
        p.style = Paint.Style.FILL
        val cols = 2
        val cellW = (W - 160f) / cols
        plan.stats.take(4).forEachIndexed { i, (key, value) ->
            val cx = 80f + cellW * (i % cols) + cellW / 2f
            val cy = top + 110f + 190f * (i / cols)
            p.color = Color.WHITE
            p.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            p.textSize = fit(p, value, cellW - 30f, 96f)
            c.drawText(value, cx, cy, p)
            p.color = Color.argb(200, 255, 255, 255)
            p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            p.textSize = 40f
            c.drawText(labels[key] ?: key, cx, cy + 56f, p)
        }

        p.typeface = Typeface.create("serif", Typeface.BOLD)
        p.textSize = 52f
        p.color = Color.parseColor("#FFB347")
        c.drawText(footer, W / 2f, H - 70f, p)
        return bmp
    }

    private fun fit(p: Paint, text: String, maxWidth: Float, maxSize: Float): Float {
        p.textSize = maxSize
        val w = p.measureText(text)
        return if (w > maxWidth) maxSize * maxWidth / w else maxSize
    }

    /** Saves the card as a PNG in the cache and opens the share sheet. */
    fun share(context: Context, bmp: Bitmap, chooserTitle: String): Boolean = runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000L) it.delete() }
        val file = File(dir, "walk-summary-${System.currentTimeMillis()}.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(context.getString(R.string.share_card_description), uri)
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
