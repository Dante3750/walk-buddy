package com.walkbuddy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.R
import com.walkbuddy.data.Clock
import com.walkbuddy.domain.ChallengeKind
import com.walkbuddy.domain.ChallengePeriod
import com.walkbuddy.domain.ChallengeProgress
import com.walkbuddy.domain.ChallengeState
import com.walkbuddy.domain.ChallengeTemplate
import com.walkbuddy.domain.Challenges
import com.walkbuddy.domain.CoupleStreakInfo
import com.walkbuddy.domain.CoupleStreakMessage
import com.walkbuddy.domain.UnitSystem
import com.walkbuddy.domain.Units
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.ScreenTitle
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.WbProgress
import com.walkbuddy.ui.theme.WbTheme

class ChallengeActions(
    val onStart: (String) -> Unit = {},
    val onDelete: (Long) -> Unit = {},
    val onBack: () -> Unit = {},
)

@Composable
fun ChallengesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val progress by vm.challengeProgress.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    ChallengesContent(
        progress, settings?.unitSystem ?: UnitSystem.Metric, Clock.today(),
        ChallengeActions(onStart = { vm.startChallenge(it) }, onDelete = { vm.deleteChallenge(it) }, onBack = onBack),
    )
}

fun challengeTitleText(ctx: android.content.Context, t: ChallengeTemplate, unit: UnitSystem): String {
    val period = ctx.getString(if (t.period == ChallengePeriod.Week) R.string.ch_week else R.string.ch_month)
    return when (t.kind) {
        ChallengeKind.DistanceKm -> ctx.getString(R.string.ch_title_distance, Units.distance(t.target * 1000.0, unit), period)
        ChallengeKind.TogetherWalks -> ctx.resources.getQuantityString(R.plurals.ch_title_walks, t.target, t.target, period)
        ChallengeKind.GroupSteps -> ctx.getString(R.string.ch_title_steps, "%,d".format(t.target), period)
    }
}

@Composable
fun challengeTitle(t: ChallengeTemplate, unit: UnitSystem): String = challengeTitleText(LocalContext.current, t, unit)

@Composable
private fun progressLine(p: ChallengeProgress, unit: UnitSystem): String = when (p.template.kind) {
    ChallengeKind.DistanceKm -> stringResource(R.string.ch_progress, Units.distance(p.current * 1000.0, unit), Units.distance(p.template.target * 1000.0, unit))
    ChallengeKind.TogetherWalks -> stringResource(R.string.ch_progress, "${p.current.toInt()}", "${p.template.target}")
    ChallengeKind.GroupSteps -> stringResource(R.string.ch_progress, "%,d".format(p.current.toLong()), "%,d".format(p.template.target))
}

/** A static burst of dots for a finished challenge. It never moves, so Reduce motion needs no special case. */
@Composable
fun CelebrationBurst(modifier: Modifier = Modifier) {
    val colors = listOf(Color(0xFFE2603F), Color(0xFFF0A02C), Color(0xFF5E9E6E), Color(0xFF3A86D1), Color(0xFF9055D8), Color(0xFFD24E9E))
    Canvas(modifier.fillMaxWidth().height(36.dp)) {
        val n = 18
        for (i in 0 until n) {
            val x = size.width * (i + 0.5f) / n
            val y = size.height * (0.25f + 0.5f * ((i * 7) % 5) / 4f)
            drawCircle(colors[i % colors.size], (3f + (i % 3)) * density, Offset(x, y))
        }
    }
}

@Composable
fun ChallengesContent(progress: List<ChallengeProgress>, unit: UnitSystem, today: Long, a: ChallengeActions) {
    BackHandler(onBack = a.onBack)
    val instances = progress.map { it.instance }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = a.onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.back)) }
        ScreenTitle(stringResource(R.string.ch_screen_title), subtitle = stringResource(R.string.ch_screen_sub))
        if (progress.isEmpty()) {
            SectionCard(null) { Text(stringResource(R.string.ch_none)) }
        }
        progress.forEach { p ->
            SectionCard(null) {
                if (p.state == ChallengeState.Completed) CelebrationBurst()
                Text(challengeTitle(p.template, unit), style = MaterialTheme.typography.titleMedium)
                val line = progressLine(p, unit)
                WbProgress(p.fraction, Modifier.semantics { contentDescription = line })
                Text(line, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (p.state) {
                        ChallengeState.Completed -> stringResource(R.string.ch_done)
                        ChallengeState.Missed -> stringResource(R.string.ch_missed)
                        ChallengeState.Active -> LocalContext.current.resources.getQuantityString(R.plurals.ch_days_left, p.daysLeft, p.daysLeft)
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p.state != ChallengeState.Active) {
                    TextButton(onClick = { a.onDelete(p.instance.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ch_remove)) }
                }
            }
        }
        SectionCard(stringResource(R.string.ch_start_title)) {
            Challenges.templates.forEach { t ->
                val can = Challenges.canStart(t.id, instances, today)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(challengeTitle(t, unit), modifier = Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { a.onStart(t.id) }, enabled = can, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (can) R.string.ch_start else R.string.ch_started)) }
                }
            }
            Disclaimer(stringResource(R.string.ch_note))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------------
// Couple streak

@Composable
fun coupleStreakText(info: CoupleStreakInfo): String = stringResource(
    when (info.message) {
        CoupleStreakMessage.NoWalksYet -> R.string.cs_none
        CoupleStreakMessage.WalkedToday -> R.string.cs_walked
        CoupleStreakMessage.RestDay -> R.string.cs_rest
        CoupleStreakMessage.TokenUsedYesterday -> R.string.cs_token
        CoupleStreakMessage.StartedAgain -> R.string.cs_again
        CoupleStreakMessage.KeepGoing -> R.string.cs_keep
        CoupleStreakMessage.GentleEvening -> R.string.cs_evening
    },
)

@Composable
fun CoupleStreakCard(info: CoupleStreakInfo, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    SectionCard(stringResource(R.string.cs_title), modifier) {
        if (info.current > 0) {
            Text(res.getQuantityString(R.plurals.cs_days, info.current, info.current), style = MaterialTheme.typography.headlineSmall)
        }
        Text(coupleStreakText(info), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.cs_tokens, info.tokens, res.getQuantityString(R.plurals.cs_days, info.longest, info.longest)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
