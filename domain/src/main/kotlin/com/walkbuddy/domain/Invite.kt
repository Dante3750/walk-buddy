package com.walkbuddy.domain

import kotlin.random.Random

/** What a scanned QR code, a tapped link or a pasted line turned out to be. */
sealed class Invite {
    abstract val code: String
    /** Always null: the server is built in ([ServerConfig]); kept so older call sites keep compiling. */
    abstract val serverUrl: String?

    /** A private two-person (partner) walk, `walkbuddy://join/CODE`. */
    data class Partner(override val code: String, override val serverUrl: String?) : Invite()

    /** An open group walk, `walkbuddy://group/CODE` or `https://host/g/CODE`. */
    data class Group(override val code: String, override val serverUrl: String?) : Invite()
}

/**
 * Open-group invite links.
 *
 * - `walkbuddy://group/K7M2QX` (the server is built in; a `?s=` parameter is ignored)
 * - `https://<built-in host>/g/K7M2QX` (the server's landing page; links on any other host are rejected)
 *
 * The compact form keeps the QR code small: a payload stays far below [QrEncoder.MAX_BYTES].
 */
object GroupLink {
    const val SCHEME = "walkbuddy"
    private const val PREFIX = "$SCHEME://group/"

    /** The compact link: the server is built in, so it is never part of the payload (smaller QR). */
    fun build(code: String): String = PREFIX + code

    /** The https page a chat app can linkify (served by the Walk Buddy server). */
    fun webLink(code: String): String = "${ServerConfig.WEB_BASE}/g/$code"

    /** Link text fits a QR code produced by [QrEncoder]. */
    fun fitsQr(link: String): Boolean = link.toByteArray(Charsets.UTF_8).size <= QrEncoder.MAX_BYTES

    /** Parses a group invite link. Does not accept a bare code (a bare code could be either mode). */
    fun parse(text: String?): Invite.Group? {
        val t = text?.trim() ?: return null
        if (t.startsWith(PREFIX, ignoreCase = true)) {
            val rest = t.substring(PREFIX.length)
            val code = SessionCode.normalize(rest.substringBefore('?').trimEnd('/')) ?: return null
            return Invite.Group(code, null)
        }
        // The server's own landing page link. Any other host is rejected: an invite can't point the app elsewhere.
        val web = Regex("^https://([A-Za-z0-9.-]{1,100})/g/([A-Za-z0-9]{6})/?(?:\\?.*)?$", RegexOption.IGNORE_CASE).matchEntire(t) ?: return null
        if (!web.groupValues[1].equals(ServerConfig.HOST, ignoreCase = true)) return null
        val code = SessionCode.normalize(web.groupValues[2]) ?: return null
        return Invite.Group(code, null)
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
