package com.walkbuddy.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.walkbuddy.BuildConfig
import com.walkbuddy.R
import com.walkbuddy.diag.AppLog
import com.walkbuddy.domain.AppLinks
import com.walkbuddy.domain.CheckId
import com.walkbuddy.domain.CheckResult
import com.walkbuddy.domain.CheckStatus
import com.walkbuddy.domain.FixAction
import com.walkbuddy.domain.SelfCheckLogic
import com.walkbuddy.domain.StepSensorKind
import com.walkbuddy.rtc.Http
import com.walkbuddy.rtc.probeHealth
import com.walkbuddy.steps.StepTracking
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.PermState
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.ScreenTitle
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.StatusPill
import com.walkbuddy.ui.rememberPermState
import com.walkbuddy.ui.rememberPermissionRequester

/** What the Self-check screen can do. Plain callbacks so the content renders in a screenshot test with no ViewModel. */
class SelfCheckActions(
    val onFix: (FixAction) -> Unit = {},
    val onRerun: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onExport: () -> Unit = {},
)

private val askedPrefs = "wb_selfcheck"

private fun wasAsked(ctx: Context, key: String) = ctx.getSharedPreferences(askedPrefs, Context.MODE_PRIVATE).getBoolean(key, false)
private fun markAsked(ctx: Context, key: String) { ctx.getSharedPreferences(askedPrefs, Context.MODE_PRIVATE).edit().putBoolean(key, true).apply() }

private tailrec fun Context.activityOrNull(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

/** True while the system would still show its permission dialog (never asked, or asked and denied once). */
private fun canAskAgain(ctx: Context, key: String, permission: String): Boolean =
    !wasAsked(ctx, key) || (ctx.activityOrNull()?.shouldShowRequestPermissionRationale(permission) ?: false)

private suspend fun runChecks(ctx: Context, perms: PermState, kind: StepSensorKind?, needsActivityPermission: Boolean, server: Boolean): List<CheckResult> {
    val act = SelfCheckLogic.activity(
        needsActivityPermission, perms.activity,
        canAskAgain(ctx, "activity", android.Manifest.permission.ACTIVITY_RECOGNITION),
    )
    val sensor = SelfCheckLogic.stepSensor(kind)
    val loc = SelfCheckLogic.location(perms.location, perms.preciseLocation, canAskAgain(ctx, "location", android.Manifest.permission.ACCESS_FINE_LOCATION))
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val anyOn = runCatching { lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) }.getOrDefault(false)
    val provider = SelfCheckLogic.locationProvider(anyOn, perms.location)
    val enabled = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    val notif = SelfCheckLogic.notifications(
        true, perms.notifications && enabled,
        Build.VERSION.SDK_INT >= 33 && canAskAgain(ctx, "notifications", "android.permission.POST_NOTIFICATIONS"),
    )
    val battery = SelfCheckLogic.battery(StepTracking.ignoringBatteryOptimizations(ctx))
    val srv = if (server) {
        val p = Http.probeHealth()
        SelfCheckLogic.server(p.code, p.ms, p.error)
    } else CheckResult(CheckId.Server, CheckStatus.Running)
    val dir = ctx.filesDir
    val storage = SelfCheckLogic.storage(runCatching { dir.usableSpace }.getOrDefault(0L), runCatching { dir.canWrite() }.getOrDefault(false))
    return listOf(act, sensor, loc, provider, notif, battery, srv, storage)
}

@Composable
fun SelfCheckScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val perms = rememberPermState()
    var results by remember { mutableStateOf(emptyList<CheckResult>()) }
    var rerun by remember { mutableStateOf(0) }
    val askActivity = rememberPermissionRequester(perms) { rerun++ }
    val askLocation = rememberPermissionRequester(perms) { rerun++ }
    val askNotif = rememberPermissionRequester(perms) { rerun++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { perms.refresh(); rerun++ }
    LaunchedEffect(rerun) {
        // Local checks first so the screen is useful at once; the server probe fills in when it answers.
        results = runChecks(ctx, perms, vm.stepKind, vm.stepsNeedPermission, server = false)
        results = runChecks(ctx, perms, vm.stepKind, vm.stepsNeedPermission, server = true)
        AppLog.i("selfcheck", SelfCheckLogic.lines(results).joinToString("; "))
    }
    SelfCheckContent(
        results,
        SelfCheckActions(
            onFix = { fix ->
                when (fix) {
                    FixAction.RequestActivity -> { markAsked(ctx, "activity"); askActivity(PermState.ACTIVITY) }
                    FixAction.RequestLocation -> { markAsked(ctx, "location"); askLocation(PermState.LOCATION) }
                    FixAction.RequestNotifications -> { markAsked(ctx, "notifications"); askNotif(PermState.NOTIFICATIONS) }
                    FixAction.AppSettings -> StepTracking.openAppSettings(ctx)
                    FixAction.LocationSettings -> runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    FixAction.BatterySettings -> StepTracking.openBatterySettings(ctx)
                    FixAction.Retry -> rerun++
                    FixAction.None -> Unit
                }
            },
            onRerun = { rerun++ },
            onBack = onBack,
            onExport = { exportDiagnostics(ctx, results) },
        ),
    )
}

internal fun exportDiagnostics(ctx: Context, results: List<CheckResult> = emptyList()) {
    try {
        val f = AppLog.writeExportFile(ctx, if (results.isEmpty()) emptyList() else listOf("Self-check: " + SelfCheckLogic.lines(results).joinToString(", ")))
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.diag_export)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, ctx.getString(R.string.no_share_app), Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(ctx, ctx.getString(R.string.diag_failed), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun checkTitle(id: CheckId): String = stringResource(
    when (id) {
        CheckId.ActivityPermission -> R.string.chk_activity
        CheckId.StepSensor -> R.string.chk_sensor
        CheckId.LocationPermission -> R.string.chk_location
        CheckId.LocationProvider -> R.string.chk_provider
        CheckId.Notifications -> R.string.chk_notifications
        CheckId.Battery -> R.string.chk_battery
        CheckId.Server -> R.string.chk_server
        CheckId.Storage -> R.string.chk_storage
    },
)

@Composable
private fun checkHelp(r: CheckResult): String = stringResource(
    when (r.id) {
        CheckId.ActivityPermission -> if (r.ok) R.string.chk_activity_ok else R.string.chk_activity_bad
        CheckId.StepSensor -> if (r.status == CheckStatus.Pass) R.string.chk_sensor_ok else if (r.status == CheckStatus.Warn) R.string.chk_sensor_warn else R.string.chk_sensor_bad
        CheckId.LocationPermission -> if (r.ok) R.string.chk_location_ok else if (r.status == CheckStatus.Warn) R.string.chk_location_warn else R.string.chk_location_bad
        CheckId.LocationProvider -> if (r.ok) R.string.chk_provider_ok else R.string.chk_provider_bad
        CheckId.Notifications -> if (r.ok) R.string.chk_notifications_ok else R.string.chk_notifications_bad
        CheckId.Battery -> if (r.ok) R.string.chk_battery_ok else R.string.chk_battery_bad
        CheckId.Server -> if (r.status == CheckStatus.Running) R.string.chk_server_running else if (r.ok) R.string.chk_server_ok else R.string.chk_server_bad
        CheckId.Storage -> if (r.ok) R.string.chk_storage_ok else R.string.chk_storage_bad
    },
)

@Composable
private fun fixLabel(f: FixAction): String? = when (f) {
    FixAction.None -> null
    FixAction.RequestActivity, FixAction.RequestLocation, FixAction.RequestNotifications -> stringResource(R.string.fix_allow)
    FixAction.AppSettings -> stringResource(R.string.fix_app_settings)
    FixAction.LocationSettings -> stringResource(R.string.fix_location_settings)
    FixAction.BatterySettings -> stringResource(R.string.fix_battery_settings)
    FixAction.Retry -> stringResource(R.string.fix_retry)
}

@Composable
fun SelfCheckContent(results: List<CheckResult>, a: SelfCheckActions) {
    BackHandler(onBack = a.onBack)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = a.onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.back)) }
        ScreenTitle(stringResource(R.string.selfcheck_title), subtitle = stringResource(R.string.selfcheck_sub))
        val overall = SelfCheckLogic.overall(results)
        if (results.isNotEmpty()) {
            SectionCard(null) {
                Text(
                    stringResource(
                        when (overall) {
                            CheckStatus.Pass -> R.string.selfcheck_all_good
                            CheckStatus.Warn -> R.string.selfcheck_some_tips
                            CheckStatus.Fail -> R.string.selfcheck_needs_fix
                            CheckStatus.Running -> R.string.selfcheck_running
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        results.forEach { r ->
            SectionCard(null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(checkTitle(r.id), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    val (label, color) = when (r.status) {
                        CheckStatus.Pass -> stringResource(R.string.status_pass) to MaterialTheme.colorScheme.primary
                        CheckStatus.Warn -> stringResource(R.string.status_warn) to MaterialTheme.colorScheme.tertiary
                        CheckStatus.Fail -> stringResource(R.string.status_fail) to MaterialTheme.colorScheme.error
                        CheckStatus.Running -> stringResource(R.string.status_running) to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    StatusPill(label, color)
                }
                Text(checkHelp(r), style = MaterialTheme.typography.bodyMedium)
                r.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                fixLabel(r.fix)?.let { label ->
                    OutlinedButton(onClick = { a.onFix(r.fix) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = a.onRerun, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.selfcheck_rerun)) }
            OutlinedButton(onClick = a.onExport, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.diag_export)) }
        }
        Disclaimer(stringResource(R.string.diag_note))
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------------
// About

private val licences = listOf(
    "Jetpack Compose, Room, DataStore, WorkManager, Glance, Navigation, CameraX" to "Apache License 2.0 (Google)",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0 (JetBrains)",
    "OkHttp" to "Apache License 2.0 (Square)",
    "WebRTC for Android (stream-webrtc-android)" to "BSD 3-Clause (WebRTC project, Stream)",
    "ZXing (QR codes)" to "Apache License 2.0",
    "Open-Meteo (only if you turn the weather suggestion on)" to "Free for non-commercial use, CC BY 4.0 data",
)

@Composable
fun AboutScreen(onBack: () -> Unit, onSelfCheck: () -> Unit) {
    val ctx = LocalContext.current
    AboutContent(
        versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE,
        onBack = onBack, onSelfCheck = onSelfCheck,
        onOpen = { url ->
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {
                Toast.makeText(ctx, ctx.getString(R.string.no_browser), Toast.LENGTH_SHORT).show()
            }
        },
    )
}

@Composable
fun AboutContent(versionName: String, versionCode: Int, onBack: () -> Unit, onSelfCheck: () -> Unit, onOpen: (String) -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.back)) }
        ScreenTitle(stringResource(R.string.about_title), subtitle = stringResource(R.string.about_version, versionName, versionCode))
        SectionCard(stringResource(R.string.about_updates)) {
            Text(stringResource(R.string.about_updates_body))
            Button(onClick = { onOpen(AppLinks.RELEASES) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Check for updates" }) {
                Text(stringResource(R.string.about_check_updates))
            }
            Disclaimer(stringResource(R.string.about_updates_note))
        }
        SectionCard(stringResource(R.string.about_privacy)) {
            Text(stringResource(R.string.about_privacy_body))
            OutlinedButton(onClick = { onOpen(AppLinks.PRIVACY) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.about_read_privacy)) }
        }
        SectionCard(stringResource(R.string.about_licences)) {
            licences.forEach { (name, lic) ->
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(lic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Disclaimer(stringResource(R.string.about_art))
        }
        SectionCard(stringResource(R.string.about_help)) {
            OutlinedButton(onClick = onSelfCheck, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.selfcheck_title)) }
            OutlinedButton(onClick = { onOpen(AppLinks.REPO) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.about_source)) }
        }
    }
}
