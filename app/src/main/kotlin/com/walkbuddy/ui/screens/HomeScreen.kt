@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.domain.Copy
import com.walkbuddy.domain.JoinLink
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.PermState
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.components.ProgressRing
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.StatLine
import com.walkbuddy.ui.rememberPermState
import com.walkbuddy.ui.rememberPermissionRequester

private fun fmt(n: Int) = "%,d".format(n)

@Composable
fun HomeScreen(vm: AppViewModel) {
    val home by vm.home.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pendingJoin by vm.pendingJoin.collectAsStateWithLifecycle()
    val perms = rememberPermState()
    var joinDialog by remember { mutableStateOf(false) }
    var codeText by remember { mutableStateOf("") }
    var action by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askLocation = rememberPermissionRequester(perms) {
        // Whatever the answer, continue: a walk without location still counts steps, just no distance or buddy gap.
        action?.invoke()
        action = null
    }

    fun withLocation(run: () -> Unit) {
        if (perms.location) run() else { action = run; askLocation(PermState.LOCATION) }
    }

    val h = home
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (h == null) {
            EmptyState("Loading", "Getting today's steps ready.")
            return@Column
        }
        Text(
            if (settings?.displayName.isNullOrBlank()) "Hello" else "Hello, ${settings?.displayName}",
            style = MaterialTheme.typography.titleLarge,
        )

        SectionCard(null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                ProgressRing(
                    fraction = if (h.goal <= 0) 0f else h.verifiedSteps.toFloat() / h.goal,
                    centerTop = fmt(h.verifiedSteps), centerBottom = "of ${fmt(h.goal)} steps",
                    description = "Today's verified steps ${h.verifiedSteps} of a goal of ${h.goal}",
                )
            }
            StatLine("Verified steps", fmt(h.verifiedSteps))
            StatLine("Raw steps from the sensor", fmt(h.rawSteps))
            Disclaimer("Verified steps leave out time spent in vehicles or on a bike, and anything faster than walking pace.")
            if (h.restDay) Text("Today is a rest day. Moving is optional.", color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { vm.setRestToday(!h.restDay) }) { Text(if (h.restDay) "Not a rest day after all" else "Make today a rest day") }
        }

        SectionCard("Active minutes this week") {
            LinearProgressIndicator(
                progress = { h.guidance.fraction.toFloat() },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${h.guidance.equivMin} of 150 active minutes this week" },
            )
            Text("${h.guidance.equivMin} of ${com.walkbuddy.domain.WeeklyGuidance.TARGET_MIN} minutes", style = MaterialTheme.typography.titleMedium)
            Disclaimer(Copy.ACTIVE_GUIDANCE)
            if (h.streak.current > 0) Text("${h.streak.current} day streak. Rest days and a spare token keep it gentle.", style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard("Walk") {
            Button(onClick = { withLocation { vm.startLobby(null) } }, modifier = Modifier.fillMaxWidth()) { Text("Start a walk together") }
            OutlinedButton(onClick = { joinDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Join with a code or link") }
            OutlinedButton(onClick = { withLocation { vm.startLobby(null, solo = true) } }, modifier = Modifier.fillMaxWidth()) { Text("Walk solo") }
            if (!perms.location) Disclaimer("Location is only used during a walk. You will be asked when you start one.")
            Disclaimer(Copy.SHARE_LOCATION)
        }

        Disclaimer(Copy.WELLNESS)
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
