@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.domain.AdaptiveGoal
import com.walkbuddy.domain.Copy
import com.walkbuddy.domain.Format
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.components.BarChart
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.StatLine

private val DAY_LABELS = listOf("M", "T", "W", "T", "F", "S", "S")

@Composable
fun WeeklyScreen(vm: AppViewModel) {
    val r by vm.weekly.collectAsStateWithLifecycle()
    val bars by vm.weekBars.collectAsStateWithLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your week", style = MaterialTheme.typography.titleLarge)
        if (r.daysWithData == 0 && r.walkCount == 0) {
            EmptyState("No steps yet this week", "Carry your phone and take a walk. Your first report builds itself as the days go by.")
            return@Column
        }
        SectionCard("Steps") {
            BarChart(
                values = bars.map { it.second.toFloat() },
                labels = bars.map { DAY_LABELS[AdaptiveGoal.weekdayOf(it.first) - 1] },
                description = "Verified steps per day this week: " + bars.joinToString(", ") { "${it.second}" },
                highlight = bars.lastIndex,
            )
            StatLine("Total", "%,d".format(r.totalSteps))
            StatLine("Average on days with data", "%,d".format(r.avgStepsPerDay))
            StatLine("Distance", Format.distance(r.distanceM))
            Text(r.trendText + (r.trendPct?.let { " (${if (it >= 0) "+" else ""}$it%)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard("Active minutes") {
            LinearProgressIndicator(
                progress = { r.guidance.fraction.toFloat() },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${r.activeEquivMin} of 150 active minutes" },
            )
            Text("${r.activeEquivMin} of 150 minutes", style = MaterialTheme.typography.titleMedium)
            Text(if (r.guidance.met) "You have reached the commonly cited weekly guidance." else "${r.guidance.remainingMin} minutes to the commonly cited weekly guidance.")
            Disclaimer(Copy.ACTIVE_GUIDANCE + " Brisk minutes count once, vigorous minutes count double.")
        }
        SectionCard("Together") {
            StatLine("Walks this week", "${r.walkCount}")
            Text(r.avgTogetherPct?.let { "Together score on shared walks: $it%" } ?: "No shared walks this week yet.")
            Text(r.bestDayPart?.let { "Your most active time of day: ${it.label.lowercase()}." } ?: "Take a few walks to see your best time of day.")
        }
        Disclaimer(Copy.WELLNESS)
    }
}
