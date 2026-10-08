package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsHeroTest {
    @Test fun metricDistance() {
        assertEquals("850 m", Units.distance(850.0, UnitSystem.Metric))
        assertEquals("1.25 km", Units.distance(1250.0, UnitSystem.Metric))
        assertEquals("0 m", Units.distance(Double.NaN, UnitSystem.Metric))
    }

    @Test fun imperialDistance() {
        assertEquals("1.00 mi", Units.distance(1609.344, UnitSystem.Imperial))
        assertEquals("98 ft", Units.distance(30.0, UnitSystem.Imperial))
        assertEquals("12.4 mi", Units.longDistance(20_000.0, UnitSystem.Imperial))
        assertEquals("20.0 km", Units.longDistance(20_000.0, UnitSystem.Metric))
    }

    @Test fun imperialPace() {
        assertEquals("-", Units.pace(null, UnitSystem.Imperial))
        assertEquals("26:49 /mi", Units.pace(1.0, UnitSystem.Imperial))
        assertEquals(Format.pace(1.5), Units.pace(1.5, UnitSystem.Metric))
    }

    @Test fun thousandsSeparator() {
        assertEquals("0", Hero.thousands(0))
        assertEquals("999", Hero.thousands(999))
        assertEquals("1,000", Hero.thousands(1000))
        assertEquals("12,345", Hero.thousands(12345))
        assertEquals("1,234,567", Hero.thousands(1234567))
        assertEquals("-1,500", Hero.thousands(-1500))
    }

    @Test fun fractionIsClamped() {
        assertEquals(0.0, Hero.fraction(100, 0), 0.0)
        assertEquals(0.5, Hero.fraction(500, 1000), 1e-9)
        assertEquals(1.0, Hero.fraction(5000, 1000), 0.0)
        assertTrue(Hero.goalReached(1000, 1000)); assertFalse(Hero.goalReached(999, 1000))
    }

    @Test fun toGoText() {
        assertEquals("3,200 to go", Hero.toGoText(2800, 6000))
        assertEquals("Goal reached", Hero.toGoText(6010, 6000))
        assertEquals("1,000 past your goal", Hero.toGoText(7000, 6000))
    }

    @Test fun aheadBehindLabels() {
        assertEquals("+1,240", Hero.delta(4000, 5240))
        assertEquals("-500", Hero.delta(5500, 5000))
        assertEquals("=", Hero.delta(5000, 5050))
        assertEquals("Neck and neck with Sam", Hero.leadText("Sam", 5000, 5040))
        assertEquals("You are 1,000 steps ahead of Sam", Hero.leadText("Sam", 6000, 5000))
        assertEquals("Sam is 1,000 steps ahead", Hero.leadText("Sam", 5000, 6000))
    }

    @Test fun ringBuddyInitialAndFraction() {
        val b = RingBuddy("p1", " sam", 4000, 8000)
        assertEquals("S", b.initial); assertEquals(0.5, b.fraction, 1e-9)
        assertEquals("?", RingBuddy("p2", "", 0, 0).initial)
    }

    @Test fun distanceNeverBelowStepEstimate() {
        assertEquals(3500.0, Hero.distanceM(5000, 100.0, 0.7), 1e-9)
        assertEquals(4000.0, Hero.distanceM(5000, 4000.0, 0.7), 1e-9)
    }

    @Test fun activeMinutesFallbackIsEstimate() {
        assertEquals(30, Hero.activeMinutes(3300, 0)); assertTrue(Hero.activeIsEstimate(0))
        assertEquals(12, Hero.activeMinutes(9000, 12)); assertFalse(Hero.activeIsEstimate(12))
    }

    @Test fun numberSizeShrinksForLongNumbers() {
        val a = Hero.numberSizeSp("8,421", 272f); val b = Hero.numberSizeSp("12,345", 272f)
        assertTrue(a > b); assertTrue(a in 56f..116f); assertTrue(b >= 56f)
        assertEquals(116f, Hero.numberSizeSp("5", 272f), 0f)
        assertEquals(56f, Hero.numberSizeSp("1,234,567", 100f), 0f)
    }
}
