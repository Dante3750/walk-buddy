@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.domain.Copy
import com.walkbuddy.domain.FoodItem
import com.walkbuddy.data.Settings
import com.walkbuddy.data.SettingsStore
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.FuelUi
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.ToggleRow

/** Monthly "fuel" screen: an optional calorie ESTIMATE range and optional refuel ideas. Both can be hidden in Settings. */
@Composable
fun FuelScreen(vm: AppViewModel) {
    val fuel by vm.fuel.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hot by vm.hotDay.collectAsStateWithLifecycle()
    FuelContent(settings, fuel, hot, onSave = { vm.saveSettings(it) }, onHot = { onHot(it) })
}

@Composable
fun FuelContent(
    s: Settings?,
    f: FuelUi?,
    hot: Boolean,
    onSave: (suspend SettingsStore.() -> Unit) -> Unit,
    onHot: (Boolean) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("This month", style = MaterialTheme.typography.titleLarge)
        if (s == null || f == null) {
            EmptyState("Loading", "Putting your month together.")
            return@Column
        }
        if (!s.caloriesEnabled && !s.foodEnabled) {
            EmptyState(
                "Both extras are off",
                "Calorie estimates and refuel ideas are optional and hidden by default. Turn on only what you find useful.",
                action = {
                    Button(onClick = { onSave { setCalories(true) } }) { Text("Show calorie estimate") }
                    Button(onClick = { onSave { setFood(true) } }) { Text("Show refuel ideas") }
                },
            )
            Disclaimer(Copy.WELLNESS)
            return@Column
        }

        if (s.caloriesEnabled) {
            SectionCard("Walking energy (estimate)") {
                when {
                    !f.profileOk -> Text(Copy.NEUTRAL_PROFILE)
                    s.weightKg == null -> Text("Add your weight in Settings to see an estimate. It stays on this phone.")
                    f.calories == null -> Text("No walks with enough data yet this month.")
                    else -> {
                        Text("${f.calories.lowKcal} to ${f.calories.highKcal} kcal", style = MaterialTheme.typography.headlineMedium)
                        Text("Estimate for your walks in the last 30 days, shown as a range because real values vary by person.")
                    }
                }
                Disclaimer(Copy.CALORIE_NOTE)
                Button(onClick = { onSave { setCalories(false) } }) { Text("Hide calories") }
            }
        }

        if (s.foodEnabled) {
            SectionCard("Refuel ideas") {
                Text(f.ideas.headline, style = MaterialTheme.typography.titleMedium)
                f.ideas.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    ?: if (!f.profileOk) Text(Copy.NEUTRAL_PROFILE) else Text("A few more days of steps will make these a bit more fitting.")
                ToggleRow("Hot day", "Adds a salty drink idea", hot) { onHot(it) }
                IdeaGroup("Hydration first", f.ideas.hydration)
                IdeaGroup("After a longer or brisker walk", f.ideas.afterWalk)
                IdeaGroup("Easy snacks", f.ideas.snacks)
                Disclaimer(f.ideas.disclaimer)
                Button(onClick = { onSave { setFood(false) } }) { Text("Hide refuel ideas") }
            }
        }
    }
}

@Composable
private fun IdeaGroup(title: String, items: List<FoodItem>) {
    if (items.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleMedium)
    items.forEach {
        Column(Modifier.fillMaxWidth()) {
            Text(it.name)
            if (it.note.isNotBlank()) Text(it.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
