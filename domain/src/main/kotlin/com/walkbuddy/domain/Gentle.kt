package com.walkbuddy.domain

/** "Gentle day": a lighter goal for a tired day. It never ends a streak and never lowers the adaptive goal for other days. */
object GentleDay {
    const val FACTOR = 0.6
    const val MIN_GOAL = 1500
    const val COPY = "Gentle day: a lighter goal for a tired day. Your streak stays safe."

    fun goal(baseGoal: Int): Int {
        val g = (Math.round(baseGoal * FACTOR / 100.0) * 100).toInt()
        return g.coerceAtLeast(MIN_GOAL).coerceAtMost(baseGoal)
    }

    fun effectiveGoal(baseGoal: Int, gentle: Boolean): Int = if (gentle) goal(baseGoal) else baseGoal
}

/** Keeps goals honest: gentle days are excluded from the adaptive median so a tired day never drags the goal down. */
object GentleHistory {
    fun forGoalPlanning(history: List<DayRecord>): List<DayRecord> = history.filter { !it.gentle }
}
