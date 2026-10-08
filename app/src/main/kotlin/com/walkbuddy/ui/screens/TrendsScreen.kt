@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.domain.AdaptiveGoal
import com.walkbuddy.domain.Copy
import com.walkbuddy.domain.Hero
import com.walkbuddy.domain.HourlyHistogram
import com.walkbuddy.domain.MoodInsights
import com.walkbuddy.domain.Units
import com.walkbuddy.share.ShareCard
import com.walkbuddy.share.ShareSpec
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.TrendsUi
import com.walkbuddy.domain.WeeklyReport
import com.walkbuddy.ui.components.BarChart
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.components.HourlyChart
import com.walkbuddy.ui.components.MonthHeatmap
import com.walkbuddy.ui.components.MoodDialog
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.ScreenTitle
import com.walkbuddy.ui.components.WbProgress
import com.walkbuddy.ui.components.StatLine
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_LABELS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
fun TrendsScreen(vm: AppViewModel, onBadges: () -> Unit, onRecap: () -> Unit) {
    val r by vm.weekly.collectAsStateWithLifecycle()
    val bars by vm.weekBars.collectAsStateWithLifecycle()
    val trends by vm.trends.collectAsStateWithLifecycle()
    val badges by vm.badges.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
    var moodDialog by remember { mutableStateOf(false) }

    TrendsContent(
        r = r, bars = bars, t = trends, badgeText = "${badges.count { it.earned }}/${badges.size}", is24 = is24,
        onBadges = onBadges, onRecap = onRecap,
        onShareWeek = { t ->
            ShareCard.share(
                ctx,
                ShareSpec("OUR WEEK", t.ourWeek.headlineValue, t.ourWeek.headlineLabel, r.avgTogetherPct?.div(100f) ?: 0f, t.ourWeek.lines.take(4)),
            )
        },
        onShiftMonth = { vm.shiftMonth(it) },
        onLogMood = { moodDialog = true },
    )

    if (moodDialog) MoodDialog(onSave = { m, n -> vm.addMood(m, n, null) }, onDismiss = { moodDialog = false })
}

@Composable
fun TrendsContent(
    r: WeeklyReport,
    bars: List<Pair<Long, Int>>,
    t: TrendsUi?,
    badgeText: String,
    is24: Boolean,
    onBadges: () -> Unit,
    onRecap: () -> Unit,
    onShareWeek: (TrendsUi) -> Unit,
    onShiftMonth: (Int) -> Unit,
    onLogMood: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("Trends", subtitle = "Your week, your best hour, your month")
        if (t == null) {
            EmptyState("Loading", "Putting your trends together.")
            return@Column
        }
        val unit = t.unit

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRecap, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Weekly recap") }
            OutlinedButton(onClick = onBadges, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Badges $badgeText")
            }
        }

        if (r.daysWithData == 0 && r.walkCount == 0) {
            EmptyState("No steps yet this week", "Carry your phone and take a walk. Your first report builds itself as the days go by.")
        } else {
            SectionCard("This week") {
                BarChart(
                    values = bars.map { it.second.toFloat() },
                    labels = bars.map { DAY_LABELS[AdaptiveGoal.weekdayOf(it.first) - 1] },
                    description = "Verified steps per day this week: " + bars.joinToString(", ") { "${it.second}" },
                    highlight = bars.lastIndex,
                )
                StatLine("Total", Hero.thousands(r.totalSteps))
                StatLine("Average on days with data", Hero.thousands(r.avgStepsPerDay))
                StatLine("Distance", Units.longDistance(r.distanceM, unit))
                Text(r.trendText + (r.trendPct?.let { " (${if (it >= 0) "+" else ""}$it%)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionCard("Our week") {
            Text(t.ourWeek.headlineValue, style = com.walkbuddy.ui.theme.NumberStyle.copy(fontSize = 44.sp, lineHeight = 48.sp), color = MaterialTheme.colorScheme.secondary)
            Text(t.ourWeek.headlineLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { t.ourWeek.lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) } }
            OutlinedButton(
                onClick = { onShareWeek(t) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Share our week") }
            Disclaimer("Stats only. No map, no places.")
        }

        SectionCard("Your best walking hour") {
            Text(HourlyHistogram.bestText(t.hourly, is24), style = MaterialTheme.typography.titleMedium)
            HourlyChart(t.hourly, is24)
            Disclaimer("Average steps in each hour over the last 30 days with data. Based on when your phone counted them.")
        }

        SectionCard(t.monthTitle) {
            MonthHeatmap(
                model = t.month, monthLabel = t.monthTitle,
                dayLabel = { LocalDate.ofEpochDay(it).format(DAY_FMT) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onShiftMonth(-1) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Earlier") }
                Text("${t.month.goalDays} goal days", style = MaterialTheme.typography.labelLarge)
                TextButton(enabled = t.canGoForward, onClick = { onShiftMonth(1) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Later") }
            }
            Disclaimer("Warmer colours mean closer to, or past, that day's goal.")
        }

        SectionCard("How walks feel") {
            Text(t.moodInsight.text, style = MaterialTheme.typography.bodyLarge)
            if (!t.moodInsight.enoughData) {
                WbProgress(
                    (t.moodInsight.pairedDays / MoodInsights.MIN_DAYS.toFloat()).coerceIn(0f, 1f),
                    Modifier.semantics { contentDescription = "${t.moodInsight.pairedDays} of ${MoodInsights.MIN_DAYS} days with a check-in" },
                )
            }
            t.recentMoods.forEach { m ->
                val mood = com.walkbuddy.domain.Mood.fromScore(m.mood)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(mood?.emoji ?: "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = mood?.label ?: "" })
                    Column(Modifier.weight(1f)) {
                        Text(LocalDate.ofEpochDay(m.epochDay).format(DAY_FMT), style = MaterialTheme.typography.labelLarge)
                        if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            OutlinedButton(onClick = onLogMood, modifier = Modifier.heightIn(min = 48.dp)) { Text("Log how I feel") }
            Disclaimer(MoodInsights.CAVEAT)
        }

        SectionCard("Active minutes") {
            WbProgress(r.guidance.fraction.toFloat(), Modifier.semantics { contentDescription = "${r.activeEquivMin} of 150 active minutes" })
            Text("${r.activeEquivMin} of 150 minutes", style = MaterialTheme.typography.titleMedium)
            Text(if (r.guidance.met) "You have reached the commonly cited weekly guidance." else "${r.guidance.remainingMin} minutes to the commonly cited weekly guidance.")
            Disclaimer(Copy.ACTIVE_GUIDANCE + " Brisk minutes count once, vigorous minutes count double.")
        }
        Disclaimer(Copy.WELLNESS)
    }
}
