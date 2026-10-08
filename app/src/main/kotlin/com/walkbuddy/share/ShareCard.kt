package com.walkbuddy.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.walkbuddy.R
import java.io.File
import kotlin.math.min

/** What a share card shows. Stats only: no map, no places, no names of places. */
data class ShareSpec(
    val kicker: String,
    val bigNumber: String,
    val caption: String,
    val fraction: Float,
    val lines: List<String>,
    val footer: String = "Walk Buddy",
)

/**
 * Draws the card with plain android.graphics (no view needed), writes a PNG to the cache and opens the share sheet
 * through a FileProvider. The picture contains only numbers, never a map or a location.
 */
object ShareCard {
    private const val W = 1080
    private const val H = 1350

    fun render(spec: ShareSpec): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), Color.parseColor("#2B1030"), Color.parseColor("#11060F"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = null

        val cx = W / 2f
        val cy = 560f
        val r = 340f
        val sw = 74f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.argb(46, 255, 255, 255)
        c.drawArc(oval, 0f, 360f, false, p)
        val sweep = 360f * spec.fraction.coerceIn(0f, 1f)
        if (sweep > 0.5f) {
            p.shader = SweepGradient(cx, cy, intArrayOf(Color.parseColor("#FFB347"), Color.parseColor("#FF6B5E"), Color.parseColor("#D63384"), Color.parseColor("#FFB347")), floatArrayOf(0f, 0.5f, 0.95f, 1f))
            c.save()
            c.rotate(-90f, cx, cy)
            c.drawArc(oval, 0f, sweep, false, p)
            c.restore()
            p.shader = null
        }

        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.color = Color.parseColor("#FFD9A0")
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = 44f
        p.letterSpacing = 0.04f
        c.drawText(spec.kicker, cx, 150f, p)
        p.letterSpacing = 0f

        p.color = Color.WHITE
        p.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        p.fontFeatureSettings = "tnum"
        p.textSize = fitSize(p, spec.bigNumber, r * 1.45f, 230f)
        c.drawText(spec.bigNumber, cx, cy + p.textSize * 0.34f, p)
        p.fontFeatureSettings = null

        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.color = Color.argb(210, 255, 255, 255)
        p.textSize = 48f
        c.drawText(spec.caption, cx, cy + p.textSize * 0.34f + 150f, p)

        p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        p.textSize = 46f
        p.color = Color.WHITE
        var y = 1040f
        for (line in spec.lines.take(4)) {
            c.drawText(line, cx, y, p)
            y += 70f
        }

        p.typeface = Typeface.create("serif", Typeface.BOLD)
        p.textSize = 52f
        p.color = Color.parseColor("#FFB347")
        c.drawText(spec.footer, cx, H - 70f, p)
        return bmp
    }

    private fun fitSize(p: Paint, text: String, maxWidth: Float, maxSize: Float): Float {
        var size = maxSize
        p.textSize = size
        val w = p.measureText(text)
        if (w > maxWidth) size = size * maxWidth / w
        return min(size, maxSize)
    }

    /** Renders, saves and opens the system share sheet. Returns false if anything went wrong. */
    fun share(context: Context, spec: ShareSpec): Boolean = runCatching {
        val bmp = render(spec)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000L) it.delete() }
        val file = File(dir, "walk-buddy-${System.currentTimeMillis()}.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, spec.kicker + ": " + spec.bigNumber + " " + spec.caption)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(context.getString(R.string.share_card_description), uri)
        val chooser = Intent.createChooser(send, context.getString(R.string.share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        true
    }.getOrDefault(false)
}
