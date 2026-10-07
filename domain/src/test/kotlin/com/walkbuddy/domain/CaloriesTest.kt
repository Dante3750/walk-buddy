package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaloriesTest {
    @Test fun stepLengthFromHeightUsesSexSpecificFactors() {
        assertEquals(1.75 * 0.415, StepLength.fromHeight(175.0, Sex.Male)!!, 1e-9)
        assertEquals(1.65 * 0.413, StepLength.fromHeight(165.0, Sex.Female)!!, 1e-9)
        assertEquals(1.70 * 0.414, StepLength.fromHeight(170.0, Sex.Unspecified)!!, 1e-9)
        assertNull(StepLength.fromHeight(null, Sex.Male))
        assertNull(StepLength.fromHeight(20.0, Sex.Male))
    }

    @Test fun stepLengthCalibration() {
        assertEquals(0.72, StepLength.calibrate(720.0, 1000)!!, 1e-9)
        assertNull(StepLength.calibrate(50.0, 1000)) // too short
        assertNull(StepLength.calibrate(1000.0, 100)) // too few steps
        assertNull(StepLength.calibrate(5000.0, 1000)) // 5 m per step is nonsense
    }

    @Test fun speedFromCadence() = assertEquals(1.4, StepLength.speedFromCadence(120.0, 0.7), 1e-9)

    @Test fun metTableMatchesCompendiumAnchorsAndInterpolates() {
        fun mph(v: Double) = MetTable.metForKmh(v * 1.609344)
        assertEquals(2.8, mph(2.0), 1e-9)
        assertEquals(3.5, mph(3.0), 1e-9)
        assertEquals(4.3, mph(3.5), 1e-9)
        assertEquals(5.0, mph(4.0), 1e-9)
        assertEquals(3.9, mph(3.25), 1e-9) // halfway between 3.5 and 4.3
        assertEquals(6.0, mph(4.25), 1e-9)
    }

    @Test fun metIsMonotonicAndClamped() {
        var prev = 0.0
        var kmh = 0.0
        while (kmh <= 12.0) { val m = MetTable.metForKmh(kmh); assertTrue(m >= prev); prev = m; kmh += 0.1 }
        assertEquals(8.3, MetTable.metForKmh(30.0), 1e-9)
        assertEquals(1.0, MetTable.metForKmh(-3.0), 1e-9)
        assertEquals(1.0, MetTable.metForKmh(Double.NaN), 1e-9)
    }

    @Test fun netKcalSubtractsRestingMet() {
        assertEquals(175.0, CalorieEstimator.netKcal(3.5, 70.0, 1.0), 1e-9)
        assertEquals(0.0, CalorieEstimator.netKcal(0.5, 70.0, 1.0), 1e-9)
    }

    @Test fun alwaysARangeAroundTwentyFivePercent() {
        val r = CalorieEstimator.rangeOf(175.0)
        assertEquals(130, r.lowKcal)
        assertEquals(220, r.highKcal)
        assertTrue(r.lowKcal < r.highKcal)
        assertTrue(r.label().contains("estimate"))
        assertTrue(r.lowKcal % 5 == 0 && r.highKcal % 5 == 0)
    }

    @Test fun estimateFromGpsAndFromCadence() {
        val p = BodyProfile(heightCm = 170.0, weightKg = 70.0, sex = Sex.Unspecified)
        val gps = CalorieEstimator.estimate(p, 3_600_000, 5000.0, 6500)!!
        // 5 km/h => ~3.67 MET => ~187 net kcal
        assertTrue("$gps", gps.lowKcal in 130..150 && gps.highKcal in 220..240)
        val noGps = CalorieEstimator.estimate(p, 3_600_000, null, 6500)!! // 108 spm x 0.704 m => 4.6 km/h
        assertTrue("$noGps", noGps.lowKcal in 100..140)
    }

    @Test fun missingOrImplausibleProfileGivesNoNumbers() {
        assertNull(CalorieEstimator.estimate(BodyProfile(heightCm = 170.0), 3_600_000, 5000.0, 6000))
        assertNull(CalorieEstimator.estimate(BodyProfile(weightKg = 10.0), 3_600_000, 5000.0, 6000))
        assertNull(CalorieEstimator.estimate(BodyProfile(weightKg = 70.0, heightCm = 500.0), 3_600_000, 5000.0, 6000))
        assertNull(CalorieEstimator.estimate(BodyProfile(weightKg = 70.0), 10_000, 5.0, 10)) // too short
        assertFalse(ProfileCheck.isPlausible(BodyProfile(weightKg = Double.NaN)))
        assertTrue(ProfileCheck.isPlausible(BodyProfile()))
    }

    @Test fun userCanHideCaloriesEntirely() {
        val r = CalorieRange(100, 150)
        assertNull(CalorieDisplay.text(r, enabled = false))
        assertNotNull(CalorieDisplay.text(r, enabled = true))
        assertNull(CalorieDisplay.text(null, enabled = true))
    }

    @Test fun noWeightLossFramingAnywhere() {
        val copy = listOf(Copy.CALORIE_NOTE, Copy.CALORIE_LABEL, Copy.NEUTRAL_PROFILE, CalorieRange(100, 150).label()).joinToString(" ").lowercase()
        for (bad in listOf("burn off", "deficit", "bmi", "lose weight", "weight loss", "fat")) assertFalse("'$bad' in copy", bad in copy)
    }
}
