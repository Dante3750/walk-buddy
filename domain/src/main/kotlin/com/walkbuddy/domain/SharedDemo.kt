package com.walkbuddy.domain

import kotlin.math.sin

/** Believable sample "Walks together" for demo mode. Made with the real recorder, so the detail screen and the replay behave as they do for real walks. */
object SharedDemo {
    private const val DAY_MS = 86_400_000L

    fun build(nowMs: Long): List<SharedWalkRecord> = listOf(
        partner(nowMs - 2 * DAY_MS - 3_600_000L, -1, "Sam", 52, 7),
        group(nowMs - 9 * DAY_MS, -2),
        partner(nowMs - 20 * DAY_MS, -3, "Sam", 31, 3),
    )

    private fun partner(startMs: Long, id: Long, name: String, minutes: Int, seed: Int): SharedWalkRecord {
        val rec = SharedWalkRecorder(startMs, SharedMode.Partner)
        val me = AvatarCode.encode(Avatar(AvatarStyle.Boy, 5))
        val her = AvatarCode.encode(Avatar(AvatarStyle.Girl, 8))
        var together = 0
        var total = 0
        var longest = 0L
        var streak = 0L
        for (s in 0..minutes * 60 step 15) {
            val meX = s * 1.28
            val gap = 6.0 + 14.0 * (sin(s / 140.0 + seed) * 0.5 + 0.5) + (if (s in 900..1200) 70.0 else 0.0)
            val herX = meX + (if (sin(s / 300.0 + seed) > 0) gap else -gap)
            rec.onFrame(
                startMs + s * 1000L,
                listOf(
                    RecLane("me", "You", me, meX, (s * 1.8).toInt(), meX, true),
                    RecLane("partner", name, her, herX, (s * 1.75).toInt(), herX, false),
                ),
                gap,
            )
            total++
            if (gap <= TrackLayout.TOGETHER_M) { together++; streak += 15_000; if (streak > longest) longest = streak } else streak = 0
        }
        val end = startMs + minutes * 60_000L
        return rec.build(end, name, 2, if (total == 0) 0 else together * 100 / total, longest, null, null, id)
    }

    private fun group(startMs: Long, id: Long): SharedWalkRecord {
        val rec = SharedWalkRecorder(startMs, SharedMode.Group)
        val names = listOf("You", "Asha", "Ben", "Chitra", "Dev", "Esha")
        val minutes = 74
        for (s in 0..minutes * 60 step 20) {
            val lead = s * 1.3
            val lanes = names.mapIndexed { i, n ->
                val x = lead - i * 6.0 + sin(s / 90.0 + i) * 8.0 - (if (i == 4 && s in 1200..2400) (s - 1200) * 0.06 else 0.0)
                RecLane(if (i == 0) "me" else "g$i", n, AvatarCode.encode(Avatar(AvatarStyle.fromCode(i % 3), (i * 2) % AvatarPalette.SIZE)), x, (s * 1.7).toInt(), if (i == 0) x else null, i == 0)
            }
            val xs = lanes.mapNotNull { it.xM }
            rec.onFrame(startMs + s * 1000L, lanes, xs.max() - xs.min())
        }
        return rec.build(startMs + minutes * 60_000L, "Saturday park loop", 6, 71, 14 * 60_000L, null, null, id)
    }
}
