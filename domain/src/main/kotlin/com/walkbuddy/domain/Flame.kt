package com.walkbuddy.domain

enum class FlameLevel { Spark, Ember, Flame, Blaze, Inferno }

data class FlameInfo(
    val level: FlameLevel,
    val days: Int,
    val tokens: Int,
    val atRisk: Boolean,
    val title: String,
    val subtitle: String,
)

/** Turns the streak result and rest tokens into something to feel good about, never to be anxious about. */
object StreakFlame {
    fun levelFor(days: Int): FlameLevel = when {
        days <= 0 -> FlameLevel.Spark
        days < 3 -> FlameLevel.Ember
        days < 7 -> FlameLevel.Flame
        days < 30 -> FlameLevel.Blaze
        else -> FlameLevel.Inferno
    }

    fun info(result: StreakResult, metToday: Boolean, restToday: Boolean, hourOfDay: Int): FlameInfo {
        val days = result.current
        val level = levelFor(days)
        val atRisk = days > 0 && !metToday && !restToday && hourOfDay >= 18
        val title = when {
            days <= 0 -> "Light your first flame"
            days == 1 -> "1 day streak"
            else -> "$days day streak"
        }
        val tokenText = when (result.tokens) {
            0 -> "No rest tokens right now"
            1 -> "1 rest token ready"
            else -> "${result.tokens} rest tokens ready"
        }
        val subtitle = when {
            restToday -> "Rest day. Your flame is safe."
            atRisk && result.tokens > 0 -> "A short walk keeps it glowing, and a rest token has your back."
            atRisk -> "A short walk tonight keeps it glowing."
            days <= 0 -> "Reach your goal today to begin."
            else -> tokenText
        }
        return FlameInfo(level, days, result.tokens, atRisk, title, subtitle)
    }
}
