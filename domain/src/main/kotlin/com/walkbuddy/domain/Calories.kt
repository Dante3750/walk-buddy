package com.walkbuddy.domain

enum class Sex { Male, Female, Unspecified }

data class BodyProfile(val heightCm: Double? = null, val weightKg: Double? = null, val sex: Sex = Sex.Unspecified)

/** Rejects implausible profile values; when false, the app shows neutral copy instead of numbers. */
object ProfileCheck {
    val HEIGHT_CM = 100.0..230.0
    val WEIGHT_KG = 25.0..250.0

    fun heightOk(h: Double?) = h == null || (h.isFinite() && h in HEIGHT_CM)
    fun weightOk(w: Double?) = w == null || (w.isFinite() && w in WEIGHT_KG)
    fun isPlausible(p: BodyProfile) = heightOk(p.heightCm) && weightOk(p.weightKg)
}

object StepLength {
    const val MIN_M = 0.40
    const val MAX_M = 1.20
    const val DEFAULT_M = 0.70

    /** Common rule of thumb: step length is about 0.415 x height (men), 0.413 (women), 0.414 otherwise. */
    fun fromHeight(heightCm: Double?, sex: Sex): Double? {
        if (heightCm == null || !ProfileCheck.heightOk(heightCm)) return null
        val k = when (sex) { Sex.Male -> 0.415; Sex.Female -> 0.413; Sex.Unspecified -> 0.414 }
        return (heightCm / 100.0 * k).coerceIn(MIN_M, MAX_M)
    }

    /** Calibrate from a GPS walk. Needs enough data to be meaningful; returns null otherwise. */
    fun calibrate(distanceM: Double, steps: Long): Double? {
        if (steps < 200 || distanceM < 120.0) return null
        val l = distanceM / steps
        return if (l in MIN_M..MAX_M) l else null
    }

    fun speedFromCadence(cadenceSpm: Double, stepLengthM: Double): Double = cadenceSpm / 60.0 * stepLengthM
}

/**
 * MET by walking speed, linearly interpolated.
 * Source: Ainsworth BE et al., 2011 Compendium of Physical Activities (Med Sci Sports Exerc 43(8):1575-81),
 * walking codes 17150-17250 (level, firm surface): ~2.0 mph 2.8, 2.5 mph 3.0, 3.0 mph 3.5, 3.5 mph 4.3, 4.0 mph 5.0,
 * 4.5 mph 7.0, 5.0 mph 8.3 METs. Values are transcribed from memory: verify against the published tables before
 * relying on them. The anchor at 0 km/h (1.0 MET = rest) and 1.6 km/h (2.0 MET, "strolling") are interpolation
 * anchors, not Compendium rows. Above 8 km/h we clamp: this is a walking app.
 */
object MetTable {
    private const val MPH = 1.609344
    val POINTS: List<Pair<Double, Double>> = listOf(
        0.0 to 1.0,
        1.6 to 2.0,
        2.0 * MPH to 2.8,
        2.5 * MPH to 3.0,
        3.0 * MPH to 3.5,
        3.5 * MPH to 4.3,
        4.0 * MPH to 5.0,
        4.5 * MPH to 7.0,
        5.0 * MPH to 8.3,
    )

    fun metForKmh(kmh: Double): Double {
        if (kmh.isNaN() || kmh <= POINTS.first().first) return POINTS.first().second
        if (kmh >= POINTS.last().first) return POINTS.last().second
        for (i in 1 until POINTS.size) {
            val (x1, y1) = POINTS[i]
            if (kmh <= x1) {
                val (x0, y0) = POINTS[i - 1]
                return y0 + (y1 - y0) * (kmh - x0) / (x1 - x0)
            }
        }
        return POINTS.last().second
    }

    fun metForMps(mps: Double) = metForKmh(mps * 3.6)
}

/** Always a range: false precision is misleading. Values are rounded to the nearest 5 kcal. */
data class CalorieRange(val lowKcal: Int, val highKcal: Int) {
    fun label(): String = "about $lowKcal to $highKcal kcal (${Copy.CALORIE_LABEL.lowercase()})"
}

object CalorieEstimator {
    const val UNCERTAINTY = 0.25

    /** NET kcal = (MET - 1) x weight_kg x hours, so resting energy is not counted as walking energy. */
    fun netKcal(met: Double, weightKg: Double, hours: Double): Double = ((met - 1.0).coerceAtLeast(0.0)) * weightKg * hours

    /**
     * Speed preference: good GPS distance/time, otherwise cadence x step length (from height or calibration).
     * Returns null when the profile is missing or implausible, so callers show neutral copy.
     */
    fun estimate(
        profile: BodyProfile,
        durationMs: Long,
        gpsDistanceM: Double?,
        steps: Long,
        stepLengthOverrideM: Double? = null,
    ): CalorieRange? {
        val w = profile.weightKg ?: return null
        if (!ProfileCheck.isPlausible(profile) || durationMs < 60_000) return null
        val hours = durationMs / 3_600_000.0
        val gpsSpeed = if (gpsDistanceM != null && gpsDistanceM >= 50.0) gpsDistanceM / (durationMs / 1000.0) else null
        val stepLen = stepLengthOverrideM ?: StepLength.fromHeight(profile.heightCm, profile.sex) ?: StepLength.DEFAULT_M
        val cadenceSpeed = if (steps > 0) StepLength.speedFromCadence(steps / (durationMs / 60_000.0), stepLen) else null
        val speed = gpsSpeed ?: cadenceSpeed ?: return null
        val net = netKcal(MetTable.metForMps(speed), w, hours)
        return rangeOf(net)
    }

    fun rangeOf(net: Double): CalorieRange {
        fun r5(x: Double) = (Math.round(x / 5.0) * 5).toInt()
        return CalorieRange(r5(net * (1 - UNCERTAINTY)), r5(net * (1 + UNCERTAINTY)))
    }
}

/** User can hide calories entirely. */
object CalorieDisplay {
    fun text(range: CalorieRange?, enabled: Boolean): String? = if (!enabled || range == null) null else range.label()
}
