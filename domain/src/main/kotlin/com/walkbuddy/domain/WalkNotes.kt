package com.walkbuddy.domain

/** Notes and photos on a saved walk (alpha 2.0). Kept on the phone, in app-private storage, and deleted with the walk. */
object WalkNotes {
    const val MAX_NOTE = 400
    const val MAX_PHOTO_PX = 1600
    const val MAX_PHOTO_BYTES = 1_500_000L

    /** Trims, drops control characters, collapses blank lines and caps the length. Empty becomes null. */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        val s = raw.replace("\r", "").filter { it == '\n' || it >= ' ' }.replace(Regex("\n{3,}"), "\n\n").trim().take(MAX_NOTE).trim()
        return s.ifEmpty { null }
    }

    /** Power-of-two sample size so the longer side ends up at most [MAX_PHOTO_PX]. */
    fun sampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var s = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_PHOTO_PX) { longest /= 2; s *= 2 }
        return s
    }

    /** Size after the final scale-down, keeping the aspect ratio. */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val longest = maxOf(width, height)
        if (longest <= MAX_PHOTO_PX) return width to height
        val f = MAX_PHOTO_PX.toDouble() / longest
        return maxOf(1, Math.round(width * f).toInt()) to maxOf(1, Math.round(height * f).toInt())
    }

    /** JPEG quality to try next so the file ends up under the byte limit. */
    fun nextQuality(current: Int, bytes: Long): Int? = if (bytes <= MAX_PHOTO_BYTES || current <= 45) null else (current - 12).coerceAtLeast(45)

    fun photoFileName(walkId: Long): String = "walk-$walkId.jpg"
}
