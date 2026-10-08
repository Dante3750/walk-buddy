package com.walkbuddy.domain

import kotlin.math.abs

/** A buddy shown as a small avatar on the progress ring, at their own daily progress. */
data class RingBuddy(val id: String, val name: String, val steps: Int, val goal: Int) {
    val fraction: Double get() = Hero.fraction(steps, goal)
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"
}

/** Small, testable decisions behind the big step-count hero. */
object Hero {
    /** Within this many steps counts as neck and neck. */
    const val NECK_AND_NECK = 100

    fun fraction(steps: Int, goal: Int): Double = if (goal <= 0) 0.0 else (steps.toDouble() / goal).coerceIn(0.0, 1.0)

    fun goalReached(steps: Int, goal: Int) = goal > 0 && steps >= goal

    fun thousands(n: Int): String {
        val neg = n < 0
        val digits = abs(n.toLong()).toString()
        val out = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return (if (neg) "-" else "") + out
    }

    fun toGoText(steps: Int, goal: Int): String = when {
        goal <= 0 -> "Set a goal in Settings"
        steps >= goal && steps - goal < 50 -> "Goal reached"
        steps >= goal -> "${thousands(steps - goal)} past your goal"
        else -> "${thousands(goal - steps)} to go"
    }

    /** "+1,240" when the buddy is ahead of me, "-500" when behind. */
    fun delta(mySteps: Int, buddySteps: Int): String {
        val d = buddySteps - mySteps
        return when {
            abs(d) < NECK_AND_NECK -> "="
            d > 0 -> "+" + thousands(d)
            else -> "-" + thousands(-d)
        }
    }

    fun leadText(buddyName: String, mySteps: Int, buddySteps: Int): String {
        val d = mySteps - buddySteps
        return when {
            abs(d) < NECK_AND_NECK -> "Neck and neck with $buddyName"
            d > 0 -> "You are ${thousands(d)} steps ahead of $buddyName"
            else -> "$buddyName is ${thousands(-d)} steps ahead"
        }
    }

    /** Recorded distance (from walks) can lag behind idle steps, so never show less than the step-length estimate. */
    fun distanceM(verifiedSteps: Int, recordedM: Double, stepLengthM: Double): Double =
        maxOf(recordedM.coerceAtLeast(0.0), verifiedSteps.coerceAtLeast(0) * stepLengthM)

    /** Recorded active minutes if there are any, otherwise a rough estimate (about 110 steps a minute while walking). */
    fun activeMinutes(verifiedSteps: Int, recordedMin: Int): Int =
        if (recordedMin > 0) recordedMin else (verifiedSteps.coerceAtLeast(0) / 110)

    fun activeIsEstimate(recordedMin: Int) = recordedMin <= 0

    /** Font size that keeps the number inside the ring: shrinks for long numbers, within sane bounds. */
    fun numberSizeSp(text: String, innerWidthDp: Float, minSp: Float = 56f, maxSp: Float = 116f): Float {
        // Tabular figures are ~0.6 em wide, a comma ~0.3 em.
        val em = text.sumOf { if (it == ',' || it == '.') 0.3 else 0.6 }.toFloat().coerceAtLeast(0.6f)
        return (innerWidthDp / em).coerceIn(minSp, maxSp)
    }

}
