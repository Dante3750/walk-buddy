package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachWeatherTest {
    // ---------- pace coach ----------

    private fun input(t: Long, mine: Double?, others: List<Double?>, sweeper: Boolean = false, quiet: Boolean = false, qh: Boolean = false) =
        CoachInput(t, mine, others, sweeper, quiet, qh)

    private fun run(c: PaceCoach, from: Long, to: Long, mine: Double, others: List<Double?>, step: Long = 5_000, sweeper: Boolean = false): List<CoachAdvice> =
        (from..to step step).map { c.update(input(it, mine, others, sweeper)) }

    @Test fun offByDefaultNeverSpeaks() {
        val c = PaceCoach()
        assertTrue(run(c, 0, 600_000, 2.0, listOf(1.0)).all { it == CoachAdvice.None })
    }

    @Test fun fasterWalkerIsAskedToEaseOffOnlyAfterItLasts() {
        val c = PaceCoach(CoachConfig(CoachMode.Gentle))
        val a = run(c, 0, 30_000, 1.8, listOf(1.2))
        assertTrue("not yet: sustain is 40 s", a.all { it == CoachAdvice.None })
        val b = c.update(input(50_000, 1.8, listOf(1.2)))
        assertTrue(b is CoachAdvice.EaseOff)
        assertEquals(1.2, (b as CoachAdvice.EaseOff).targetMps, 1e-9)
    }

    @Test fun cooldownPreventsRepeats() {
        val c = PaceCoach(CoachConfig(CoachMode.Gentle))
        val first = run(c, 0, 60_000, 1.8, listOf(1.2)).filterIsInstance<CoachAdvice.EaseOff>()
        assertEquals(1, first.size)
        val soon = run(c, 61_000, 200_000, 1.8, listOf(1.2)).filterIsInstance<CoachAdvice.EaseOff>()
        assertTrue("cooldown 4 min", soon.isEmpty())
        val later = run(c, 300_000, 400_000, 1.8, listOf(1.2)).filterIsInstance<CoachAdvice.EaseOff>()
        assertEquals(1, later.size)
    }

    @Test fun slowerWalkerGetsAGentlePickUpInGentleModeOnly() {
        val g = PaceCoach(CoachConfig(CoachMode.Gentle))
        assertTrue(run(g, 0, 60_000, 0.9, listOf(1.4)).any { it is CoachAdvice.PickUp })
        val s = PaceCoach(CoachConfig(CoachMode.SlowestPace))
        assertTrue(run(s, 0, 120_000, 0.9, listOf(1.4)).all { it == CoachAdvice.None })
    }

    @Test fun slowestPaceModeAsksTheFasterOneToMatchTheSlowest() {
        val c = PaceCoach(CoachConfig(CoachMode.SlowestPace))
        val a = run(c, 0, 60_000, 1.7, listOf(1.4, 1.0, 1.5)).filterIsInstance<CoachAdvice.EaseOff>()
        assertEquals(1, a.size)
        assertEquals(1.0, a[0].targetMps, 1e-9)
    }

    @Test fun sweeperIsNeverAskedToSpeedUp() {
        val c = PaceCoach(CoachConfig(CoachMode.Gentle))
        assertTrue(run(c, 0, 300_000, 0.8, listOf(1.5, 1.4), sweeper = true).all { it == CoachAdvice.None })
    }

    @Test fun quietModeAndQuietHoursSilenceItAndResetTheTimer() {
        val c = PaceCoach(CoachConfig(CoachMode.Gentle))
        run(c, 0, 30_000, 1.8, listOf(1.2))
        assertEquals(CoachAdvice.None, c.update(input(50_000, 1.8, listOf(1.2), quiet = true)))
        // timer restarted: the next ask needs another 40 s
        assertEquals(CoachAdvice.None, c.update(input(55_000, 1.8, listOf(1.2))))
        assertEquals(CoachAdvice.None, c.update(input(60_000, 1.8, listOf(1.2), qh = true)))
    }

    @Test fun standingStillOrNoOneElseMeansSilence() {
        val c = PaceCoach(CoachConfig(CoachMode.Gentle))
        assertTrue(run(c, 0, 200_000, 0.1, listOf(1.4)).all { it == CoachAdvice.None })
        assertTrue(run(c, 0, 200_000, 1.4, listOf(null, 0.2)).all { it == CoachAdvice.None })
        assertTrue(run(c, 0, 200_000, 1.4, emptyList()).all { it == CoachAdvice.None })
    }

    @Test fun capsTheNumberOfHintsPerWalk() {
        val c = PaceCoach(CoachConfig(CoachMode.Gentle, cooldownMs = 1_000, sustainMs = 1_000, maxPerWalk = 3))
        val all = run(c, 0, 600_000, 1.9, listOf(1.2), step = 2_000).filterIsInstance<CoachAdvice.EaseOff>()
        assertEquals(3, all.size)
        c.reset()
        assertEquals(0, c.adviceCount)
    }

    // ---------- weather ----------

    private val sample = """{"latitude":13.0,"hourly":{"time":["2026-10-10T05:00","2026-10-10T06:00","2026-10-10T07:00","2026-10-10T08:00","2026-10-10T12:00","2026-10-10T17:00","2026-10-10T18:00","2026-10-10T19:00","2026-10-10T20:00"],
        "temperature_2m":[19.0,20.0,22.0,24.0,34.0,30.0,27.0,24.5,23.0],"precipitation_probability":[5,5,10,10,0,60,40,5,5],
        "wind_speed_10m":[4,5,6,8,15,14,12,9,8],"uv_index":[0,0,1.5,3,9.5,2,0.5,0,0]}}"""

    @Test fun locationIsRoundedToACoarseTenthOfADegree() {
        assertEquals(12.97 .let { 13.0 }, WeatherPrivacy.coarse(12.9716), 1e-9)
        assertEquals(77.6, WeatherPrivacy.coarse(77.5946), 1e-9)
        assertEquals(-33.9, WeatherPrivacy.coarse(-33.8688), 1e-9)
        val url = WeatherPrivacy.url(WeatherPrivacy.coarse(LatLon(12.9716, 77.5946)))
        assertTrue(url.contains("latitude=13.0&longitude=77.6"))
        assertFalse("no finer position in the request", url.contains("12.97") || url.contains("77.59"))
        assertTrue(url.startsWith("https://api.open-meteo.com/"))
    }

    @Test fun cacheLastsThreeHours() {
        assertTrue(WeatherPrivacy.isFresh(0, 2 * 3_600_000L))
        assertFalse(WeatherPrivacy.isFresh(0, 3 * 3_600_000L))
        assertFalse(WeatherPrivacy.isFresh(10_000, 5_000)) // clock went back
    }

    @Test fun parsesOpenMeteoHourly() {
        val h = WeatherParser.parse(sample)!!
        assertEquals(9, h.size)
        assertEquals(7, h[2].hour); assertEquals(22.0, h[2].tempC, 0.0); assertEquals(10, h[2].rainPct)
        assertEquals(9.5, h[4].uv, 0.0)
    }

    @Test fun parserRejectsGarbageWithoutThrowing() {
        for (bad in listOf(null, "", "nope", "{}", """{"hourly":{}}""", """{"hourly":{"time":["x"],"temperature_2m":[1]}}""", """{"hourly":{"time":["2026-10-10T07:00"],"temperature_2m":[999]}}""")) {
            assertNull(WeatherParser.parse(bad))
        }
    }

    @Test fun picksAPleasantDryMorningOverMiddayHeat() {
        val w = WalkWindows.best(WeatherParser.parse(sample)!!, nowHour = 6)!!
        assertTrue("window ${w.startHour}", w.startHour in 6..8 || w.startHour in 19..20)
        assertTrue(w.endHour > w.startHour)
        assertTrue(w.rainPct <= 10)
    }

    @Test fun ignoresHoursThatHavePassed() {
        val w = WalkWindows.best(WeatherParser.parse(sample)!!, nowHour = 17)!!
        assertTrue(w.startHour >= 17)
        assertEquals(19, w.startHour)
    }

    @Test fun nothingLeftTodayGivesNull() {
        assertNull(WalkWindows.best(WeatherParser.parse(sample)!!, nowHour = 22))
        assertNull(WalkWindows.best(emptyList(), nowHour = 6))
    }

    @Test fun aWetHotDayStillFindsTheLeastRain() {
        val hours = (6..21).map { HourWeather(it, 41.0, if (it == 15) 30 else 90, 5.0, 2.0) }
        val w = WalkWindows.best(hours, 6)!!
        assertEquals(WeatherReason.LeastRain, w.reason)
        assertEquals(15, w.startHour)
    }

    @Test fun scoreIsBoundedAndSensible() {
        assertEquals(100, WalkWindows.score(HourWeather(8, 20.0, 0, 5.0, 1.0)))
        assertEquals(0, WalkWindows.score(HourWeather(8, 45.0, 100, 70.0, 11.0)))
        assertTrue(WalkWindows.score(HourWeather(8, 20.0, 0, 5.0, 1.0)) > WalkWindows.score(HourWeather(8, 20.0, 80, 5.0, 1.0)))
        assertNotNull(WeatherReason.values())
    }
}
