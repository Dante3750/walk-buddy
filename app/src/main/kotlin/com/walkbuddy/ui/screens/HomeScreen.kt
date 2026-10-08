@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.walkbuddy.ui.components.CardShape
import com.walkbuddy.ui.components.StatItem
import com.walkbuddy.ui.components.StatStrip
import com.walkbuddy.ui.components.StreakChip
import com.walkbuddy.ui.components.WbProgress
import com.walkbuddy.ui.components.staggerIn
import com.walkbuddy.ui.theme.NumberStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import android.app.Activity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.domain.StepCountingStatus
import com.walkbuddy.domain.StepHealth
import com.walkbuddy.steps.StepTracking
import com.walkbuddy.data.Clock
import com.walkbuddy.domain.Copy
import com.walkbuddy.domain.GentleDay
import com.walkbuddy.domain.GoalCelebration
import com.walkbuddy.domain.Hero
import com.walkbuddy.domain.Invite
import com.walkbuddy.domain.Invites
import com.walkbuddy.domain.JoinLink
import com.walkbuddy.domain.LocationPrecision
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
import com.walkbuddy.ui.components.ModeCard
import androidx.compose.ui.semantics.heading
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
fun HomeScreen(
    vm: AppViewModel,
    wide: Boolean,
    onScan: () -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onJoinGroup: () -> Unit = {},
    onHistory: () -> Unit = {},
) {
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

    // ---- step counting permission and notices ----
    var askTick by remember { mutableIntStateOf(0) }
    val askActivity = rememberPermissionRequester(perms) {
        StepTracking.markAsked(ctx)
        askTick++
        if (perms.activity) vm.stepsPermissionGranted()
    }
    // Coming back from the system settings page (or any other app) re-checks the permission and the battery state.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        perms.refresh()
        resumeTick++
        if (perms.activity) vm.stepsPermissionGranted()
    }
    val stepsNotice: StepsNotice = run {
        if (askTick < 0 || resumeTick < 0) return@run StepsNotice.None // reads both, so this recomputes after a permission answer or a return from settings
        val granted = perms.activity
        val activity = ctx as? Activity
        val blocked = !granted && StepTracking.wasAsked(ctx) && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, android.Manifest.permission.ACTIVITY_RECOGNITION)
        when (StepHealth.status(vm.stepKind, vm.stepsNeedPermission, granted, blocked)) {
            StepCountingStatus.NoSensor -> StepsNotice.NoSensor
            StepCountingStatus.NeedsPermission -> StepsNotice.NeedsPermission
            StepCountingStatus.PermissionBlocked -> StepsNotice.PermissionBlocked
            StepCountingStatus.Counting ->
                if (!StepTracking.batteryTipDismissed(ctx) && !StepTracking.ignoringBatteryOptimizations(ctx)) StepsNotice.BatteryTip else StepsNotice.None
        }
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

    HomeContent(
        h = h,
        greeting = greeting(java.time.LocalTime.now().hour, settings?.displayName.orEmpty()),
        wide = wide,
        locationGranted = perms.location,
        burst = burst,
        onBurstDone = { burst = false },
        notice = stepsNotice,
        a = HomeActions(
            onAllowSteps = { askActivity(PermState.ACTIVITY) },
            onOpenAppSettings = { StepTracking.openAppSettings(ctx) },
            onBatterySettings = { StepTracking.openBatterySettings(ctx) },
            onDismissBatteryTip = { StepTracking.dismissBatteryTip(ctx); resumeTick++ },
            onGentle = { vm.setGentleToday(it) },
            onRest = { vm.setRestToday(it) },
            onShare = {
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
            onStartTogether = { withLocation { vm.startLobby(null) } },
            onJoin = { joinDialog = true },
            onSolo = { withLocation { vm.startLobby(null, solo = true) } },
            onDemoGoal = { vm.demoReachGoal() },
            onDemoWalk = { vm.startDemoWalk() },
            onScan = onScan,
            onCreateGroup = onCreateGroup,
            onJoinGroup = onJoinGroup,
            onHistory = onHistory,
            onDemoGroup = { vm.startDemoGroup() },
            onUseRealData = { vm.setDemoMode(false) },
        ),
    )

    if (joinDialog) {
        AlertDialog(
            onDismissRequest = { joinDialog = false },
            title = { Text("Join a walk") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = codeText, onValueChange = { codeText = it.take(200) }, singleLine = true,
                        label = { Text("6-character code or link") }, isError = codeText.isNotBlank() && JoinLink.parse(codeText) == null,
                    )
                    OutlinedButton(onClick = { joinDialog = false; onScan() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Scan a QR code instead") }
                }
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
        val invite = Invites.parse(link)
        val nick = settings?.displayName.orEmpty().ifBlank { "Walker" }
        AlertDialog(
            onDismissRequest = { vm.pendingJoin.value = null },
            title = { Text(if (invite is Invite.Group) "Join this group walk?" else "Join this walk?") },
            text = {
                Text(
                    when (invite) {
                        null -> "That link is not a valid Walk Buddy link."
                        is Invite.Group -> "You have been invited to an open group walk. Code ${invite.code}. You will appear as $nick. ${Copy.SHARE_LOCATION}"
                        is Invite.Partner -> "Someone invited you to walk. Code ${invite.code}. ${Copy.SHARE_LOCATION}"
                    },
                )
            },
            confirmButton = {
                when (invite) {
                    null -> Unit
                    is Invite.Group -> TextButton(onClick = {
                        vm.pendingJoin.value = null
                        val precision = settings?.groupPrecision ?: LocationPrecision.Exact
                        withLocation { vm.joinGroup(link, nick, precision) }
                    }) { Text("Join") }
                    is Invite.Partner -> TextButton(onClick = { vm.pendingJoin.value = null; withLocation { vm.startLobby(link) } }) { Text("Join") }
                }
            },
            dismissButton = { TextButton(onClick = { vm.pendingJoin.value = null }) { Text("Not now") } },
        )
    }
}


/** Everything the home screen can do, as plain callbacks, so [HomeContent] can be rendered without a ViewModel. */
class HomeActions(
    val onGentle: (Boolean) -> Unit = {},
    val onRest: (Boolean) -> Unit = {},
    val onShare: () -> Unit = {},
    val onStartTogether: () -> Unit = {},
    val onJoin: () -> Unit = {},
    val onSolo: () -> Unit = {},
    val onDemoGoal: () -> Unit = {},
    val onDemoWalk: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onCreateGroup: () -> Unit = {},
    val onJoinGroup: () -> Unit = {},
    val onDemoGroup: () -> Unit = {},
    val onUseRealData: () -> Unit = {},
    val onAllowSteps: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onBatterySettings: () -> Unit = {},
    val onDismissBatteryTip: () -> Unit = {},
    val onHistory: () -> Unit = {},
)

/** What the step counter needs from the user, shown at the top of Home. Only one at a time, most urgent first. */
enum class StepsNotice { None, NeedsPermission, PermissionBlocked, NoSensor, BatteryTip }

@Composable
fun HomeContent(
    h: HomeUi,
    greeting: String,
    wide: Boolean,
    locationGranted: Boolean,
    burst: Boolean,
    onBurstDone: () -> Unit,
    a: HomeActions,
    notice: StepsNotice = StepsNotice.None,
) {
    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(greeting, style = MaterialTheme.typography.headlineMedium, maxLines = 2)
                Text(
                    h.daysTogether ?: "One step, then another.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StreakChip(h.flame)
        }
    }

    val stepsCard: @Composable () -> Unit = {
        if (!h.demo) StepsNoticeCard(notice, a)
    }

    val hero: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StepHero(steps = h.verifiedSteps, goal = h.goal, buddies = h.buddies, walking = h.walking)
                ConfettiBurst(active = burst, onDone = onBurstDone, modifier = Modifier.matchParentSize())
            }
            if (Hero.goalReached(h.verifiedSteps, h.goal)) {
                Text(
                    "You reached today's goal. Lovely.",
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center,
                )
            }
            BuddyLeadRow(h.buddies, h.verifiedSteps)
            VerifiedChip(h.verifiedSteps, h.rawSteps)
        }
    }

    val stats: @Composable () -> Unit = {
        val dist = Units.distanceAmount(h.distanceM, h.unit)
        StatStrip(
            buildList {
                add(StatItem("Distance", dist.value, dist.unit))
                add(StatItem("Active", (if (h.activeIsEstimate) "~" else "") + h.activeMin, "min"))
                h.calories?.let { add(StatItem("Energy, estimate", "${(it.lowKcal + it.highKcal) / 2}", "kcal")) }
            },
        )
    }

    val walk: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Walk together", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
            ModeCard(
                title = "Walk with partner", tagline = "2 people  ·  direct when possible, private",
                gradient = listOf(Color(0xFFC2305F), Color(0xFF5E1D55)),
                glyphs = listOf(Color(0xFFE0557E), Color(0xFF8E3A8A)),
            ) {
                Text("Your phones connect directly whenever the network allows. If it does not, the tiny server passes your small updates along without storing or logging them.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = a.onStartTogether, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Start a walk together", style = MaterialTheme.typography.titleMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = a.onJoin, colors = tonalColors(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Join with a code", textAlign = TextAlign.Center) }
                    FilledTonalButton(onClick = a.onScan, colors = tonalColors(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Scan QR", textAlign = TextAlign.Center) }
                }
            }
            ModeCard(
                title = "Open group walk", tagline = "Up to about 50  ·  one shared map",
                gradient = listOf(Color(0xFF0E7C7B), Color(0xFF16466B)),
                glyphs = listOf(Color(0xFF2FA9A3), Color(0xFF3C7DBE), Color(0xFF6A6FD0)),
            ) {
                Text("Anyone with the QR code, link or code can join, even after you have started. Everyone appears on one map.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = a.onCreateGroup, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Create a group", style = MaterialTheme.typography.titleMedium)
                }
                FilledTonalButton(onClick = a.onJoinGroup, colors = tonalColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Join a group", textAlign = TextAlign.Center) }
                if (h.demo) OutlinedButton(onClick = a.onDemoGroup, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Try a demo group walk") }
            }
            FilledTonalButton(onClick = a.onSolo, colors = tonalColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Walk solo") }
            OutlinedButton(onClick = a.onHistory, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Walks together (history)") }
            if (!locationGranted) Disclaimer("Location is only used during a walk. You will be asked when you start one.")
        }
    }

    val details: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (h.demo) DemoBanner(a)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = h.gentle, onClick = { a.onGentle(!h.gentle) },
                    label = { Text(if (h.gentle) "Gentle day is on" else "Gentle day") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                FilterChip(
                    selected = h.restDay, onClick = { a.onRest(!h.restDay) },
                    label = { Text(if (h.restDay) "Rest day is on" else "Rest day") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                AssistChip(onClick = a.onShare, label = { Text("Share today") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            if (h.gentle) Disclaimer("${GentleDay.COPY} Today's goal: ${Hero.thousands(h.goal)} steps.")
            if (h.restDay) Text("Today is a rest day. Moving is optional.", color = MaterialTheme.colorScheme.primary)

            StreakCard(h.flame, Modifier.staggerIn(0))

            if (h.countdown != null) HeartCard(h.daysTogether, h.countdown, Modifier.staggerIn(1))

            SectionCard("Active minutes this week", Modifier.staggerIn(2)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${h.guidance.equivMin}", style = NumberStyle.copy(fontSize = 32.sp, lineHeight = 36.sp))
                    Text("of ${WeeklyGuidance.TARGET_MIN} minutes", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WbProgress(h.guidance.fraction.toFloat(), Modifier.semantics { contentDescription = "${h.guidance.equivMin} of 150 active minutes this week" })
                Disclaimer(Copy.ACTIVE_GUIDANCE)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Disclaimer(Copy.SHARE_LOCATION)
                Disclaimer(Copy.WELLNESS)
                Disclaimer("Verified steps leave out time spent in vehicles or on a bike, and anything faster than walking pace.")
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                header()
                stepsCard()
                hero()
                stats()
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                walk()
                details()
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            header()
            stepsCard()
            hero()
            stats()
            walk()
            details()
        }
    }
}

@Composable
private fun StepsNoticeCard(n: StepsNotice, a: HomeActions) {
    if (n == StepsNotice.None) return
    val title = when (n) {
        StepsNotice.NeedsPermission -> "Allow step counting"
        StepsNotice.PermissionBlocked -> "Step counting is switched off"
        StepsNotice.NoSensor -> "No step sensor on this phone"
        else -> "Keep counting when the app is closed"
    }
    val body = when (n) {
        StepsNotice.NeedsPermission ->
            "Steps are not counting yet. Android calls this permission physical activity. Walk Buddy reads your phone's built-in step counter all day, even when the app is closed or you are offline. Nothing leaves your phone."
        StepsNotice.PermissionBlocked ->
            "Android is no longer showing the permission prompt. Open the app settings, choose Permissions, then Physical activity, and select Allow. Steps taken meanwhile are not lost once it is on."
        StepsNotice.NoSensor ->
            "This phone reports no step counter, step detector or motion sensor, so steps cannot be counted here. Walks still measure distance with GPS."
        else ->
            "Some phones stop background apps to save battery. If your steps stop adding up while the app is closed, let Walk Buddy run in the background. It only opens a settings screen; nothing changes unless you choose it."
    }
    SectionCard(title) {
        Text(body, style = MaterialTheme.typography.bodyMedium)
        when (n) {
            StepsNotice.NeedsPermission ->
                Button(onClick = a.onAllowSteps, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Allow step counting") }
            StepsNotice.PermissionBlocked ->
                Button(onClick = a.onOpenAppSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open app settings") }
            StepsNotice.BatteryTip -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = a.onBatterySettings, colors = tonalColors(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Battery settings", textAlign = TextAlign.Center) }
                TextButton(onClick = a.onDismissBatteryTip, modifier = Modifier.heightIn(min = 48.dp)) { Text("Not now") }
            }
            else -> Unit
        }
    }
}

@Composable
private fun tonalColors() = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/** "Day 412 together" and the next date to look forward to, on a berry gradient. White text keeps strong contrast in both themes. */
@Composable
private fun HeartCard(daysTogether: String?, countdown: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().clip(CardShape).background(Brush.linearGradient(listOf(Color(0xFFC2305F), Color(0xFF5E1D55)))).padding(20.dp)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(daysTogether, countdown).joinToString(". ") },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            daysTogether?.let { Text(it, style = MaterialTheme.typography.headlineSmall, color = Color.White) }
            Text(countdown, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.92f))
        }
    }
}

@Composable
private fun DemoBanner(a: HomeActions) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Demo mode", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                "Sample steps, a pretend buddy named Sam, and nothing saved. No permissions needed.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = a.onDemoGoal) { Text("Reach the goal") }
                OutlinedButton(onClick = a.onDemoWalk) { Text("Try a demo walk") }
                TextButton(onClick = a.onUseRealData) { Text("Use my real data") }
            }
        }
    }
}
