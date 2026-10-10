package com.walkbuddy.domain

/**
 * The Self-check screen (alpha 2.0): a few quick tests that say, in plain words, what works on this phone and what to tap to fix it.
 * Android supplies the raw facts; this file turns them into a verdict and a fix, so the rules are tested.
 */
enum class CheckId { ActivityPermission, StepSensor, LocationPermission, LocationProvider, Notifications, Battery, Server, Storage }

enum class CheckStatus { Pass, Warn, Fail, Running }

enum class FixAction { None, RequestActivity, RequestLocation, RequestNotifications, AppSettings, LocationSettings, BatterySettings, Retry }

data class CheckResult(val id: CheckId, val status: CheckStatus, val fix: FixAction = FixAction.None, val detail: String? = null) {
    val ok: Boolean get() = status == CheckStatus.Pass
}

object SelfCheckLogic {
    const val LOW_STORAGE_BYTES = 50L * 1024 * 1024
    const val SLOW_SERVER_MS = 8_000L

    fun activity(needed: Boolean, granted: Boolean, canAskAgain: Boolean): CheckResult = when {
        !needed -> CheckResult(CheckId.ActivityPermission, CheckStatus.Pass)
        granted -> CheckResult(CheckId.ActivityPermission, CheckStatus.Pass)
        canAskAgain -> CheckResult(CheckId.ActivityPermission, CheckStatus.Fail, FixAction.RequestActivity)
        else -> CheckResult(CheckId.ActivityPermission, CheckStatus.Fail, FixAction.AppSettings)
    }

    fun stepSensor(kind: StepSensorKind?): CheckResult = when (kind) {
        StepSensorKind.Counter -> CheckResult(CheckId.StepSensor, CheckStatus.Pass, detail = "counter")
        StepSensorKind.Detector -> CheckResult(CheckId.StepSensor, CheckStatus.Warn, detail = "detector")
        StepSensorKind.Accelerometer -> CheckResult(CheckId.StepSensor, CheckStatus.Warn, detail = "motion")
        null -> CheckResult(CheckId.StepSensor, CheckStatus.Fail, detail = "none")
    }

    fun location(granted: Boolean, precise: Boolean, canAskAgain: Boolean): CheckResult = when {
        granted && precise -> CheckResult(CheckId.LocationPermission, CheckStatus.Pass)
        granted -> CheckResult(CheckId.LocationPermission, CheckStatus.Warn, FixAction.AppSettings, "approximate")
        canAskAgain -> CheckResult(CheckId.LocationPermission, CheckStatus.Fail, FixAction.RequestLocation)
        else -> CheckResult(CheckId.LocationPermission, CheckStatus.Fail, FixAction.AppSettings)
    }

    fun locationProvider(anyOn: Boolean, permissionGranted: Boolean): CheckResult = when {
        !permissionGranted -> CheckResult(CheckId.LocationProvider, CheckStatus.Warn, FixAction.None, "needs permission")
        anyOn -> CheckResult(CheckId.LocationProvider, CheckStatus.Pass)
        else -> CheckResult(CheckId.LocationProvider, CheckStatus.Fail, FixAction.LocationSettings)
    }

    fun notifications(needed: Boolean, allowed: Boolean, canAskAgain: Boolean): CheckResult = when {
        !needed || allowed -> CheckResult(CheckId.Notifications, CheckStatus.Pass)
        canAskAgain -> CheckResult(CheckId.Notifications, CheckStatus.Warn, FixAction.RequestNotifications)
        else -> CheckResult(CheckId.Notifications, CheckStatus.Warn, FixAction.AppSettings)
    }

    fun battery(unrestricted: Boolean): CheckResult =
        if (unrestricted) CheckResult(CheckId.Battery, CheckStatus.Pass) else CheckResult(CheckId.Battery, CheckStatus.Warn, FixAction.BatterySettings)

    /** [httpCode] is null when the request failed; [ms] is how long it took. */
    fun server(httpCode: Int?, ms: Long?, error: String?): CheckResult = when {
        httpCode == null -> CheckResult(CheckId.Server, CheckStatus.Fail, FixAction.Retry, error?.let { LogRedactor.redact(it).take(60) })
        httpCode in 200..299 && (ms ?: 0) > SLOW_SERVER_MS -> CheckResult(CheckId.Server, CheckStatus.Warn, FixAction.Retry, "slow")
        httpCode in 200..299 -> CheckResult(CheckId.Server, CheckStatus.Pass, detail = ms?.let { "$it ms" })
        else -> CheckResult(CheckId.Server, CheckStatus.Fail, FixAction.Retry, "HTTP $httpCode")
    }

    fun storage(freeBytes: Long, writable: Boolean): CheckResult = when {
        !writable -> CheckResult(CheckId.Storage, CheckStatus.Fail, FixAction.None, "not writable")
        freeBytes < LOW_STORAGE_BYTES -> CheckResult(CheckId.Storage, CheckStatus.Warn, FixAction.None, "${freeBytes / (1024 * 1024)} MB free")
        else -> CheckResult(CheckId.Storage, CheckStatus.Pass, detail = "${freeBytes / (1024 * 1024)} MB free")
    }

    /** Failures first; used for the headline. */
    fun overall(results: List<CheckResult>): CheckStatus = when {
        results.any { it.status == CheckStatus.Running } -> CheckStatus.Running
        results.any { it.status == CheckStatus.Fail } -> CheckStatus.Fail
        results.any { it.status == CheckStatus.Warn } -> CheckStatus.Warn
        else -> CheckStatus.Pass
    }

    /** Plain-text lines for the diagnostics export (no secrets in them). */
    fun lines(results: List<CheckResult>): List<String> = results.map { "${it.id.name}: ${it.status.name}" + (it.detail?.let { d -> " ($d)" } ?: "") }
}
