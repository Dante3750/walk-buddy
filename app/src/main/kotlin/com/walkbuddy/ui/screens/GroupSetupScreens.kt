@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import androidx.compose.foundation.horizontalScroll
import com.walkbuddy.ui.components.staggerIn
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.data.Settings
import com.walkbuddy.domain.CollectiveSteps
import com.walkbuddy.domain.Copy
import com.walkbuddy.domain.Invite
import com.walkbuddy.domain.Invites
import com.walkbuddy.domain.LocationPrecision
import com.walkbuddy.session.CreateGroupOptions
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.PermState
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.components.ScreenTitle
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.ToggleRow
import com.walkbuddy.ui.rememberPermState
import com.walkbuddy.ui.rememberPermissionRequester

/** How precisely to share, with a plain-words explanation for each choice. */
@Composable
fun PrecisionPicker(selected: LocationPrecision, onSelect: (LocationPrecision) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("How precisely to share my location", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            LocationPrecision.values().forEach { p ->
                FilterChip(selected = selected == p, onClick = { onSelect(p) }, label = { Text(p.label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Disclaimer(
            when (selected) {
                LocationPrecision.Exact -> "The group sees where you are on the map."
                LocationPrecision.Approx100 -> "The group sees a point within about 70 m of you. Good for a crowd you do not know well."
                LocationPrecision.Approx500 -> "The group sees a point within about 350 m of you. Distances to you get rough, and nudges are relaxed."
            } + " You always see yourself exactly.",
        )
    }
}

private val GoalChoices = listOf(0, 10_000, 25_000, 50_000, 100_000)

/** Host setup: a name, who approves, how long it lasts, a collective goal, and how precisely to share. */
@Composable
fun GroupCreateScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings
    val perms = rememberPermState()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberPermissionRequester(perms) { pending?.invoke(); pending = null }
    GroupCreateContent(
        s = s,
        onBack = onBack,
        onCreate = { opts ->
            val go = { vm.createGroup(opts) }
            // Location is optional: without it you still join, see the group on the map and add your steps.
            if (perms.location) go() else { pending = go; ask(PermState.LOCATION) }
        },
    )
}

@Composable
fun GroupCreateContent(s: Settings?, onBack: () -> Unit, onCreate: (CreateGroupOptions) -> Unit) {
    if (s == null) { EmptyState("Loading", "One moment."); return }
    var nick by remember(s.displayName) { mutableStateOf(s.displayName) }
    var title by remember { mutableStateOf("") }
    var approval by remember { mutableStateOf(false) }
    var hours by remember { mutableStateOf(4) }
    var goal by remember { mutableStateOf(0) }
    var precision by remember(s.groupPrecision) { mutableStateOf(s.groupPrecision) }
    var server by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }
    val serverOk = server.trim().let { it.startsWith("wss://") || it.startsWith("ws://") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("Create a group walk", subtitle = "Anyone you invite can join, even after you have started.")
        SectionCard("The basics", Modifier.staggerIn(0)) {
            OutlinedTextField(value = nick, onValueChange = { nick = it.take(24) }, label = { Text("Your name in the group") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = title, onValueChange = { title = it.take(40) }, label = { Text("Group name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ToggleRow("I approve each person", "Off: anyone with the QR or code joins straight away. On: you tap Let in for each request.", approval) { approval = it }
        }
        SectionCard("How long it lasts", Modifier.staggerIn(1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 4, 8).forEach { h ->
                    FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text("$h h") }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Disclaimer("The group closes by itself after this, or sooner if you end it. Nothing is kept on the server.")
        }
        SectionCard("A goal for everyone together (optional)", Modifier.staggerIn(2)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalChoices.take(4).forEach { g ->
                    FilterChip(selected = goal == g, onClick = { goal = g }, label = { Text(if (g == 0) "No goal" else "%,d".format(g)) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Disclaimer("Everyone's steps add up to one shared number. There is no ranking. A friendly size for 10 people is about ${"%,d".format(CollectiveSteps.suggestGoal(10))}.")
        }
        SectionCard("Privacy") {
            PrecisionPicker(precision) { precision = it }
        }
        SectionCard("Server") {
            Text("Open groups relay live updates through a server. Enter the address of one you trust, or run your own (see the server folder in the project).", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = server, onValueChange = { server = it.take(200) }, label = { Text("wss://your-server.example") }, singleLine = true,
                isError = server.isNotBlank() && !serverOk, modifier = Modifier.fillMaxWidth(),
                supportingText = { if (server.isNotBlank() && !serverOk) Text("Start with wss:// (or ws:// for a test on your own network)") },
            )
            Disclaimer("The server passes your position on to the group while you walk. It does not store it.")
        }
        Button(
            enabled = serverOk,
            onClick = {
                onCreate(
                    CreateGroupOptions(
                        nickname = nick.trim().ifBlank { "Host" }, title = title.trim(), approval = approval, ttlMin = hours * 60,
                        goalSteps = goal, precision = precision, serverUrl = server.trim(),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Create group", style = MaterialTheme.typography.titleMedium) }
        OutlinedButton(onClick = onBack, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back") }
        Disclaimer(Copy.SHARE_LOCATION)
    }
}

/** Join by pasting a link or typing the 6-character code. Scanning has its own screen. */
@Composable
fun GroupJoinScreen(vm: AppViewModel, initial: String, onScan: () -> Unit, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings
    val perms = rememberPermState()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberPermissionRequester(perms) { pending?.invoke(); pending = null }
    GroupJoinContent(
        s = s, initial = initial, onScan = onScan, onBack = onBack,
        onJoin = { input, nick, server, precision ->
            val go = { vm.joinGroup(input, nick, server, precision) }
            if (perms.location) go() else { pending = go; ask(PermState.LOCATION) }
        },
    )
}

@Composable
fun GroupJoinContent(
    s: Settings?, initial: String, onScan: () -> Unit, onBack: () -> Unit,
    onJoin: (input: String, nickname: String, server: String?, precision: LocationPrecision) -> Unit,
) {
    if (s == null) { EmptyState("Loading", "One moment."); return }
    var input by remember(initial) { mutableStateOf(initial) }
    var nick by remember(s.displayName) { mutableStateOf(s.displayName) }
    var precision by remember(s.groupPrecision) { mutableStateOf(s.groupPrecision) }
    var server by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }
    val parsed = Invites.parse(input)
    val bare = Invites.bareCode(input)
    val isPartnerLink = parsed is Invite.Partner
    val valid = parsed is Invite.Group || (parsed == null && bare != null)
    val needsServer = (parsed as? Invite.Group)?.serverUrl == null && s.serverUrl.isBlank()
    val serverOk = !needsServer || server.trim().let { it.startsWith("wss://") || it.startsWith("ws://") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("Join a group walk", subtitle = "Scan the QR code, paste the invite link, or type the 6-character code.")
        Button(onClick = onScan, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Scan a QR code", style = MaterialTheme.typography.titleMedium) }
        SectionCard("Link or code") {
            OutlinedTextField(
                value = input, onValueChange = { input = it.take(300) }, label = { Text("Invite link or code") }, singleLine = true,
                isError = input.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    when {
                        isPartnerLink -> Text("That is a partner-walk link. Go back and choose Walk with partner.")
                        input.isNotBlank() && !valid -> Text("That does not look like a group link or code")
                    }
                },
            )
            OutlinedTextField(value = nick, onValueChange = { nick = it.take(24) }, label = { Text("Your name in the group") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (needsServer) {
                OutlinedTextField(
                    value = server, onValueChange = { server = it.take(200) }, label = { Text("Server, for example wss://your-server.example") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Disclaimer("A bare code does not say which server the group is on. Links and QR codes do.")
            }
        }
        SectionCard("Privacy") { PrecisionPicker(precision) { precision = it } }
        Button(
            enabled = valid && serverOk,
            onClick = { onJoin(input.trim(), nick.trim().ifBlank { "Walker" }, server.trim().takeIf { needsServer && it.isNotBlank() }, precision) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Join group", style = MaterialTheme.typography.titleMedium) }
        OutlinedButton(onClick = onBack, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back") }
        Disclaimer(Copy.SHARE_LOCATION)
    }
}
