package com.walkbuddy.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/** Warm, preset quick reactions sent over the data channel during a walk. Fixed list: no free text travels. */
enum class Reaction(val id: String, val label: String, val emoji: String) {
    Love("love", "Love this", "❤️"),
    Beautiful("beautiful", "Beautiful out here", "🌅"),
    Proud("proud", "Proud of you", "🌟"),
    Chai("chai", "Chai after?", "☕"),
    Wait("wait", "Wait for me", "🐢"),
    Breather("breather", "Need a breather", "🌿");

    companion object {
        fun fromId(id: String?): Reaction? = values().firstOrNull { it.id == id }
    }
}

object Reactions {
    fun senderLimiter() = PingLimiter(minGapMs = 4_000, maxPerHour = 40)
    fun receiverLimiter() = PingLimiter(minGapMs = 2_000, maxPerHour = 80)
}

/** Quiet hours silence reminders and nudges. The window may wrap past midnight (22 to 7). */
data class QuietHours(val enabled: Boolean = false, val fromHour: Int = 22, val toHour: Int = 7) {
    fun isQuiet(hour: Int): Boolean {
        if (!enabled || fromHour == toHour) return false
        val h = ((hour % 24) + 24) % 24
        return if (fromHour > toHour) (h >= fromHour || h < toHour) else (h in fromHour until toHour)
    }
}

data class CountdownInfo(val daysUntil: Int, val date: LocalDate, val yearsTogether: Int?, val text: String)

/** Anniversary or any date to look forward to. Yearly when it is in the past, one-off when it is still ahead. */
object AnniversaryCountdown {
    fun parse(iso: String?): LocalDate? = runCatching { LocalDate.parse(iso?.trim()) }.getOrNull()

    private fun inYear(d: LocalDate, year: Int): LocalDate =
        if (d.monthValue == 2 && d.dayOfMonth == 29 && !java.time.Year.isLeap(year.toLong())) LocalDate.of(year, 2, 28)
        else LocalDate.of(year, d.monthValue, d.dayOfMonth)

    fun info(date: LocalDate, label: String, today: LocalDate): CountdownInfo {
        val name = label.ifBlank { "your day" }
        if (date.isAfter(today)) {
            val n = ChronoUnit.DAYS.between(today, date).toInt()
            return CountdownInfo(n, date, null, phrase(n, name, null))
        }
        var next = inYear(date, today.year)
        if (next.isBefore(today)) next = inYear(date, today.year + 1)
        val n = ChronoUnit.DAYS.between(today, next).toInt()
        val years = next.year - date.year
        return CountdownInfo(n, next, years.takeIf { it > 0 }, phrase(n, name, years.takeIf { it > 0 }))
    }

    private fun phrase(n: Int, name: String, years: Int?): String {
        val y = years?.let { " ($it " + (if (it == 1) "year" else "years") + ")" } ?: ""
        return when (n) {
            0 -> "Today is $name$y"
            1 -> "Tomorrow is $name$y"
            else -> "$n days until $name$y"
        }
    }

    /** "Day 412 together", or null when the date is still ahead. */
    fun daysTogether(date: LocalDate, today: LocalDate): Long? =
        if (date.isAfter(today)) null else ChronoUnit.DAYS.between(date, today) + 1
}

data class Reminder(val key: String, val title: String, val text: String)

object ReminderPlanner {
    const val WALK_DATE_LEAD_MS = 30 * 60_000L
    const val ANNIVERSARY_HOUR = 9

    /** Reminders that should fire now. [alreadySent] keys make every reminder fire once. */
    fun due(
        nowMs: Long,
        zone: ZoneId,
        anniversary: Pair<LocalDate, String>?,
        walkDates: List<WalkDate>,
        alreadySent: Set<String>,
        quiet: QuietHours,
    ): List<Reminder> {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        if (quiet.isQuiet(now.hour)) return emptyList()
        val out = ArrayList<Reminder>()
        if (anniversary != null && now.hour >= ANNIVERSARY_HOUR) {
            val info = AnniversaryCountdown.info(anniversary.first, anniversary.second, now.toLocalDate())
            if (info.daysUntil <= 1) {
                val key = "anniv-${info.date}-${info.daysUntil}"
                if (key !in alreadySent) out += Reminder(key, if (info.daysUntil == 0) "Today" else "Tomorrow", info.text + ". A walk together, maybe?")
            }
        }
        for (d in walkDates) {
            val at = WalkDatePlanner.nextOccurrence(d, nowMs) ?: continue
            if (at - nowMs in 0..WALK_DATE_LEAD_MS) {
                val key = "date-${d.id}-$at"
                if (key !in alreadySent) out += Reminder(key, d.title.ifBlank { "Walk date" }, "Your walk date starts soon.")
            }
        }
        return out
    }
}

object GoalCelebration {
    /** Celebrate once per day, when today's steps first reach the goal. [prevSteps] 0 on first look counts as "before". */
    fun shouldCelebrate(prevSteps: Int, steps: Int, goal: Int, celebratedDay: Long?, today: Long): Boolean =
        goal > 0 && prevSteps < goal && steps >= goal && celebratedDay != today
}

data class ConfettiPiece(
    val x0: Float, val vx: Float, val vy: Float, val rot0: Float, val spin: Float,
    val size: Float, val colorIndex: Int, val delay: Float,
)

data class PiecePos(val x: Float, val y: Float, val rotation: Float, val alpha: Float)

/** Deterministic confetti physics in a unit square, so it can be tested and drawn by any canvas. */
object Confetti {
    const val DURATION_S = 2.8f
    private const val GRAVITY = 1.6f

    fun spawn(count: Int, seed: Long, colors: Int = 5): List<ConfettiPiece> {
        val r = Random(seed)
        return List(count) {
            ConfettiPiece(
                x0 = 0.5f + (r.nextFloat() - 0.5f) * 0.2f,
                vx = (r.nextFloat() - 0.5f) * 1.5f,
                vy = -(0.6f + r.nextFloat() * 1.1f),
                rot0 = r.nextFloat() * 360f,
                spin = (r.nextFloat() - 0.5f) * 720f,
                size = 0.012f + r.nextFloat() * 0.014f,
                colorIndex = r.nextInt(colors),
                delay = r.nextFloat() * 0.25f,
            )
        }
    }

    /** Null before the piece starts or after it has faded out. */
    fun position(p: ConfettiPiece, tSec: Float): PiecePos? {
        val t = tSec - p.delay
        if (t < 0f || t > DURATION_S) return null
        val x = p.x0 + p.vx * t
        val y = 0.4f + p.vy * t + 0.5f * GRAVITY * t * t
        val fadeStart = DURATION_S * 0.7f
        val alpha = if (t < fadeStart) 1f else (1f - (t - fadeStart) / (DURATION_S - fadeStart)).coerceIn(0f, 1f)
        return PiecePos(x, y, p.rot0 + p.spin * t, alpha)
    }
}
