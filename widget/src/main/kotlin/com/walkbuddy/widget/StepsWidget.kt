package com.walkbuddy.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The big step count and goal ring on the home screen. The app writes the numbers to a small SharedPreferences file
 * (see WidgetBridge in :app); this module never touches the database, so it stays tiny and cannot break the main build.
 */
class StepsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong("at", 0L)
        val fresh = at >= startOfToday()
        val steps = if (fresh) prefs.getInt("steps", 0) else 0
        val goal = prefs.getInt("goal", 6000).coerceAtLeast(500)
        val fraction = (steps.toFloat() / goal).coerceIn(0f, 1f)
        val ring = renderRing(fraction)
        val open = Intent().setClassName(context.packageName, "com.walkbuddy.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        provideContent {
            Content(ring, String.format(Locale.US, "%,d", steps), String.format(Locale.US, "of %,d", goal), (fraction * 100).toInt(), open)
        }
    }

    @Composable
    private fun Content(ring: Bitmap, number: String, caption: String, percent: Int, open: Intent) {
        val ink = ColorProvider(day = ComposeColor(0xFF2A1B2E), night = ComposeColor(0xFFF3E8F0))
        val soft = ColorProvider(day = ComposeColor(0xFF4F4254), night = ComposeColor(0xFFD3C3D0))
        Box(
            modifier = GlanceModifier.fillMaxSize()
                .background(ColorProvider(day = ComposeColor(0xFFFBF6F9), night = ComposeColor(0xFF000000)))
                .cornerRadius(28.dp)
                .clickable(actionStartActivity(open)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(ring),
                contentDescription = "Steps today $number $caption, $percent percent",
                modifier = GlanceModifier.fillMaxSize().padding(10.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(number, style = TextStyle(color = ink, fontSize = 30.sp, fontWeight = FontWeight.Bold))
                Text(caption, style = TextStyle(color = soft, fontSize = 12.sp))
            }
        }
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** A gradient progress ring drawn with plain android.graphics, since widgets cannot host a Canvas. */
    private fun renderRing(fraction: Float): Bitmap {
        val size = 360
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sw = size * 0.09f
        val oval = RectF(sw / 2, sw / 2, size - sw / 2, size - sw / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        p.color = Color.argb(48, 128, 110, 140)
        c.drawArc(oval, 0f, 360f, false, p)
        val sweep = 360f * min(1f, fraction)
        if (sweep > 0.5f) {
            p.shader = SweepGradient(
                size / 2f, size / 2f,
                intArrayOf(Color.parseColor("#FFB347"), Color.parseColor("#FF6B5E"), Color.parseColor("#D63384"), Color.parseColor("#FFB347")),
                floatArrayOf(0f, 0.5f, 0.95f, 1f),
            )
            c.save()
            c.rotate(-90f, size / 2f, size / 2f)
            c.drawArc(oval, 0f, sweep, false, p)
            c.restore()
        }
        return bmp
    }

    companion object {
        const val PREFS = "wb_widget"
    }
}

class StepsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepsWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    StepsWidget().updateAll(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.walkbuddy.widget.REFRESH"
    }
}
