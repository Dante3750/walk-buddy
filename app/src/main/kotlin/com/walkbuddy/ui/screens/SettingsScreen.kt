@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.data.GoalMode
import com.walkbuddy.data.Settings
import com.walkbuddy.domain.Copy
import com.walkbuddy.domain.Diet
import com.walkbuddy.domain.LocationPrecision
import com.walkbuddy.domain.ProfileCheck
import com.walkbuddy.domain.Sex
import com.walkbuddy.domain.UnitSystem
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.walkbuddy.health.HealthBridges
import com.walkbuddy.notify.PeriodicSampler
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.ScreenTitle
import com.walkbuddy.ui.components.ToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the settings screen can do, as callbacks, so it renders without a ViewModel. */
class SettingsActions(
    val save: (suspend com.walkbuddy.data.SettingsStore.() -> Unit) -> Unit = {},
    val setDemoMode: (Boolean) -> Unit = {},
    val testServer: (String, (String?) -> Unit) -> Unit = { _, _ -> },
    val calibrate: ((String) -> Unit) -> Unit = {},
    val healthAvailable: ((Boolean) -> Unit) -> Unit = {},
    val onExport: () -> Unit = {},
    val deleteEverything: (() -> Unit) -> Unit = {},
    val routes: () -> List<com.walkbuddy.data.RouteInfo> = { emptyList() },
    val onShareRoute: (String) -> Unit = {},
    val onDeleteRoute: (String) -> Unit = {},
)

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val text = vm.exportText()
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
            }
            Toast.makeText(ctx, if (ok) "Exported" else "Could not write the file", Toast.LENGTH_SHORT).show()
        }
    }
    val actions = SettingsActions(
        save = { vm.saveSettings(it) },
        setDemoMode = { vm.setDemoMode(it) },
        testServer = { url, done -> vm.testServer(url, done) },
        calibrate = { done -> vm.calibrateStepLength(done) },
        healthAvailable = { done -> vm.healthAvailable(done) },
        onExport = { export.launch("walk-buddy-export.csv") },
        deleteEverything = { done -> vm.deleteEverything(done) },
        routes = { vm.routes() },
        onShareRoute = { name ->
            val f = vm.routeFile(name)
            if (f != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                val send = Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { ctx.startActivity(Intent.createChooser(send, "Share route")) } catch (_: ActivityNotFoundException) {
                    Toast.makeText(ctx, "No app to share with", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onDeleteRoute = { vm.deleteRoute(it) },
    )
    SettingsContent(settings, actions)
}

@Composable
fun SettingsContent(s: Settings?, a: SettingsActions) {
    if (s == null) {
        EmptyState("Loading", "One moment.")
        return
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("Settings")
        AppearanceSection(a, s)
        ProfileSection(a, s)
        GoalSection(a, s)
        StepLengthSection(a, s)
        QuietHoursSection(a, s)
        WalkSection(a, s)
        MapSection(a, s)
        ServerSection(a, s)
        ExtrasSection(a, s)
        HealthSection(a, s)
        DemoSection(a, s)
        DataSection(a)
        SectionCard("About") {
            Text(Copy.WELLNESS)
            Text(Copy.SHARE_LOCATION)
            Text(Copy.LOCAL_ONLY)
            Disclaimer("Walk Buddy has no accounts, no analytics, no ads, and never uses the microphone. The camera is used only on the QR scan screen, and nothing it sees is saved.")
        }
    }
}

@Composable
private fun ProfileSection(a: SettingsActions, s: Settings) {
    var name by remember(s.displayName) { mutableStateOf(s.displayName) }
    var height by remember(s.heightCm) { mutableStateOf(s.heightCm?.let { "%.0f".format(it) } ?: "") }
    var weight by remember(s.weightKg) { mutableStateOf(s.weightKg?.let { "%.1f".format(it) } ?: "") }
    var sex by remember(s.sex) { mutableStateOf(s.sex) }
    val h = height.toDoubleOrNull()
    val w = weight.toDoubleOrNull()
    val hBad = height.isNotBlank() && (h == null || !ProfileCheck.heightOk(h))
    val wBad = weight.isNotBlank() && (w == null || !ProfileCheck.weightOk(w))
    SectionCard("Profile (optional, stays on this phone)") {
        OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, label = { Text("Name shown to buddies") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = height, onValueChange = { height = it.take(5) }, label = { Text("Height (cm)") }, singleLine = true, isError = hBad,
            supportingText = { if (hBad) Text("Enter a realistic height, or leave empty") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = weight, onValueChange = { weight = it.take(6) }, label = { Text("Weight (kg)") }, singleLine = true, isError = wBad,
            supportingText = { if (wBad) Text("Enter a realistic weight, or leave empty") }, modifier = Modifier.fillMaxWidth(),
        )
        Text("Used only to estimate step length and the optional calorie range.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sex.values().forEach { opt ->
                FilterChip(selected = sex == opt, onClick = { sex = opt }, label = { Text(if (opt == Sex.Unspecified) "Prefer not to say" else opt.name) })
            }
        }
        Button(
            enabled = !hBad && !wBad,
            onClick = {
                a.save {
                    setName(name.trim())
                    setBody(h, w, sex)
                }
            },
        ) { Text("Save profile") }
    }
}

@Composable
private fun GoalSection(a: SettingsActions, s: Settings) {
    var fixed by remember(s.fixedGoal) { mutableStateOf(s.fixedGoal.toString()) }
    val ctx = LocalContext.current
    SectionCard("Daily goal") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s.goalMode == GoalMode.Adaptive, onClick = { a.save { setGoal(GoalMode.Adaptive, s.fixedGoal) } })
            Text("Adaptive: based on your last two weeks, a little higher, never extreme")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s.goalMode == GoalMode.Fixed, onClick = { a.save { setGoal(GoalMode.Fixed, s.fixedGoal) } })
            Text("Fixed number")
        }
        if (s.goalMode == GoalMode.Fixed) {
            OutlinedTextField(value = fixed, onValueChange = { fixed = it.filter(Char::isDigit).take(5) }, label = { Text("Steps per day") }, singleLine = true)
            TextButton(onClick = { a.save { setGoal(GoalMode.Fixed, (fixed.toIntOrNull() ?: 6000).coerceIn(500, 50_000)) } }) { Text("Save goal") }
        }
        Text("Rest days (the goal pauses and streaks are not affected)", style = MaterialTheme.typography.bodyMedium)
        val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, label ->
                val day = i + 1
                FilterChip(
                    selected = day in s.restWeekdays,
                    onClick = { a.save { setRestWeekdays(if (day in s.restWeekdays) s.restWeekdays - day else s.restWeekdays + day) } },
                    label = { Text(label) },
                )
            }
        }
        ToggleRow("Sitting-break reminders", "About an hour of sitting, then a 2 minute stand or stroll. Checked every ~15 minutes, quiet at night.", s.sittingReminders) { on ->
            a.save { setSitting(on) }
            if (on) PeriodicSampler.schedule(ctx)
        }
    }
}

@Composable
private fun WalkSection(a: SettingsActions, s: Settings) {
    var radius by remember(s.radiusM) { mutableStateOf(s.radiusM.toFloat()) }
    SectionCard("Walking together") {
        Text("Together radius: ${radius.toInt()} m", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = radius, onValueChange = { radius = it }, valueRange = 20f..200f,
            onValueChangeFinished = { a.save { setRadius(radius.toInt()) } },
        )
        Disclaimer("The together score is the share of the walk spent within this distance of each other.")
        ToggleRow("Quiet mode by default", "Start every walk with nudges muted", s.quietByDefault) { on -> a.save { setQuietDefault(on) } }
        ToggleRow("Pace-sync mode", "The faster partner gets the gentle nudge instead of the one behind", s.paceSync) { on -> a.save { setPaceSync(on) } }
        ToggleRow("'Thinking of you' ping", "A tiny haptic hello to your buddy during a walk. Rate limited.", s.pingEnabled) { on -> a.save { setPing(on) } }
    }
}

@Composable
private fun ServerSection(a: SettingsActions, s: Settings) {
    var url by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    SectionCard("Signaling server") {
        Text("Only used to introduce your phones to each other. Steps and locations go directly between phones and never pass through it.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = url, onValueChange = { url = it.take(200) }, label = { Text("wss://your-server.example") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { a.save { setServer(url) } }) { Text("Save") }
            OutlinedButton(
                enabled = url.isNotBlank() && !testing,
                onClick = {
                    testing = true; result = null
                    a.testServer(url.trim()) { err ->
                        // Called from a background thread.
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            testing = false
                            result = err ?: "Connected. The server answered."
                        }
                    }
                },
            ) { Text(if (testing) "Testing..." else "Test connection") }
        }
        result?.let { Text(it, color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        Disclaimer("See server/README.md in the project to run your own in a minute.")
    }
}

@Composable
private fun ExtrasSection(a: SettingsActions, s: Settings) {
    val ctx = LocalContext.current
    SectionCard("Optional extras") {
        ToggleRow("Show calorie estimate", "A rough range, clearly labeled. Hidden by default.", s.caloriesEnabled) { on -> a.save { setCalories(on) } }
        if (s.caloriesEnabled) {
            Text(
                s.calibratedStepLengthM?.let { "Calibrated step length: %.2f m".format(it) } ?: "Step length comes from your height. You can calibrate it from a GPS walk.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { a.calibrate { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }) { Text("Calibrate from my last walk") }
        }
        ToggleRow("Show refuel ideas", "General, balanced ideas. Not a diet plan.", s.foodEnabled) { on -> a.save { setFood(on) } }
        if (s.foodEnabled) {
            Text("Food preference", style = MaterialTheme.typography.bodyMedium)
            Diet.values().forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.diet == d, onClick = { a.save { setDiet(d) } })
                    Text(d.label)
                }
            }
            Disclaimer(Copy.FOOD_DISCLAIMER)
        }
    }
}

@Composable
private fun HealthSection(a: SettingsActions, s: Settings) {
    val ctx = LocalContext.current
    var available by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { a.healthAvailable { available = it } }
    SectionCard("Health Connect (optional)") {
        if (!HealthBridges.compiledIn) {
            Text("Not included in this build. Build with -PhealthConnect=true to add it. Walk Buddy works fully without it.", style = MaterialTheme.typography.bodyMedium)
        } else {
            ToggleRow("Save walks to Health Connect", if (available) "Writes each finished walk as an exercise session." else "Health Connect is not available on this phone.", s.healthConnectOn && available) { on ->
                a.save { setHealthConnect(on) }
            }
            OutlinedButton(onClick = {
                try {
                    ctx.startActivity(Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME, ctx.packageName))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(ctx, "Open Health Connect from Android settings", Toast.LENGTH_LONG).show()
                }
            }) { Text("Manage Health Connect access") }
        }
    }
}

@Composable
private fun DataSection(a: SettingsActions) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    SectionCard("Your data") {
        Text("Everything is stored on this phone. Export it any time, or erase it all.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = a.onExport, modifier = Modifier.fillMaxWidth()) { Text("Export my data (CSV)") }
        Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete all my data") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete everything?") },
            text = { Text("This erases your steps, walks, dates, spots and settings from this phone. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    a.deleteEverything { Toast.makeText(ctx, "All data deleted", Toast.LENGTH_SHORT).show() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AppearanceSection(a: SettingsActions, s: Settings) {
    SectionCard("Look and feel") {
        Text("Distances in", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.unitSystem == UnitSystem.Metric, onClick = { a.save { setUnits(UnitSystem.Metric) } }, label = { Text("Kilometres") }, modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(selected = s.unitSystem == UnitSystem.Imperial, onClick = { a.save { setUnits(UnitSystem.Imperial) } }, label = { Text("Miles") }, modifier = Modifier.heightIn(min = 48.dp))
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            ToggleRow("Use my wallpaper colours", "Material You colours instead of Walk Buddy's dusk palette.", s.dynamicColor) { on -> a.save { setDynamicColor(on) } }
        }
        ToggleRow("Reduce motion", "Numbers jump instead of counting up, and there is no confetti or flicker. Also follows Android's animation setting.", s.reduceMotion) { on -> a.save { setReduceMotion(on) } }
        ToggleRow("Haptics", "Small vibrations for pings, reactions and reaching your goal.", s.haptics) { on -> a.save { setHaptics(on) } }
    }
}

@Composable
private fun StepLengthSection(a: SettingsActions, s: Settings) {
    val ctx = LocalContext.current
    var cm by remember(s.stepLengthM) { mutableStateOf((s.stepLengthM * 100).toFloat().coerceIn(40f, 120f)) }
    SectionCard("Step length") {
        Text("Used for distance when you are not on a GPS walk: ${cm.toInt()} cm", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = cm, onValueChange = { cm = it }, valueRange = 40f..120f,
            modifier = Modifier.semantics { contentDescription = "Step length in centimetres" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { a.save { setStepLength(cm.toInt() / 100.0) } }) { Text("Save") }
            OutlinedButton(onClick = { a.calibrate { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }) { Text("Calibrate from my last walk") }
        }
        if (s.calibratedStepLengthM != null) {
            TextButton(onClick = { a.save { setStepLength(null) } }) { Text("Go back to the estimate from my height") }
        }
        Disclaimer("A typical step is 60 to 80 cm. A walk of at least 120 m with GPS gives the best calibration.")
    }
}

@Composable
private fun QuietHoursSection(a: SettingsActions, s: Settings) {
    var from by remember(s.quietFromHour) { mutableStateOf(s.quietFromHour.toFloat()) }
    var to by remember(s.quietToHour) { mutableStateOf(s.quietToHour.toFloat()) }
    SectionCard("Quiet hours") {
        ToggleRow("Silence reminders and nudges", "Walk date and anniversary reminders, sitting breaks and walk nudges wait until the quiet hours end.", s.quietEnabled) { on ->
            a.save { setQuietHours(on, from.toInt(), to.toInt()) }
        }
        if (s.quietEnabled) {
            Text("From %02d:00".format(from.toInt()), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = from, onValueChange = { from = it }, valueRange = 0f..23f, steps = 22,
                onValueChangeFinished = { a.save { setQuietHours(true, from.toInt(), to.toInt()) } },
                modifier = Modifier.semantics { contentDescription = "Quiet hours start" },
            )
            Text("Until %02d:00".format(to.toInt()), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = to, onValueChange = { to = it }, valueRange = 0f..23f, steps = 22,
                onValueChangeFinished = { a.save { setQuietHours(true, from.toInt(), to.toInt()) } },
                modifier = Modifier.semantics { contentDescription = "Quiet hours end" },
            )
        }
    }
}

@Composable
private fun DemoSection(a: SettingsActions, s: Settings) {
    SectionCard("Demo mode") {
        ToggleRow("Show demo data", "Sample steps, badges and a pretend buddy named Sam. Works with no permissions and saves nothing. Your real data is untouched.", s.demoMode) { on -> a.setDemoMode(on) }
    }
}

@Composable
private fun MapSection(a: SettingsActions, s: Settings) {
    var routes by remember { mutableStateOf(a.routes()) }
    LaunchedEffect(s.saveRoutes) { routes = a.routes() }
    SectionCard("Maps and groups") {
        Text("Group walks: how precisely my location is shared", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocationPrecision.values().forEach { p ->
                FilterChip(selected = s.groupPrecision == p, onClick = { a.save { setGroupPrecision(p) } }, label = { Text(p.label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Disclaimer("A coarser setting snaps your position to a grid, so the group sees roughly where you are. You always see yourself exactly. You can change it again when you join.")
        ToggleRow(
            "Show street map tiles", "Off by default. The map works without them, drawing only the walkers and their trails.", s.mapTiles,
        ) { a.save { setMapTiles(it) } }
        Disclaimer("With tiles on, this phone fetches map pictures from OpenStreetMap for the area you are viewing, which tells that service roughly where you are looking. Map data (c) OpenStreetMap contributors.")
        ToggleRow(
            "Save my route after a walk", "Keeps your own track on this phone as a GPX file. Never anybody else's.", s.saveRoutes,
        ) { a.save { setSaveRoutes(it) } }
        if (routes.isNotEmpty()) {
            Text("Saved routes", style = MaterialTheme.typography.titleMedium)
            routes.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.label, Modifier.weight(1f))
                    TextButton(onClick = { a.onShareRoute(r.fileName) }, Modifier.heightIn(min = 48.dp)) { Text("Share") }
                    TextButton(onClick = { a.onDeleteRoute(r.fileName); routes = a.routes() }, Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                }
            }
            TextButton(onClick = { routes.forEach { a.onDeleteRoute(it.fileName) }; routes = a.routes() }, Modifier.heightIn(min = 48.dp)) { Text("Delete all saved routes") }
        } else {
            Disclaimer("No saved routes.")
        }
    }
}
