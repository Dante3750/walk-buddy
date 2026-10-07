@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkbuddy.domain.BuddyCard
import com.walkbuddy.domain.BuddyStatus
import com.walkbuddy.domain.Copy
import com.walkbuddy.domain.Format
import com.walkbuddy.rtc.SignalingState
import com.walkbuddy.session.Phase
import com.walkbuddy.session.SessionUi
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.components.ProgressRing
import com.walkbuddy.ui.components.QrView
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.StatLine
import com.walkbuddy.ui.components.ToggleRow

/** Full-screen flow shown whenever a session is not idle: lobby, live walk, summary. */
@Composable
fun WalkFlow(vm: AppViewModel, ui: SessionUi) {
    Column(Modifier.fillMaxSize()) {
        when (ui.phase) {
            Phase.Lobby -> LobbyScreen(vm, ui)
            Phase.Walking -> LiveScreen(vm, ui)
            Phase.Summary -> SummaryScreen(vm, ui)
            Phase.Idle -> Unit
        }
    }
    ui.spotOffer?.let { (from, spot) ->
        AlertDialog(
            onDismissRequest = { vm.acceptSpot(false) },
            title = { Text("$from shared a favorite spot") },
            text = { Text("\"${spot.name}\". Save it to your favorite walking spots? It stays on this phone.") },
            confirmButton = { TextButton(onClick = { vm.acceptSpot(true) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { vm.acceptSpot(false) }) { Text("No thanks") } },
        )
    }
}

@Composable
private fun LobbyScreen(vm: AppViewModel, ui: SessionUi) {
    val ctx = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (ui.solo) "Solo walk" else "Your walk", style = MaterialTheme.typography.titleLarge)

        if (!ui.solo && ui.code != null) {
            SectionCard("Invite a buddy") {
                Text(
                    ui.code, fontSize = 40.sp, style = MaterialTheme.typography.headlineMedium, letterSpacing = 6.sp,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Session code ${ui.code!!.toList().joinToString(" ")}" },
                    textAlign = TextAlign.Center,
                )
                ui.joinLink?.let { link ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { QrView(link) }
                    Disclaimer("Scan with your buddy's phone camera, or send them the link or the code.")
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "Walk with me! Open Walk Buddy and join with code ${ui.code}, or tap: $link")
                            ctx.startActivity(Intent.createChooser(send, "Share invite"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Share invite link") }
                }
                Disclaimer(Copy.SHARE_LOCATION)
            }

            SectionCard("Who is here") {
                val statusText = when (ui.signaling) {
                    SignalingState.Idle -> "Not connected"
                    SignalingState.Connecting -> "Connecting to the signaling server..."
                    SignalingState.Connected -> "Waiting for buddies to join"
                    SignalingState.Failed -> "Having trouble reaching the server. Retrying."
                }
                Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (ui.peers.isEmpty()) {
                    Text("Nobody yet. You can start walking now and they can still join.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    ui.peers.forEach { Text("${it.name}  ${if (it.connected) "connected" else "connecting"}", style = MaterialTheme.typography.titleMedium) }
                }
                if (ui.coupleMode) Text("Just the two of you. Couple features are on.", color = MaterialTheme.colorScheme.primary)
            }
        } else {
            ui.note?.let { Text(it) }
        }

        ToggleRow("Quiet mode", "Mute all nudges for this walk", ui.quiet) { vm.setQuiet(it) }

        Button(onClick = { vm.startWalking() }, modifier = Modifier.fillMaxWidth()) { Text("Start walking") }
        OutlinedButton(onClick = { vm.leaveLobby() }, modifier = Modifier.fillMaxWidth()) { Text("Leave") }
        Disclaimer(Copy.SHARING_ENDS)
    }
}

@Composable
private fun LiveScreen(vm: AppViewModel, ui: SessionUi) {
    var confirmEnd by remember { mutableStateOf(false) }
    val w = ui.walk
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (w == null) {
            EmptyState("Getting ready", "Looking for a GPS fix. Stepping outside helps.")
            OutlinedButton(onClick = { vm.endWalk() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            return@Column
        }
        ui.banner?.let { text ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Notice: $text" },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    TextButton(onClick = { vm.dismissBanner() }) { Text("OK") }
                }
            }
        }

        SectionCard("You") {
            StatLine("Time", Format.duration(w.elapsedMs))
            StatLine("Distance", Format.distance(w.myDistanceM))
            StatLine("Steps (verified)", "${w.myVerifiedSteps}")
            if (w.myRawSteps != w.myVerifiedSteps) StatLine("Steps (raw)", "${w.myRawSteps}")
            StatLine("Pace", Format.pace(w.mySpeedMps))
            StatLine("Zone", w.myZone?.label ?: "-")
        }

        if (w.buddies.isNotEmpty()) {
            SectionCard(null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    ProgressRing(
                        fraction = w.together.scorePct / 100f, centerTop = "${w.together.scorePct}%", centerBottom = "together",
                        description = "Together score ${w.together.scorePct} percent", size = 150.dp, stroke = 12.dp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Text(
                    when (w.togetherNow) {
                        true -> "Side by side right now"
                        false -> "A little apart right now"
                        null -> "Waiting for everyone's location"
                    },
                    Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
                )
                Disclaimer("Share of the walk spent within the group radius. Longest stretch together: ${Format.duration(w.together.longestStreakMs)}.")
            }
            w.buddies.forEach { BuddyCardView(it) }
            w.paceSuggestion?.let { sug ->
                val slow = w.buddies.firstOrNull { b -> b.id == sug.slowestId }
                SectionCard("Pace match") {
                    Text(
                        if (slow != null) "Matching ${slow.name}'s pace, around ${sug.targetPace}, keeps you together without effort."
                        else "Your pace, around ${sug.targetPace}, is the easiest for the group. Your buddies can match it.",
                    )
                }
            }
        } else {
            SectionCard(null) { Text("Nobody else is connected yet. They can still join with the code.") }
        }

        ToggleRow("Quiet mode", "Mute all nudges for this walk", ui.quiet) { vm.setQuiet(it) }
        if (ui.pingEnabled && w.buddies.isNotEmpty()) {
            OutlinedButton(onClick = { vm.sendPing() }, modifier = Modifier.fillMaxWidth()) { Text("Thinking of you") }
        }
        Button(onClick = { confirmEnd = true }, modifier = Modifier.fillMaxWidth()) { Text("End walk") }
        Disclaimer(Copy.SHARING_ENDS)
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this walk?") },
            text = { Text("Live sharing stops right away and your summary is saved on this phone.") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; vm.endWalk() }) { Text("End walk") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep walking") } },
        )
    }
}

@Composable
private fun BuddyCardView(b: BuddyCard) {
    val container = when (b.status) {
        BuddyStatus.Moving -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
    }
    SectionCard(b.name, container = container) {
        Text(b.statusText, style = MaterialTheme.typography.bodyMedium)
        if (b.status == BuddyStatus.Moving || b.status == BuddyStatus.Stopped) {
            StatLine("Where", b.relation)
            b.distanceM?.let { StatLine("Distance", Format.distance(it)) }
            StatLine("Steps", "${b.steps}")
            StatLine("Pace", Format.pace(b.speedMps))
            StatLine("Zone", b.zone?.label ?: "-")
        }
    }
}

@Composable
private fun SummaryScreen(vm: AppViewModel, ui: SessionUi) {
    val ctx = LocalContext.current
    val s = ui.summary
    val card = ui.highlights
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (s == null || card == null) {
            EmptyState("Nothing to show", "This walk had no data.")
        } else {
            Text(card.title, style = MaterialTheme.typography.titleLarge)
            SectionCard("Highlights") {
                card.lines.forEach { Text(it, style = MaterialTheme.typography.titleMedium) }
                if (s.steps0()) Disclaimer("Raw steps were ${s.rawSteps}; verified steps leave out time in vehicles or at running speed.")
            }
            if (s.nudgesShown > 0) Disclaimer("${s.nudgesShown} gentle nudge(s) during this walk.")
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, card.title + "\n" + card.lines.joinToString("\n"))
                    ctx.startActivity(Intent.createChooser(send, "Share highlights"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share highlights (stats only)") }
        }
        Disclaimer("Sharing has ended and no locations were kept anywhere but this phone.")
        OutlinedButton(onClick = { vm.finishSummary() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

private fun com.walkbuddy.domain.WalkSummary.steps0(): Boolean = rawSteps != verifiedSteps
