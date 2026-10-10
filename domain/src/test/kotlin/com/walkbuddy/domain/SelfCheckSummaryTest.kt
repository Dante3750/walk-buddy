package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCheckSummaryTest {
    @Test fun activityPermissionVerdicts() {
        assertEquals(CheckStatus.Pass, SelfCheckLogic.activity(needed = false, granted = false, canAskAgain = true).status)
        assertEquals(CheckStatus.Pass, SelfCheckLogic.activity(true, true, true).status)
        assertEquals(FixAction.RequestActivity, SelfCheckLogic.activity(true, false, true).fix)
        assertEquals(FixAction.AppSettings, SelfCheckLogic.activity(true, false, false).fix)
    }

    @Test fun sensorVerdicts() {
        assertEquals(CheckStatus.Pass, SelfCheckLogic.stepSensor(StepSensorKind.Counter).status)
        assertEquals(CheckStatus.Warn, SelfCheckLogic.stepSensor(StepSensorKind.Detector).status)
        assertEquals(CheckStatus.Warn, SelfCheckLogic.stepSensor(StepSensorKind.Accelerometer).status)
        assertEquals(CheckStatus.Fail, SelfCheckLogic.stepSensor(null).status)
    }

    @Test fun locationVerdicts() {
        assertEquals(CheckStatus.Pass, SelfCheckLogic.location(true, true, true).status)
        assertEquals(CheckStatus.Warn, SelfCheckLogic.location(true, false, true).status)
        assertEquals(FixAction.RequestLocation, SelfCheckLogic.location(false, false, true).fix)
        assertEquals(FixAction.AppSettings, SelfCheckLogic.location(false, false, false).fix)
        assertEquals(FixAction.LocationSettings, SelfCheckLogic.locationProvider(false, true).fix)
        assertEquals(CheckStatus.Pass, SelfCheckLogic.locationProvider(true, true).status)
        assertEquals(CheckStatus.Warn, SelfCheckLogic.locationProvider(false, false).status)
    }

    @Test fun notificationsAndBattery() {
        assertEquals(CheckStatus.Pass, SelfCheckLogic.notifications(false, false, true).status)
        assertEquals(FixAction.RequestNotifications, SelfCheckLogic.notifications(true, false, true).fix)
        assertEquals(FixAction.AppSettings, SelfCheckLogic.notifications(true, false, false).fix)
        assertEquals(CheckStatus.Pass, SelfCheckLogic.battery(true).status)
        assertEquals(FixAction.BatterySettings, SelfCheckLogic.battery(false).fix)
    }

    @Test fun serverVerdictsNeverLeakDetails() {
        assertEquals(CheckStatus.Pass, SelfCheckLogic.server(200, 300, null).status)
        assertEquals(CheckStatus.Warn, SelfCheckLogic.server(200, 20_000, null).status)
        assertEquals(CheckStatus.Fail, SelfCheckLogic.server(503, 100, null).status)
        val f = SelfCheckLogic.server(null, null, "failed to connect to https://walk-buddy-server.example.org/health at 10.2.3.4")
        assertEquals(CheckStatus.Fail, f.status); assertEquals(FixAction.Retry, f.fix)
        assertFalse(f.detail!!.contains("example.org")); assertFalse(f.detail!!.contains("10.2.3.4"))
    }

    @Test fun storageVerdicts() {
        assertEquals(CheckStatus.Pass, SelfCheckLogic.storage(500L * 1024 * 1024, true).status)
        assertEquals(CheckStatus.Warn, SelfCheckLogic.storage(10L * 1024 * 1024, true).status)
        assertEquals(CheckStatus.Fail, SelfCheckLogic.storage(500L * 1024 * 1024, false).status)
    }

    @Test fun overallPutsFailuresFirst() {
        val pass = CheckResult(CheckId.Battery, CheckStatus.Pass)
        assertEquals(CheckStatus.Pass, SelfCheckLogic.overall(listOf(pass)))
        assertEquals(CheckStatus.Warn, SelfCheckLogic.overall(listOf(pass, CheckResult(CheckId.Battery, CheckStatus.Warn))))
        assertEquals(CheckStatus.Fail, SelfCheckLogic.overall(listOf(pass, CheckResult(CheckId.Battery, CheckStatus.Warn), CheckResult(CheckId.Server, CheckStatus.Fail))))
        assertEquals(CheckStatus.Running, SelfCheckLogic.overall(listOf(pass, CheckResult(CheckId.Server, CheckStatus.Running))))
        assertTrue(SelfCheckLogic.lines(listOf(pass)).single().startsWith("Battery: Pass"))
    }

    // ---------- summary card ----------

    private fun record(route: String?): SharedWalkRecord {
        val lanes = listOf(
            SharedLane("me", "Me", AvatarCode.encode(Avatar(AvatarStyle.Boy, 1)), true, 2400, 1800.0),
            SharedLane("p", "Asha", AvatarCode.encode(Avatar(AvatarStyle.Girl, 5, AvatarAccessory.Cap)), false, 2300, 1750.0),
        )
        val samples = (0..10).map { TrackSample(it * 60, listOf(it * 150, it * 150 - 20)) }
        return SharedWalkRecord(1, 1_000_000, 600_000, SharedMode.Partner, 2, "Asha", lanes, samples, 91, 300_000, 40.0, route)
    }

    private val route = Polyline.encode((0..40).map { T.east(T.north(T.origin, it * 10.0), it * 3.0) })

    @Test fun routeIsHiddenUnlessAsked() {
        val hidden = SummaryCard.plan(record(route), UnitSystem.Metric, includeRoute = false, title = "Walk", dateText = "Today")
        assertNull(hidden.route)
        val shown = SummaryCard.plan(record(route), UnitSystem.Metric, includeRoute = true, title = "Walk", dateText = "Today")
        assertNotNull(shown.route)
        assertTrue(shown.route!!.all { it.first in 0f..1f && it.second in 0f..1f })
        assertNull(SummaryCard.plan(record(null), UnitSystem.Metric, true, "Walk", "Today").route)
    }

    @Test fun cardHasStatsAndAStripButNoPlaceNames() {
        val p = SummaryCard.plan(record(route), UnitSystem.Metric, false, "Walk together", "Sat 10 Oct")
        assertEquals(listOf("distance", "time", "steps", "together"), p.stats.map { it.first })
        assertEquals("91%", p.stats.last().second)
        assertEquals(SummaryCard.STRIP_STEPS, p.strip.size)
        assertEquals(2, p.laneAvatars.size)
        assertTrue(p.strip.flatten().filterNotNull().all { it in 0f..1f })
    }

    @Test fun outlineKeepsShapeAndHandlesDegenerateInput() {
        assertNull(SummaryCard.outline(listOf(T.origin)))
        assertNull(SummaryCard.outline(listOf(T.origin, T.origin)))
        val o = SummaryCard.outline((0..500).map { T.north(T.origin, it * 2.0) }, maxPoints = 50)!!
        assertTrue(o.size <= 50)
        // a straight north line: x constant in the middle, y runs 1 -> 0
        assertTrue(o.all { kotlin.math.abs(it.first - 0.5f) < 0.01f })
        assertTrue(o.first().second > o.last().second)
    }

    @Test fun linksPointAtTheRepository() {
        assertTrue(AppLinks.RELEASES.startsWith("https://github.com/Dante3750/walk-buddy"))
        assertTrue(AppLinks.RELEASES.endsWith("/releases"))
    }
}
