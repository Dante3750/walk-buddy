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
import com.walkbuddy.domain.ProfileCheck
import com.walkbuddy.domain.Sex
import com.walkbuddy.health.HealthBridges
import com.walkbuddy.notify.PeriodicSampler
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.ToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings
    if (s == null) {
        EmptyState("Loading", "One moment.")
        return
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        ProfileSection(vm, s)
        GoalSection(vm, s)
        WalkSection(vm, s)
        ServerSection(vm, s)
        ExtrasSection(vm, s)
        HealthSection(vm, s)
        DataSection(vm)
        SectionCard("About") {
            Text(Copy.WELLNESS)
            Text(Copy.SHARE_LOCATION)
            Text(Copy.LOCAL_ONLY)
            Disclaimer("Walk Buddy has no accounts, no analytics, no ads, and never uses the microphone or camera.")
        }
    }
}

@Composable
private fun ProfileSection(vm: AppViewModel, s: Settings) {
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
                vm.saveSettings {
                    setName(name.trim())
                    setBody(h, w, sex)
                }
            },
        ) { Text("Save profile") }
    }
}

@Composable
private fun GoalSection(vm: AppViewModel, s: Settings) {
    var fixed by remember(s.fixedGoal) { mutableStateOf(s.fixedGoal.toString()) }
    val ctx = LocalContext.current
    SectionCard("Daily goal") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s.goalMode == GoalMode.Adaptive, onClick = { vm.saveSettings { setGoal(GoalMode.Adaptive, s.fixedGoal) } })
            Text("Adaptive: based on your last two weeks, a little higher, never extreme")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s.goalMode == GoalMode.Fixed, onClick = { vm.saveSettings { setGoal(GoalMode.Fixed, s.fixedGoal) } })
            Text("Fixed number")
        }
        if (s.goalMode == GoalMode.Fixed) {
            OutlinedTextField(value = fixed, onValueChange = { fixed = it.filter(Char::isDigit).take(5) }, label = { Text("Steps per day") }, singleLine = true)
            TextButton(onClick = { vm.saveSettings { setGoal(GoalMode.Fixed, (fixed.toIntOrNull() ?: 6000).coerceIn(500, 50_000)) } }) { Text("Save goal") }
        }
        Text("Rest days (the goal pauses and streaks are not affected)", style = MaterialTheme.typography.bodyMedium)
        val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, label ->
                val day = i + 1
                FilterChip(
                    selected = day in s.restWeekdays,
                    onClick = { vm.saveSettings { setRestWeekdays(if (day in s.restWeekdays) s.restWeekdays - day else s.restWeekdays + day) } },
                    label = { Text(label) },
                )
            }
        }
        ToggleRow("Sitting-break reminders", "About an hour of sitting, then a 2 minute stand or stroll. Checked every ~15 minutes, quiet at night.", s.sittingReminders) { on ->
            vm.saveSettings { setSitting(on) }
            if (on) PeriodicSampler.schedule(ctx)
        }
    }
}

@Composable
private fun WalkSection(vm: AppViewModel, s: Settings) {
    var radius by remember(s.radiusM) { mutableStateOf(s.radiusM.toFloat()) }
    SectionCard("Walking together") {
        Text("Together radius: ${radius.toInt()} m", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = radius, onValueChange = { radius = it }, valueRange = 20f..200f,
            onValueChangeFinished = { vm.saveSettings { setRadius(radius.toInt()) } },
        )
        Disclaimer("The together score is the share of the walk spent within this distance of each other.")
        ToggleRow("Quiet mode by default", "Start every walk with nudges muted", s.quietByDefault) { on -> vm.saveSettings { setQuietDefault(on) } }
        ToggleRow("Pace-sync mode", "The faster partner gets the gentle nudge instead of the one behind", s.paceSync) { on -> vm.saveSettings { setPaceSync(on) } }
        ToggleRow("'Thinking of you' ping", "A tiny haptic hello to your buddy during a walk. Rate limited.", s.pingEnabled) { on -> vm.saveSettings { setPing(on) } }
    }
}

@Composable
private fun ServerSection(vm: AppViewModel, s: Settings) {
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
            Button(onClick = { vm.saveSettings { setServer(url) } }) { Text("Save") }
            OutlinedButton(
                enabled = url.isNotBlank() && !testing,
                onClick = {
                    testing = true; result = null
                    vm.testServer(url.trim()) { err ->
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
private fun ExtrasSection(vm: AppViewModel, s: Settings) {
    val ctx = LocalContext.current
    SectionCard("Optional extras") {
        ToggleRow("Show calorie estimate", "A rough range, clearly labeled. Hidden by default.", s.caloriesEnabled) { on -> vm.saveSettings { setCalories(on) } }
        if (s.caloriesEnabled) {
            Text(
                s.calibratedStepLengthM?.let { "Calibrated step length: %.2f m".format(it) } ?: "Step length comes from your height. You can calibrate it from a GPS walk.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { vm.calibrateStepLength { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }) { Text("Calibrate from my last walk") }
        }
        ToggleRow("Show refuel ideas", "General, balanced ideas. Not a diet plan.", s.foodEnabled) { on -> vm.saveSettings { setFood(on) } }
        if (s.foodEnabled) {
            Text("Food preference", style = MaterialTheme.typography.bodyMedium)
            Diet.values().forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.diet == d, onClick = { vm.saveSettings { setDiet(d) } })
                    Text(d.label)
                }
            }
            Disclaimer(Copy.FOOD_DISCLAIMER)
        }
    }
}

@Composable
private fun HealthSection(vm: AppViewModel, s: Settings) {
    val ctx = LocalContext.current
    var available by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.healthAvailable { available = it } }
    SectionCard("Health Connect (optional)") {
        if (!HealthBridges.compiledIn) {
            Text("Not included in this build. Build with -PhealthConnect=true to add it. Walk Buddy works fully without it.", style = MaterialTheme.typography.bodyMedium)
        } else {
            ToggleRow("Save walks to Health Connect", if (available) "Writes each finished walk as an exercise session." else "Health Connect is not available on this phone.", s.healthConnectOn && available) { on ->
                vm.saveSettings { setHealthConnect(on) }
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
private fun DataSection(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val text = vm.exportText()
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
            }
            Toast.makeText(ctx, if (ok) "Exported" else "Could not write the file", Toast.LENGTH_SHORT).show()
        }
    }
    SectionCard("Your data") {
        Text("Everything is stored on this phone. Export it any time, or erase it all.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { export.launch("walk-buddy-export.csv") }, modifier = Modifier.fillMaxWidth()) { Text("Export my data (CSV)") }
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
                    vm.deleteEverything { Toast.makeText(ctx, "All data deleted", Toast.LENGTH_SHORT).show() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
