package com.walkbuddy.steps

import com.walkbuddy.AppContainer
import com.walkbuddy.data.Clock
import com.walkbuddy.data.Goals
import com.walkbuddy.notify.WidgetBridge

/** Keeps the home-screen widget showing the stored step total, whoever just updated it. */
object StepWidgetSync {
    suspend fun publish(c: AppContainer) {
        val s = c.settings.current()
        if (s.demoMode) return
        val history = c.repository.allDays()
        val day = Clock.epochDay()
        val steps = history.firstOrNull { it.epochDay == day }?.verifiedSteps ?: 0
        WidgetBridge.publish(c.appContext, steps, Goals.goalFor(day, history, s), s.displayName)
    }
}
