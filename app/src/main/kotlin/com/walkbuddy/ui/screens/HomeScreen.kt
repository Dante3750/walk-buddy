@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.data.Clock
import com.walkbuddy.domain.Copy
import com.walkbuddy.domain.GentleDay
import com.walkbuddy.domain.GoalCelebration
import com.walkbuddy.domain.Hero
import com.walkbuddy.domain.JoinLink
import com.walkbuddy.domain.Units
import com.walkbuddy.domain.WeeklyGuidance
import com.walkbuddy.share.ShareCard
import com.walkbuddy.share.ShareSpec
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.HomeUi
import com.walkbuddy.ui.PermState
import com.walkbuddy.ui.components.BuddyLeadRow
import com.walkbuddy.ui.components.ConfettiBurst
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.StatPill
import com.walkbuddy.ui.components.StepHero
import com.walkbuddy.ui.components.StreakCard
import com.walkbuddy.ui.components.VerifiedChip
import com.walkbuddy.ui.rememberPermState
import com.walkbuddy.ui.rememberPermissionRequester
import com.walkbuddy.ui.theme.WbTheme

private fun greeting(hour: Int, name: String): String {
    val g = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    return if (name.isBlank()) g else "$g, $name"
}

@Composable
fun HomeScreen(vm: AppViewModel, wide: Boolean) {
    val home by vm.home.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pendingJoin by vm.pendingJoin.collectAsStateWithLifecycle()
    val pendingAction by vm.pendingAction.collectAsStateWithLifecycle()
    val celebrated by vm.celebratedToday.collectAsStateWithLifecycle()
    val perms = rememberPermState()
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val motion = WbTheme.motion
    var joinDialog by remember { mutableStateOf(false) }
    var codeText by remember { mutableStateOf("") }
    var action by remember { mutableStateOf<(() -> Unit)?>(null) }
    var prevSteps by remember { mutableIntStateOf(0) }
    var burst by remember { mutableStateOf(false) }
    val askLocation = rememberPermissionRequester(perms) {
        // Whatever the answer, continue: a walk without location still counts steps, just no distance or buddy gap.
        action?.invoke()
        action = null
    }

    fun withLocation(run: () -> Unit) {
        if (perms.location) run() else { action = run; askLocation(PermState.LOCATION) }
    }

    LaunchedEffect(pendingAction) {
        if (pendingAction == "start_solo") {
            vm.pendingAction.value = null
            withLocation { vm.startLobby(null, solo = true) }
        }
    }

    val h = home
    // Goal celebration: confetti and a haptic once per day, never when reduce-motion is on (the message still appears).
    LaunchedEffect(h?.verifiedSteps, h?.goal) {
        if (h == null) return@LaunchedEffect
        if (GoalCelebration.shouldCelebrate(prevSteps, h.verifiedSteps, h.goal, if (celebrated) Clock.today() else null, Clock.today())) {
            if (!motion.reduceMotion) burst = true
            if (motion.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            vm.markCelebrated()
        }
        prevSteps = h.verifiedSteps
    }

    if (h == null) {
        EmptyState("Loading", "Getting today's steps ready.")
        return
    }

    val hero: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StepHero(steps = h.verifiedSteps, goal = h.goal, buddies = h.buddies, walking = h.walking)
                ConfettiBurst(active = burst, onDone = { burst = false }, modifier = Modifier.matchParentSize())
            }
            VerifiedChip(h.verifiedSteps, h.rawSteps)
            BuddyLeadRow(h.buddies, h.verifiedSteps)
            if (Hero.goalReached(h.verifiedSteps, h.goal)) {
                Text(
                    "You reached today's goal. Lovely.",
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center,
                )
            }
            val dist = Units.distanceAmount(h.distanceM, h.unit)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatPill("Distance", dist.value, dist.unit, Modifier.weight(1f))
                StatPill("Active", (if (h.activeIsEstimate) "~" else "") + h.activeMin, "min", Modifier.weight(1f))
                h.calories?.let { StatPill("Energy, estimate", "${(it.lowKcal + it.highKcal) / 2}", "kcal", Modifier.weight(1f)) }
            }
        }
    }

    val details: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (h.demo) DemoBanner(vm)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = h.gentle, onClick = { vm.setGentleToday(!h.gentle) },
                    label = { Text(if (h.gentle) "Gentle day is on" else "Gentle day") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                FilterChip(
                    selected = h.restDay, onClick = { vm.setRestToday(!h.restDay) },
                    label = { Text(if (h.restDay) "Rest day is on" else "Rest day") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                OutlinedButton(
                    onClick = {
                        ShareCard.share(
                            ctx,
                            ShareSpec(
                                kicker = if (Hero.goalReached(h.verifiedSteps, h.goal)) "GOAL REACHED" else "TODAY",
                                bigNumber = Hero.thousands(h.verifiedSteps), caption = "steps",
                                fraction = Hero.fraction(h.verifiedSteps, h.goal).toFloat(),
                                lines = listOf(
                                    "Goal ${Hero.thousands(h.goal)}",
                                    Units.distance(h.distanceM, h.unit) + "  /  " + (if (h.activeIsEstimate) "about " else "") + h.activeMin + " active min",
                                    if (h.streak.current > 1) "${h.streak.current} day streak" else "",
                                ).filter { it.isNotEmpty() },
                            ),
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Share today") }
            }
            if (h.gentle) Disclaimer("${GentleDay.COPY} Today's goal: ${Hero.thousands(h.goal)} steps.")
            if (h.restDay) Text("Today is a rest day. Moving is optional.", color = MaterialTheme.colorScheme.primary)

            StreakCard(h.flame)

            if (h.countdown != null) {
                SectionCard(null) {
                    h.daysTogether?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text(h.countdown, style = MaterialTheme.typography.bodyLarge)
                }
            }

            SectionCard("Active minutes this week") {
                LinearProgressIndicator(
                    progress = { h.guidance.fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp).semantics { contentDescription = "${h.guidance.equivMin} of 150 active minutes this week" },
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text("${h.guidance.equivMin} of ${WeeklyGuidance.TARGET_MIN} minutes", style = MaterialTheme.typography.titleMedium)
                Disclaimer(Copy.ACTIVE_GUIDANCE)
            }

            SectionCard("Walk") {
                Button(onClick = { withLocation { vm.startLobby(null) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Start a walk together") }
                OutlinedButton(onClick = { joinDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Join with a code or link") }
                OutlinedButton(onClick = { withLocation { vm.startLobby(null, solo = true) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Walk solo") }
                if (!perms.location) Disclaimer("Location is only used during a walk. You will be asked when you start one.")
                Disclaimer(Copy.SHARE_LOCATION)
            }

            Disclaimer(Copy.WELLNESS)
            Disclaimer("Verified steps leave out time spent in vehicles or on a bike, and anything faster than walking pace.")
        }
    }

    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(greeting(java.time.LocalTime.now().hour, settings?.displayName.orEmpty()), style = MaterialTheme.typography.headlineSmall)
            Text(
                h.daysTogether ?: "One step, then another.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                header()
                hero()
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { details() }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            header()
            hero()
            details()
        }
    }

    if (joinDialog) {
        AlertDialog(
            onDismissRequest = { joinDialog = false },
            title = { Text("Join a walk") },
            text = {
                OutlinedTextField(
                    value = codeText, onValueChange = { codeText = it.take(200) }, singleLine = true,
                    label = { Text("6-character code or link") }, isError = codeText.isNotBlank() && JoinLink.parse(codeText) == null,
                )
            },
            confirmButton = {
                TextButton(enabled = JoinLink.parse(codeText) != null, onClick = {
                    val t = codeText; joinDialog = false; codeText = ""
                    withLocation { vm.startLobby(t) }
                }) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { joinDialog = false }) { Text("Cancel") } },
        )
    }

    val link = pendingJoin
    if (link != null) {
        val target = JoinLink.parse(link)
        AlertDialog(
            onDismissRequest = { vm.pendingJoin.value = null },
            title = { Text("Join this walk?") },
            text = { Text(if (target == null) "That link is not a valid Walk Buddy link." else "Someone invited you to walk. Code ${target.code}. ${Copy.SHARE_LOCATION}") },
            confirmButton = {
                if (target != null) TextButton(onClick = { vm.pendingJoin.value = null; withLocation { vm.startLobby(link) } }) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { vm.pendingJoin.value = null }) { Text("Not now") } },
        )
    }
}

@Composable
private fun DemoBanner(vm: AppViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Demo mode", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                "Sample steps, a pretend buddy named Sam, and nothing saved. No permissions needed.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.demoReachGoal() }) { Text("Reach the goal") }
                OutlinedButton(onClick = { vm.startDemoWalk() }) { Text("Try a demo walk") }
                TextButton(onClick = { vm.setDemoMode(false) }) { Text("Use my real data") }
            }
        }
    }
}
