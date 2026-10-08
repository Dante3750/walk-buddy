@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.domain.BuddyStatus
import com.walkbuddy.domain.CollectiveSteps
import com.walkbuddy.domain.Copy
import com.walkbuddy.domain.Format
import com.walkbuddy.domain.GroupLink
import com.walkbuddy.domain.GroupMemberCard
import com.walkbuddy.domain.GroupRole
import com.walkbuddy.domain.GroupState
import com.walkbuddy.domain.Hero
import com.walkbuddy.domain.LatLon
import com.walkbuddy.domain.MapFollow
import com.walkbuddy.domain.UnitSystem
import com.walkbuddy.domain.Units
import com.walkbuddy.rtc.SignalingState
import com.walkbuddy.session.GroupPhase
import com.walkbuddy.session.GroupUi
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.components.AnimatedCount
import com.walkbuddy.ui.components.Avatar
import com.walkbuddy.ui.components.SegmentedProgress
import com.walkbuddy.ui.components.StatusPill
import com.walkbuddy.ui.components.TagChip
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.components.MapColors
import com.walkbuddy.ui.components.MapPerson
import com.walkbuddy.ui.components.QrView
import com.walkbuddy.ui.components.ScreenTitle
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.StatItem
import com.walkbuddy.ui.components.StatStrip
import com.walkbuddy.ui.components.ToggleRow
import com.walkbuddy.ui.components.WalkMap
import com.walkbuddy.ui.components.WbProgress

/** Everything the group screens can do, as plain callbacks, so they render without a ViewModel. */
class GroupActions(
    val onEnd: (closeForEveryone: Boolean) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDismissBanner: () -> Unit = {},
    val onSharing: (Boolean) -> Unit = {},
    val onQuiet: (Boolean) -> Unit = {},
    val onSweeper: (Boolean) -> Unit = {},
    val onApprove: (String) -> Unit = {},
    val onDeny: (String) -> Unit = {},
    val onKick: (String) -> Unit = {},
    val onApprovalRequired: (Boolean) -> Unit = {},
    val onGoal: (Int) -> Unit = {},
    val onSetPin: (LatLon) -> Unit = {},
    val onClearPin: () -> Unit = {},
    val onShareInvite: (String) -> Unit = {},
    val onFinish: () -> Unit = {},
    val onLocationAction: (com.walkbuddy.domain.LocationAction) -> Unit = {},
)

/** Full-screen flow while an open group walk is on: waiting, live (map, people, invite) and summary. */
@Composable
fun GroupFlow(vm: AppViewModel, ui: GroupUi) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val unit = settings?.unitSystem ?: UnitSystem.Metric
    val power by vm.powerState.collectAsStateWithLifecycle()
    val locationAction = com.walkbuddy.ui.rememberLocationNoticeHandler { vm.locationAvailable() }
    val a = GroupActions(
        onLocationAction = locationAction,
        onEnd = { vm.groupEnd(it) },
        onCancel = { vm.groupCancel() },
        onDismissBanner = { vm.groupDismissBanner() },
        onSharing = { vm.groupSharing(it) },
        onQuiet = { vm.groupQuiet(it) },
        onSweeper = { vm.groupSweeper(it) },
        onApprove = { vm.groupApprove(it) },
        onDeny = { vm.groupDeny(it) },
        onKick = { vm.groupKick(it) },
        onApprovalRequired = { vm.groupApprovalRequired(it) },
        onGoal = { vm.groupGoal(it) },
        onSetPin = { vm.groupSetPin(it) },
        onClearPin = { vm.groupClearPin() },
        onShareInvite = { text ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            ctx.startActivity(Intent.createChooser(send, "Share invite"))
        },
        onFinish = { vm.groupFinish() },
    )
    when (ui.phase) {
        GroupPhase.Active -> GroupLiveContent(ui, unit, settings?.mapTiles == true, a, lowPower = power.systemBatterySaver || power.userBatterySaver)
        GroupPhase.Summary -> GroupSummaryContent(ui, a)
        GroupPhase.Idle -> Unit
    }
}

@Composable
fun GroupLiveContent(ui: GroupUi, unit: UnitSystem, tiles: Boolean, a: GroupActions, lowPower: Boolean = false) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    BackHandler(enabled = true) { confirm = true }

    if (!ui.joined) {
        WaitingContent(ui, a)
        return
    }
    val w = ui.walk
    Column(Modifier.fillMaxSize()) {
        GroupHeader(ui)
        ui.banner?.let { text ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Notice: $text" },
            ) {
                Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    TextButton(onClick = a.onDismissBanner) { Text("OK") }
                }
            }
        }
        if (ui.hostAway) Disclaimer("The host is away for a moment. The walk carries on.", Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        ui.note?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val notice = if (ui.demo) null else com.walkbuddy.domain.LocationStatusLogic.notice(ui.locStatus, ui.locSearchingSec)
        // "Finding your position" only deserves a card once it has taken a moment; the others need the person to act.
        val shownNotice = notice?.takeIf { it.status != com.walkbuddy.domain.LocationStatus.Searching || ui.locSearchingSec >= 8 }
        if (shownNotice != null && tab <= 1) {
            com.walkbuddy.ui.components.LocationNoticeCard(shownNotice, a.onLocationAction, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        val missing = ui.requests.size
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Track") }, modifier = Modifier.heightIn(min = 48.dp))
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Map") }, modifier = Modifier.heightIn(min = 48.dp))
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(if (missing > 0 && ui.iAmHost) "People ($missing new)" else "People") }, modifier = Modifier.heightIn(min = 48.dp))
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Invite") }, modifier = Modifier.heightIn(min = 48.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> GroupTrackTab(ui, w, unit, lowPower)
                1 -> GroupMapTab(ui, w, unit, tiles, a)
                2 -> GroupPeopleTab(ui, w, unit, a)
                else -> GroupInviteTab(ui, w, a)
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.iAmHost) Button(onClick = { confirm = true }, Modifier.weight(1f).heightIn(min = 52.dp)) { Text("End walk") }
            else OutlinedButton(onClick = { confirm = true }, Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Leave group") }
        }
    }
    if (confirm) EndDialog(ui, onDismiss = { confirm = false }, a = a)
}

@Composable
private fun GroupHeader(ui: GroupUi) {
    val w = ui.walk
    val people = w?.memberCount ?: ui.roster.size.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ui.settings.title.ifBlank { "Group walk" }, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(
                (if (ui.demo) "Demo" else "Code ${ui.code ?: ""}") + "  ·  $people ${if (people == 1) "person" else "people"}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val status = when {
            ui.demo -> "Demo walk"
            ui.connection == SignalingState.Connected -> "Connected"
            ui.connection == SignalingState.Connecting -> "Connecting"
            else -> "Reconnecting"
        }
        StatusPill(status, if (ui.connection == SignalingState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun WaitingContent(ui: GroupUi, a: GroupActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle(
            if (ui.waitingForApproval) "Waiting for the host" else if (ui.iAmHost) "Creating your group" else "Joining the group",
            subtitle = if (ui.waitingForApproval) "The host has been asked to let you in. You can leave at any time." else "One moment.",
        )
        SectionCard(null) {
            if (ui.connection != SignalingState.Failed) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape).semantics { contentDescription = "Connecting" })
            }
            Text(
                when (ui.connection) {
                    SignalingState.Failed -> ui.note ?: com.walkbuddy.domain.ServerConfig.RETRY_NOTE
                    SignalingState.Connecting -> com.walkbuddy.domain.ServerConfig.WAKING_NOTE
                    SignalingState.Connected -> if (ui.waitingForApproval) "Request sent." else "Connected. Joining..."
                    SignalingState.Idle -> ui.note ?: "Starting..."
                },
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            ui.code?.let { Text("Code $it", style = MaterialTheme.typography.titleMedium) }
        }
        OutlinedButton(onClick = a.onCancel, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Cancel") }
        Disclaimer(Copy.GROUP_RELAY)
    }
}

@Composable
private fun EndDialog(ui: GroupUi, onDismiss: () -> Unit, a: GroupActions) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (ui.iAmHost) "End your walk?" else "Leave this group?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your location stops being shared right away and your walk is saved on this phone.")
                if (ui.iAmHost) {
                    Text("As host you can close the group for everyone, or leave and let it carry on for a while.", style = MaterialTheme.typography.bodySmall)
                }
                if (ui.iAmHost && !ui.demo) {
                    Button(onClick = { onDismiss(); a.onEnd(true) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("End for everyone") }
                }
                OutlinedButton(onClick = { onDismiss(); a.onEnd(false) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (ui.iAmHost) "Leave, group continues" else "Leave group")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep walking") } },
    )
}

// ------------------------------------------------------------------------------------------------------------------
// Track tab
// ------------------------------------------------------------------------------------------------------------------

/** Everyone on parallel lanes along one line: who is at the front, who is at the back, and how long the group is. */
@Composable
private fun GroupTrackTab(ui: GroupUi, w: GroupState?, unit: UnitSystem, lowPower: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (w == null) {
            EmptyState("Getting ready", "Your walkers appear here as soon as the group starts moving.")
            return@Column
        }
        val walkers = androidx.compose.runtime.remember(w, ui.myAvatar) { com.walkbuddy.domain.TrackBuilders.forGroup(w, "You", ui.myAvatar) }
        val lanes = minOf(walkers.size, com.walkbuddy.domain.TrackLayout.MAX_LANES)
        com.walkbuddy.ui.components.TrackView(
            walkers = walkers, realGapM = null, unit = unit, lowPower = lowPower,
            modifier = Modifier.fillMaxWidth().height((90 + 62 * lanes).coerceIn(240, 640).dp),
        )
        Text(statusLine(ui, w, unit), Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        if (!ui.sharing) Disclaimer("You are not sharing your location. You can still see the group.")
    }
}

// ------------------------------------------------------------------------------------------------------------------
// Map tab
// ------------------------------------------------------------------------------------------------------------------

/** The map people for a group state: me plus everyone who has shared a position. */
fun groupMapPeople(ui: GroupUi, w: GroupState?): List<MapPerson> {
    if (w == null) return emptyList()
    val me = MapPerson(w.selfId, "You", w.myPos, w.trails[w.selfId].orEmpty(), isMe = true, role = w.myRole, apart = w.iAmStraggler)
    // A member who went quiet stays where we last saw them, faded, instead of vanishing from the map.
    return listOf(me) + w.members.map {
        val quiet = it.pos == null || (it.lastHeardAgoSec ?: 0) >= com.walkbuddy.domain.TrackBuilders.STALE_SEC
        MapPerson(it.id, it.name, it.pos ?: it.lastPos, w.trails[it.id].orEmpty(), stale = quiet, apart = it.straggler, role = it.role)
    }
}

@Composable
private fun GroupMapTab(ui: GroupUi, w: GroupState?, unit: UnitSystem, tiles: Boolean, a: GroupActions) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (w != null) {
            Text(
                statusLine(ui, w, unit),
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
            )
        }
        WalkMap(
            people = groupMapPeople(ui, w), pin = ui.pin, tilesEnabled = tiles, imperial = unit == UnitSystem.Imperial,
            canPin = ui.iAmHost, onSetPin = a.onSetPin, onClearPin = a.onClearPin,
            modifier = Modifier.weight(1f).fillMaxWidth(), initialFollow = MapFollow.Group,
            notice = null, onNoticeAction = a.onLocationAction,
        )
        if (!ui.sharing) Disclaimer("You are not sharing your location. You can still see the group.")
    }
}

private fun statusLine(ui: GroupUi, w: GroupState, unit: UnitSystem): String {
    val parts = mutableListOf<String>()
    parts += when (w.togetherNow) {
        true -> "Together right now"
        false -> "Spread out a little"
        null -> "Waiting for locations"
    }
    w.groupRadiusM?.let { if (w.memberCount > 1) parts += "spans about ${Units.distance(it * 2, unit)}" }
    if (w.goal.goal > 0) parts += "goal ${(w.goal.fraction * 100).toInt()}%"
    return parts.joinToString("  ·  ")
}

// ------------------------------------------------------------------------------------------------------------------
// People tab
// ------------------------------------------------------------------------------------------------------------------

@Composable
private fun GroupPeopleTab(ui: GroupUi, w: GroupState?, unit: UnitSystem, a: GroupActions) {
    val members = (w?.members ?: emptyList()).sortedWith(compareBy<GroupMemberCard> { it.distanceM == null }.thenBy { it.distanceM ?: 0.0 }.thenBy { it.name })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ui.iAmHost && ui.requests.isNotEmpty()) {
            item(key = "requests") {
                SectionCard("Waiting to join") {
                    ui.requests.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { a.onDeny(r.id) }, Modifier.heightIn(min = 48.dp)) { Text("No") }
                            Button(onClick = { a.onApprove(r.id) }, Modifier.heightIn(min = 48.dp)) { Text("Let in") }
                        }
                    }
                }
            }
        }
        if (w != null) {
            item(key = "summary") { GroupSummaryCard(w, unit) }
            if (ui.settings.goalSteps > 0 || w.goal.totalSteps > 0) item(key = "goal") { GoalCard(w) }
            item(key = "me") {
                PersonRow(
                    name = "You", color = MaterialTheme.colorScheme.primary, role = w.myRole, apart = w.iAmStraggler, host = ui.iAmHost,
                    line1 = if (ui.sharing) "Sharing ${ui.precision.label.lowercase()}" else "Not sharing my location",
                    line2 = "${w.myVerifiedSteps} steps  ·  ${Units.distance(w.myDistanceM, unit)}", canRemove = false, onRemove = {},
                )
            }
        }
        if (members.isEmpty()) {
            item(key = "empty") { EmptyState("Just you so far", "Share the invite from the Invite tab. People can join at any time, even while you walk.") }
        }
        items(members, key = { it.id }) { m ->
            PersonRow(
                name = m.name, color = MapColors.forId(m.id), role = m.role, apart = m.straggler, host = ui.roster.firstOrNull { it.peerId == m.id }?.host == true,
                line1 = lineFor(m, unit), line2 = "${m.steps} steps",
                dim = m.status == BuddyStatus.ConnectionLost || m.status == BuddyStatus.Waiting,
                canRemove = ui.iAmHost && !ui.demo, onRemove = { a.onKick(m.id) },
            )
        }
        item(key = "mine") {
            SectionCard("My settings") {
                ToggleRow("Share my location", "Off: the group still gets your steps, but no position.", ui.sharing) { a.onSharing(it) }
                ToggleRow("Quiet mode", "Mute gentle hints for this walk", ui.quiet) { a.onQuiet(it) }
                ToggleRow("I am the sweeper", "I walk at the back on purpose, so no catch-up hints for me. This is only on my phone.", ui.iAmSweeper) { a.onSweeper(it) }
                Disclaimer("Location precision: ${ui.precision.label}. Set when you joined.")
            }
        }
        item(key = "privacy") { Disclaimer(Copy.GROUP_RELAY) }
    }
}

private fun lineFor(m: GroupMemberCard, unit: UnitSystem): String = when (m.status) {
    BuddyStatus.ConnectionLost -> "Connection lost for a moment"
    BuddyStatus.Waiting -> "Joining..."
    else -> {
        val d = m.distanceM
        when {
            d == null -> "Location not shared"
            m.status == BuddyStatus.Stopped -> Units.distance(d, unit) + " from you, paused"
            else -> Units.distance(d, unit) + " from you"
        }
    }
}

@Composable
private fun GroupSummaryCard(w: GroupState, unit: UnitSystem) {
    SectionCard(null) {
        Text(
            when (w.togetherNow) {
                true -> "Together right now"
                false -> "Spread out a little"
                null -> "Waiting for everyone's location"
            },
            Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
        )
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Group together score ${w.together.scorePct} percent" },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom,
        ) {
            AnimatedCount(w.together.scorePct, style = com.walkbuddy.ui.theme.NumberStyle.copy(fontSize = 44.sp, lineHeight = 48.sp), color = MaterialTheme.colorScheme.secondary, suffix = "%")
            Text("  of this walk together", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val facts = buildList {
            w.groupRadiusM?.let { add("The group spans about ${Units.distance(it * 2, unit)}.") }
            w.lengthM?.let { if (it > 40) add("Front to back is about ${Units.distance(it, unit)}.") }
            if (w.stragglerCount > 0) add("${w.stragglerCount} ${if (w.stragglerCount == 1) "person is" else "people are"} a little apart.")
        }
        if (facts.isNotEmpty()) Disclaimer(facts.joinToString(" "))
    }
}

@Composable
private fun GoalCard(w: GroupState) {
    val g = w.goal
    SectionCard("Our steps together") {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimatedCount(g.totalSteps.toInt(), style = com.walkbuddy.ui.theme.NumberStyle.copy(fontSize = 36.sp, lineHeight = 40.sp))
            if (g.goal > 0) Text("of %,d".format(g.goal), Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (g.goal > 0) {
            // Each person's steps are a piece of the same bar: it shows the group adding up, never who is ahead.
            val parts = listOf(w.myVerifiedSteps.toFloat() to MaterialTheme.colorScheme.primary) +
                w.members.map { it.steps.toFloat() to MapColors.forId(it.id) }
            SegmentedProgress(parts, g.fraction.toFloat(), Modifier.semantics { contentDescription = "${(g.fraction * 100).toInt()} percent of the group goal" })
        }
        Disclaimer(CollectiveSteps.message(g) + " Everyone's steps add up; nobody is ranked.")
    }
}

@Composable
private fun PersonRow(
    name: String, color: Color, role: GroupRole?, apart: Boolean, host: Boolean, line1: String, line2: String,
    dim: Boolean = false, canRemove: Boolean, onRemove: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    val tags = buildList {
        if (host) add("Host")
        role?.let { add(it.label) }
        if (apart) add("A little apart")
    }
    Surface(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$name. ${tags.joinToString(", ")}. $line1. $line2" },
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(Modifier.padding(12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(name, color, size = 44.dp, dim = dim)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { TagChip(it, color = if (it == "A little apart") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary) }
                    }
                }
                Text(line1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(line2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canRemove) TextButton(onClick = { confirmRemove = true }, Modifier.heightIn(min = 48.dp)) { Text("Remove") }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove $name?") },
            text = { Text("They leave the group right away and cannot rejoin this one.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }
}

// ------------------------------------------------------------------------------------------------------------------
// Invite tab
// ------------------------------------------------------------------------------------------------------------------

@Composable
private fun GroupInviteTab(ui: GroupUi, w: GroupState?, a: GroupActions) {
    val link = ui.inviteLink
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("Invite people") {
            val code = ui.code
            if (code != null) {
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Group code ${code.toList().joinToString(" ")}" },
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    code.forEach { ch ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(width = 40.dp, height = 54.dp), contentAlignment = Alignment.Center) {
                                Text(ch.toString(), style = com.walkbuddy.ui.theme.BigNumberStyle, fontSize = 30.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            }
            if (link != null && GroupLink.fitsQr(link)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        QrView(link, Modifier.padding(8.dp), size = 220.dp)
                    }
                }
                Disclaimer("Anyone can scan this to join, even while the group is walking. Or share the link or the code.")
            } else {
                Disclaimer("Share the link or the code. (Too long for a QR code.)")
            }
            if (link != null) {
                Button(
                    onClick = {
                        val text = buildString {
                            append("Join our group walk on Walk Buddy! Code ${ui.code}. ")
                            append("Open the app, choose Open group walk, then Join, or tap: ")
                            append(ui.webLink ?: link)
                        }
                        a.onShareInvite(text)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Share invite link") }
            }
            if (ui.settings.approval) Disclaimer("The host approves each person before they join.")
            ui.expiresAtMs?.let { exp ->
                val left = ((exp - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                Disclaimer("This group closes by itself in about ${if (left >= 90) "${left / 60} hours" else "$left minutes"}.")
            }
        }
        if (ui.iAmHost) {
            SectionCard("Host controls") {
                ToggleRow("I approve each person", "New people wait until you tap Let in.", ui.settings.approval) { a.onApprovalRequired(it) }
                Text("Group step goal", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 10_000, 25_000, 50_000).forEach { g ->
                        FilterChip(selected = ui.settings.goalSteps == g, onClick = { a.onGoal(g) }, label = { Text(if (g == 0) "None" else "%,d".format(g)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                val mine = w?.myPos
                if (ui.pin == null) {
                    OutlinedButton(
                        enabled = mine != null, onClick = { mine?.let(a.onSetPin) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Set a meeting point where I am") }
                    Disclaimer("Or press and hold anywhere on the map. Everyone sees the flag.")
                } else {
                    OutlinedButton(onClick = a.onClearPin, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Clear the meeting point") }
                }
            }
        }
        Disclaimer(Copy.GROUP_RELAY)
    }
}

// ------------------------------------------------------------------------------------------------------------------
// Summary
// ------------------------------------------------------------------------------------------------------------------

@Composable
fun GroupSummaryContent(ui: GroupUi, a: GroupActions) {
    val s = ui.summary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle(if (s != null) "Group walk done" else "Group walk", subtitle = if (s != null) "Nicely walked." else null)
        ui.endedReason?.let { r ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Text(r, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        if (s != null) {
            StatStrip(
                listOf(
                    StatItem("Time", Format.duration(s.durationMs)),
                    StatItem("Distance", Format.distance(s.distanceM)),
                    StatItem("Your steps", Hero.thousands(s.verifiedSteps.toInt())),
                ),
            )
            SectionCard("As a group") {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedCount(ui.groupSteps.toInt(), style = com.walkbuddy.ui.theme.NumberStyle.copy(fontSize = 36.sp, lineHeight = 40.sp))
                    Text("steps together", Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                s.togetherPct?.let { Text("$it% of the walk spent close together. Longest stretch: ${Format.duration(s.longestTogetherMs)}.") }
                Disclaimer("${s.buddyCount} ${if (s.buddyCount == 1) "other person" else "other people"} walked with you. Group details are not saved; your own walk is saved on this phone.")
            }
        } else if (ui.endedReason == null) {
            EmptyState("Nothing to show", "The walk ended before it began.")
        }
        Button(onClick = a.onFinish, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Done") }
        Disclaimer(Copy.SHARING_ENDS)
    }
}
