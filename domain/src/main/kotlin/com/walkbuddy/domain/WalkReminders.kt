package com.walkbuddy.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Walk reminders at times the person chooses (alpha 2.0). Pure scheduling maths: which slot fires next, what "can't today", "snooze" and
 * "skip" do, and how slots are stored as text. Android only turns the answer into one inexact WorkManager job, so there are no alarms and
 * no wake-ups beyond that single job.
 */
data class ReminderSlot(
    val id: Int,
    /** ISO weekdays, 1 = Monday ... 7 = Sunday. */
    val weekdays: Set<Int>,
    val minuteOfDay: Int,
    val enabled: Boolean = true,
) {
    val hour: Int get() = minuteOfDay / 60
    val minute: Int get() = minuteOfDay % 60
}

data class NextReminder(val slotId: Int, val atMs: Long, val epochDay: Long)

object WalkReminders {
    const val MAX_SLOTS = 5
    const val SNOOZE_MIN = 30
    const val SNOOZE_MS = SNOOZE_MIN * 60_000L

    /** A reminder that arrives later than this after its time (Doze can delay inexact work) is dropped instead of nagging. */
    const val STALE_AFTER_MS = 3 * 3_600_000L

    val everyDay: Set<Int> = (1..7).toSet()

    fun encode(slots: List<ReminderSlot>): String =
        slots.take(MAX_SLOTS).joinToString(";") { "${it.id}:${it.weekdays.sorted().joinToString("")}:${it.minuteOfDay}:${if (it.enabled) 1 else 0}" }

    fun decode(text: String?): List<ReminderSlot> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(';').mapNotNull { part ->
            val f = part.split(':')
            if (f.size != 4) return@mapNotNull null
            val id = f[0].toIntOrNull() ?: return@mapNotNull null
            val days = f[1].mapNotNull { it.digitToIntOrNull() }.filter { it in 1..7 }.toSet()
            val minute = f[2].toIntOrNull()?.takeIf { it in 0..1439 } ?: return@mapNotNull null
            if (days.isEmpty()) return@mapNotNull null
            ReminderSlot(id, days, minute, f[3] == "1")
        }.distinctBy { it.id }.take(MAX_SLOTS)
    }

    fun nextId(slots: List<ReminderSlot>): Int = (slots.maxOfOrNull { it.id } ?: 0) + 1

    /**
     * The next time any enabled slot is due strictly after [afterMs]. Days in [skipDays] (epoch days the person said they cannot walk) and
     * [skipOnce] (one exact occurrence) are passed over. Looks a fortnight ahead, which is enough for any weekly pattern.
     */
    fun next(
        slots: List<ReminderSlot>, afterMs: Long, zone: ZoneId, skipDays: Set<Long> = emptySet(), skipOnce: Set<Pair<Int, Long>> = emptySet(),
    ): NextReminder? {
        val active = slots.filter { it.enabled && it.weekdays.isNotEmpty() }
        if (active.isEmpty()) return null
        val startDay = Instant.ofEpochMilli(afterMs).atZone(zone).toLocalDate()
        var best: NextReminder? = null
        for (offset in 0..14) {
            val day: LocalDate = startDay.plusDays(offset.toLong())
            val epochDay = day.toEpochDay()
            if (epochDay in skipDays) continue
            for (s in active) {
                if (day.dayOfWeek.value !in s.weekdays) continue
                if ((s.id to epochDay) in skipOnce) continue
                // ZonedDateTime.of resolves a time that does not exist (spring-forward) to the next valid instant.
                val at = ZonedDateTime.of(day, java.time.LocalTime.of(s.hour, s.minute), zone).toInstant().toEpochMilli()
                if (at <= afterMs) continue
                if (best == null || at < best.atMs) best = NextReminder(s.id, at, epochDay)
            }
            if (best != null) return best
        }
        return best
    }

    fun snoozeAt(nowMs: Long): Long = nowMs + SNOOZE_MS

    fun isStale(dueMs: Long, nowMs: Long): Boolean = nowMs - dueMs > STALE_AFTER_MS

    /** Whether to show a reminder that is due: not stale, not on a "can't today" day and not during quiet hours. */
    fun shouldShow(dueMs: Long, nowMs: Long, zone: ZoneId, skipDays: Set<Long>, quiet: QuietHours): Boolean {
        if (isStale(dueMs, nowMs) || nowMs < dueMs - 60_000L) return false
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        if (now.toLocalDate().toEpochDay() in skipDays) return false
        return !quiet.isQuiet(now.hour)
    }

    /** Old "can't today" days are forgotten so the list never grows. */
    fun pruneSkipDays(skipDays: Set<Long>, today: Long): Set<Long> = skipDays.filter { it >= today }.toSet()

    fun encodeDays(days: Set<Long>): String = days.sorted().joinToString(",")
    fun decodeDays(text: String?): Set<Long> = text.orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
}

enum class ReminderTone { Solo, Partner }

object ReminderTexts {
    /** Partner wording only when the person has a partner name on file and asked for it. */
    fun tone(partnerVariant: Boolean, partnerName: String): ReminderTone =
        if (partnerVariant && partnerName.isNotBlank()) ReminderTone.Partner else ReminderTone.Solo
}
