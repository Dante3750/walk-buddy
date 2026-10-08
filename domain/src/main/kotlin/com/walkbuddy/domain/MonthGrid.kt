package com.walkbuddy.domain

import java.time.LocalDate

data class DayCell(
    val epochDay: Long,
    val dayOfMonth: Int,
    val steps: Int,
    /** 0 nothing, 1 under half the goal, 2 under the goal, 3 goal reached, 4 a lot more than the goal. */
    val level: Int,
    val isToday: Boolean,
    val isFuture: Boolean,
)

data class MonthGridModel(
    val year: Int,
    val month: Int,
    /** Empty cells before the 1st, with weeks starting on Monday. */
    val leadingBlanks: Int,
    val cells: List<DayCell>,
    val daysWithSteps: Int,
    val goalDays: Int,
)

object MonthGrid {
    fun level(steps: Int, goal: Int): Int = when {
        steps <= 0 -> 0
        goal <= 0 -> 1
        steps * 2 < goal -> 1
        steps < goal -> 2
        steps * 2 < goal * 3 -> 3
        else -> 4
    }

    fun build(year: Int, month: Int, stepsByDay: Map<Long, Int>, goalFor: (Long) -> Int, todayEpochDay: Long): MonthGridModel {
        val first = LocalDate.of(year, month, 1)
        val n = first.lengthOfMonth()
        val cells = (1..n).map { d ->
            val ed = first.plusDays((d - 1).toLong()).toEpochDay()
            val steps = if (ed > todayEpochDay) 0 else stepsByDay[ed] ?: 0
            DayCell(ed, d, steps, if (ed > todayEpochDay) 0 else level(steps, goalFor(ed)), ed == todayEpochDay, ed > todayEpochDay)
        }
        return MonthGridModel(
            year, month, first.dayOfWeek.value - 1, cells,
            daysWithSteps = cells.count { it.steps > 0 }, goalDays = cells.count { it.level >= 3 },
        )
    }
}
