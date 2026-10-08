package com.walkbuddy.steps

import com.walkbuddy.AppContainer
import com.walkbuddy.data.Clock
import com.walkbuddy.data.Goals
import com.walkbuddy.notify.WidgetBridge

/**
 * Keeps the home-screen widget showing the stored step total, whoever just updated it. Reading the whole history to work out the goal
 * is not free, so refreshes are spaced by the power policy (a minute with the screen on, minutes otherwise, longer in Battery Saver)
 * unless [force]d.
 */
object StepWidgetSync {
    @Volatile private var lastAtMs = 0L

    suspend fun publish(c: AppContainer, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now >= lastAtMs && now - lastAtMs < c.power.plan().widgetMinMs) return
        val s = c.settings.current()
        if (s.demoMode) return
        lastAtMs = now
        val history = c.repository.allDays()
        val day = Clock.epochDay()
        val steps = history.firstOrNull { it.epochDay == day }?.verifiedSteps ?: 0
        WidgetBridge.publish(c.appContext, steps, Goals.goalFor(day, history, s), s.displayName)
    }
}
