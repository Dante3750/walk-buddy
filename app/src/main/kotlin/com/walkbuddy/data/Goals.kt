package com.walkbuddy.data

import com.walkbuddy.domain.AdaptiveGoal
import com.walkbuddy.domain.DayRecord
import com.walkbuddy.domain.GentleDay
import com.walkbuddy.domain.GentleHistory

/** The single place that decides "what is the goal on day X", used by the app, the widget and the background sampler. */
object Goals {
    fun plan(day: Long, history: List<DayRecord>, s: Settings): AdaptiveGoal.GoalPlan =
        AdaptiveGoal.planFor(day, GentleHistory.forGoalPlanning(history), s.restWeekdays, if (s.goalMode == GoalMode.Fixed) s.fixedGoal else null)

    /** The goal for [day], lowered on a gentle day. */
    fun goalFor(day: Long, history: List<DayRecord>, s: Settings): Int {
        val rec = history.firstOrNull { it.epochDay == day }
        return GentleDay.effectiveGoal(plan(day, history, s).goal, rec?.gentle == true)
    }
}
