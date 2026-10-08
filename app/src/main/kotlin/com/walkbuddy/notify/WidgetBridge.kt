package com.walkbuddy.notify

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The home-screen widget lives in its own module (see :widget) so the main app does not depend on it at compile time.
 * The app only writes today's numbers to a tiny SharedPreferences file and pings the widget's receiver by name.
 * If the widget module is not part of the build, the ping simply goes nowhere.
 */
object WidgetBridge {
    const val PREFS = "wb_widget"
    const val ACTION_REFRESH = "com.walkbuddy.widget.REFRESH"
    private const val RECEIVER = "com.walkbuddy.widget.StepsWidgetReceiver"

    @Volatile private var lastSteps = -1
    @Volatile private var lastGoal = -1
    @Volatile private var lastAt = 0L

    fun publish(context: Context, steps: Int, goal: Int, name: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val similar = lastGoal == goal && kotlin.math.abs(steps - lastSteps) < 25 && now - lastAt < 60_000
        if (!force && similar) return
        lastSteps = steps; lastGoal = goal; lastAt = now
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("steps", steps).putInt("goal", goal).putString("name", name).putLong("day", System.currentTimeMillis() / 86_400_000L).apply()
        runCatching {
            val cn = ComponentName(app.packageName, RECEIVER)
            val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(cn)
            if (ids.isNotEmpty()) app.sendBroadcast(Intent(ACTION_REFRESH).setComponent(cn))
        }
    }
}
