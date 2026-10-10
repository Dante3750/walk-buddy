package com.walkbuddy.domain

/**
 * Support diagnostics (alpha 2.0). The app keeps a small ring buffer of its own events on the phone so a person can send a text file to
 * whoever is helping. It is redacted when a line is written, capped in size, and never uploaded: it leaves the phone only through the
 * system share sheet when the person taps Export. No coordinates, codes, ids, links, addresses or names are ever kept.
 */
object LogRedactor {
    private val url = Regex("""(?i)\b(?:https?|wss?|walkbuddy)://\S+""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val ip = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
    private val uuid = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val peerId = Regex("""\bp[0-9a-f]{12}\b""")
    private val longHex = Regex("""\b[0-9a-fA-F]{16,}\b""")
    private val keyLike = Regex("""\b[A-Za-z0-9_-]{22,}\b""")
    private val coord = Regex("""[-+]?\d{1,3}\.\d{3,}""")
    private val labelled = Regex("""(?i)\b(code|key|room|group|peer|id|name|nick|nickname|token)\s*[=:]\s*\S+""")
    private val sessionCode = Regex("""\b[2-9A-HJ-NP-Z]{6}\b""")

    fun redact(line: String): String {
        var s = line
        s = url.replace(s, "<link>")
        s = email.replace(s, "<email>")
        s = uuid.replace(s, "<id>")
        s = ip.replace(s, "<ip>")
        s = labelled.replace(s) { m -> m.groupValues[1] + "=<redacted>" }
        s = peerId.replace(s, "<id>")
        s = longHex.replace(s, "<id>")
        s = keyLike.replace(s, "<key>")
        s = coord.replace(s, "<num>")
        // Group and partner codes: six characters from the code alphabet that contain a digit or are all capitals.
        s = sessionCode.replace(s) { m -> if (m.value.any { it.isDigit() } || m.value.all { it.isUpperCase() }) "<code>" else m.value }
        return s.filter { it == '\t' || it >= ' ' }
    }
}

enum class LogLevel(val letter: Char) { Debug('D'), Info('I'), Warn('W'), Error('E') }

/** A bounded, thread-safe, in-memory log. [maxLines] and [maxBytes] both apply; the oldest lines go first. */
class RingLog(private val maxLines: Int = 400, private val maxBytes: Int = 48_000, private val maxLineLen: Int = 200) {
    private val lines = ArrayDeque<String>()
    private var bytes = 0

    @Synchronized
    fun add(tMs: Long, level: LogLevel, tag: String, message: String) {
        val clean = LogRedactor.redact(message).replace('\n', ' ').take(maxLineLen)
        val t = LogRedactor.redact(tag).take(16)
        val line = "${stamp(tMs)} ${level.letter}/$t: $clean"
        lines.addLast(line)
        bytes += line.length + 1
        while (lines.size > maxLines || bytes > maxBytes) {
            if (lines.isEmpty()) break
            bytes -= lines.removeFirst().length + 1
        }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() { lines.clear(); bytes = 0 }

    @Synchronized
    fun size(): Int = lines.size

    /** Restores lines saved by [dump]; they are redacted again, so an old file can never reintroduce something sensitive. */
    @Synchronized
    fun load(text: String?) {
        if (text.isNullOrEmpty()) return
        for (raw in text.lineSequence()) {
            if (raw.isBlank()) continue
            val clean = LogRedactor.redact(raw).take(maxLineLen + 40)
            lines.addLast(clean)
            bytes += clean.length + 1
        }
        while (lines.size > maxLines || bytes > maxBytes) {
            if (lines.isEmpty()) break
            bytes -= lines.removeFirst().length + 1
        }
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    /** The text file people share: a header with app facts (supplied by the caller, redacted here too) and the log. */
    @Synchronized
    fun export(header: List<String>): String = buildString {
        append("Walk Buddy diagnostics\n")
        append("Stays on your phone unless you share it. Codes, places, links, ids and names are removed.\n")
        for (h in header) append(LogRedactor.redact(h)).append('\n')
        append("---\n")
        for (l in lines) append(l).append('\n')
    }

    private fun stamp(tMs: Long): String {
        val s = tMs / 1000
        val h = (s / 3600) % 24
        val m = (s / 60) % 60
        val sec = s % 60
        return String.format(java.util.Locale.US, "%d.%02d:%02d:%02d", tMs / 86_400_000L, h, m, sec)
    }
}
