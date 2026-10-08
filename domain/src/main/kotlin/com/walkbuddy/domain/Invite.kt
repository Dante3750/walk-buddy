package com.walkbuddy.domain

import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random

/** What a scanned QR code, a tapped link or a pasted line turned out to be. */
sealed class Invite {
    abstract val code: String
    abstract val serverUrl: String?

    /** A private two-person (partner) walk, `walkbuddy://join/CODE`. */
    data class Partner(override val code: String, override val serverUrl: String?) : Invite()

    /** An open group walk, `walkbuddy://group/CODE` or `https://host/g/CODE`. */
    data class Group(override val code: String, override val serverUrl: String?) : Invite()
}

/**
 * Open-group invite links.
 *
 * - `walkbuddy://group/K7M2QX` (server from the app's settings)
 * - `walkbuddy://group/K7M2QX?s=walk.example.org` (bare host means `wss://host`)
 * - `walkbuddy://group/K7M2QX?s=ws://192.168.1.5:8080` (explicit scheme, for a LAN test server)
 * - `https://walk.example.org/g/K7M2QX` (the server's landing page; the host becomes `wss://host`)
 *
 * The compact form keeps the QR code small: a payload stays far below [QrEncoder.MAX_BYTES].
 */
object GroupLink {
    const val SCHEME = "walkbuddy"
    private const val PREFIX = "$SCHEME://group/"
    private val HOST_RE = Regex("^[A-Za-z0-9.-]{1,100}(:\\d{1,5})?$")

    fun build(code: String, serverUrl: String? = null): String {
        val base = PREFIX + code
        val s = serverUrl?.trim().orEmpty()
        return when {
            s.isEmpty() -> base
            s.startsWith("wss://") && HOST_RE.matches(s.removePrefix("wss://").trimEnd('/')) -> base + "?s=" + s.removePrefix("wss://").trimEnd('/')
            s.startsWith("ws://") && HOST_RE.matches(s.removePrefix("ws://").trimEnd('/')) -> base + "?s=" + s.trimEnd('/')
            else -> base + "?s=" + URLEncoder.encode(s, "UTF-8")
        }
    }

    /** The https page a chat app can linkify (served by the Walk Buddy server). */
    fun webLink(code: String, serverUrl: String): String? {
        val s = serverUrl.trim().trimEnd('/')
        val (scheme, host) = when {
            s.startsWith("wss://") -> "https" to s.removePrefix("wss://")
            s.startsWith("ws://") -> "http" to s.removePrefix("ws://")
            else -> return null
        }
        if (!HOST_RE.matches(host)) return null
        return "$scheme://$host/g/$code"
    }

    /** Link text fits a QR code produced by [QrEncoder]. */
    fun fitsQr(link: String): Boolean = link.toByteArray(Charsets.UTF_8).size <= QrEncoder.MAX_BYTES

    /** Parses a group invite link. Does not accept a bare code (a bare code could be either mode). */
    fun parse(text: String?): Invite.Group? {
        val t = text?.trim() ?: return null
        if (t.startsWith(PREFIX, ignoreCase = true)) {
            val rest = t.substring(PREFIX.length)
            val code = SessionCode.normalize(rest.substringBefore('?').trimEnd('/')) ?: return null
            return Invite.Group(code, serverParam(rest.substringAfter('?', "")))
        }
        val web = Regex("^(https?)://([A-Za-z0-9.-]{1,100}(?::\\d{1,5})?)/g/([A-Za-z0-9]{6})/?(?:\\?.*)?$", RegexOption.IGNORE_CASE).matchEntire(t) ?: return null
        val code = SessionCode.normalize(web.groupValues[3]) ?: return null
        val ws = if (web.groupValues[1].equals("https", true)) "wss://" else "ws://"
        return Invite.Group(code, ws + web.groupValues[2])
    }

    private fun serverParam(query: String): String? {
        for (kv in query.split('&')) {
            if (!kv.startsWith("s=")) continue
            val v = runCatching { URLDecoder.decode(kv.substring(2), "UTF-8") }.getOrNull()?.trim() ?: return null
            if (v.length > 200 || v.isEmpty()) return null
            if (v.startsWith("wss://") || v.startsWith("ws://")) return v.trimEnd('/')
            return if (HOST_RE.matches(v)) "wss://$v" else null
        }
        return null
    }
}

object Invites {
    /**
     * Understands a partner link, a group link or a web invite. A bare 6-character code is ambiguous, so it is
     * returned as null here; the screen that asked for a code knows which mode it is in (see [bareCode]).
     */
    fun parse(text: String?): Invite? {
        GroupLink.parse(text)?.let { return it }
        val t = text?.trim() ?: return null
        if (t.startsWith("walkbuddy://join/", ignoreCase = true)) {
            return JoinLink.parse(t)?.let { Invite.Partner(it.code, it.serverUrl) }
        }
        return null
    }

    fun bareCode(text: String?): String? = SessionCode.normalize(text)
}

/** Per-phone secret that lets a reconnecting phone reclaim its own slot in a group (never shown, never saved to disk). */
object GroupKey {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun generate(random: Random = Random.Default, length: Int = 24): String =
        buildString { repeat(length.coerceIn(8, 64)) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}
