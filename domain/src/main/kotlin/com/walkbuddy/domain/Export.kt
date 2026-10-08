package com.walkbuddy.domain

/** CSV export of the user's own data (RFC 4180 quoting, with spreadsheet formula-injection guard on text cells). */
object CsvExport {
    fun cell(raw: String): String {
        var s = raw
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun days(days: List<DayRecord>): String = buildString {
        append("epoch_day,raw_steps,verified_steps,moderate_min,vigorous_min,distance_m,rest_day\r\n")
        for (d in days.sortedBy { it.epochDay }) {
            append("${d.epochDay},${d.rawSteps},${d.verifiedSteps},${d.moderateMin},${d.vigorousMin},${Math.round(d.distanceM)},${d.restDay}\r\n")
        }
    }

    fun walks(walks: List<WalkRecord>): String = buildString {
        append("id,start_ms,duration_s,distance_m,verified_steps,raw_steps,moderate_min,vigorous_min,buddies,together_pct,longest_together_s\r\n")
        for (w in walks.sortedBy { it.startMs }) {
            append("${w.id},${w.startMs},${w.durationMs / 1000},${Math.round(w.distanceM)},${w.verifiedSteps},${w.rawSteps},${w.moderateMin},${w.vigorousMin},${w.buddyCount},${w.togetherPct ?: ""},${w.longestTogetherMs / 1000}\r\n")
        }
    }

    fun spots(spots: List<FavoriteSpot>): String = buildString {
        append("name,lat,lon\r\n")
        for (s in spots) append("${cell(s.name)},${s.lat},${s.lon}\r\n")
    }

    fun moods(moods: List<MoodEntry>): String = buildString {
        append("epoch_day,at_ms,mood,note\r\n")
        for (m in moods.sortedBy { it.atMs }) append("${m.epochDay},${m.atMs},${m.mood},${cell(m.note)}\r\n")
    }
}
