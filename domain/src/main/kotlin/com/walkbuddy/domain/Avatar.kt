package com.walkbuddy.domain

/**
 * The little walker drawn on the Track and in lists. Three simple, original styles (no characters from anywhere):
 * a boy (short hair), a girl (ponytail and a dress) and a round neutral pawn. The style and a colour are the member's own choice
 * and travel as one small number ([AvatarCode]) next to the nickname.
 */
enum class AvatarStyle(val code: Int, val label: String) {
    Round(0, "Round"), Boy(1, "Boy"), Girl(2, "Girl");

    companion object {
        fun fromCode(c: Int): AvatarStyle = values().firstOrNull { it.code == c } ?: Round
    }
}

data class Avatar(val style: AvatarStyle, val colorIndex: Int) {
    init { require(colorIndex in 0 until AvatarPalette.SIZE) { "colour index out of range" } }

    companion object {
        val Default = Avatar(AvatarStyle.Round, 0)
    }
}

/** Twelve colours that stay readable on light and dark surfaces. The actual colours live in the UI; the domain only knows the count. */
object AvatarPalette {
    const val SIZE = 12
}

object AvatarCode {
    const val MAX = 255

    /** bits 0-1 style, bits 2-5 colour index. Always in 0..63. */
    fun encode(a: Avatar): Int = a.style.code or (a.colorIndex shl 2)

    /** Null for "not chosen" or anything out of range. A colour index past the palette wraps, an unknown style becomes round. */
    fun decode(code: Int?): Avatar? {
        if (code == null || code !in 0..MAX) return null
        return Avatar(AvatarStyle.fromCode(code and 3), ((code shr 2) and 15) % AvatarPalette.SIZE)
    }

    /** A steady look for someone who did not choose one (an older app version): round, with a colour from their id. */
    fun fallback(id: String): Avatar = Avatar(AvatarStyle.Round, (id.hashCode() and 0x7fffffff) % AvatarPalette.SIZE)

    fun of(code: Int?, id: String): Avatar = decode(code) ?: fallback(id)
}
