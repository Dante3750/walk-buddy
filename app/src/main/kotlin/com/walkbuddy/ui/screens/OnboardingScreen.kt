@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.walkbuddy.domain.Copy
import com.walkbuddy.ui.PermState
import com.walkbuddy.ui.rememberPermState
import com.walkbuddy.ui.rememberPermissionRequester

private data class Step(val title: String, val body: String)

/** Staged onboarding: each permission is explained right before it is requested, and each can be skipped. */
@Composable
fun OnboardingScreen(onName: (String) -> Unit, onFinish: () -> Unit) {
    val perms = rememberPermState()
    var page by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    val next = { page += 1 }
    val askLocation = rememberPermissionRequester(perms) { next() }
    val askActivity = rememberPermissionRequester(perms) { next() }
    val askNotifications = rememberPermissionRequester(perms) { next() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Walk Buddy", style = MaterialTheme.typography.headlineMedium)
        when (page) {
            0 -> {
                Text("Walk together, see each other's steps and pace live, and build a healthy habit without racing.")
                Text(Copy.LOCAL_ONLY, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("No accounts, no ads, no analytics, no voice or video.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(24) }, label = { Text("What should your buddy call you?") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onName(name.trim()); next() }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            }
            1 -> PermissionPage(
                title = "Location while you walk",
                body = "Used only during a walk, to work out distance and how far apart you and your buddies are. It is shared live only with the people you invite, and sharing stops when the walk ends. A notification stays visible while it is on.",
                granted = perms.location,
                allow = { askLocation(PermState.LOCATION) },
                skip = next,
            )
            2 -> PermissionPage(
                title = "Step counting",
                body = "Android calls this 'physical activity'. It lets Walk Buddy read your phone's built-in step counter. Nothing leaves your phone.",
                granted = perms.activity,
                allow = { askActivity(PermState.ACTIVITY) },
                skip = next,
            )
            3 -> PermissionPage(
                title = "Gentle notifications",
                body = "For the walk-in-progress notice, occasional nudges and reminders you turn on. You stay in control and can mute everything with Quiet mode.",
                granted = perms.notifications,
                allow = { askNotifications(PermState.NOTIFICATIONS) },
                skip = next,
            )
            else -> {
                Text("Before you start", style = MaterialTheme.typography.titleLarge)
                Text(Copy.WELLNESS)
                Text(Copy.SHARE_LOCATION)
                Text(Copy.SHARING_ENDS)
                Text("You can change any permission later in Android settings. Everything still works in a limited way without them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Start") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Step ${page.coerceAtMost(4) + 1} of 5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionPage(title: String, body: String, granted: Boolean, allow: () -> Unit, skip: () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(body)
    if (granted) {
        Text("Already allowed", color = MaterialTheme.colorScheme.primary)
        Button(onClick = skip, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    } else {
        Button(onClick = allow, modifier = Modifier.fillMaxWidth()) { Text("Allow") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = skip) { Text("Not now") }
        }
    }
}
