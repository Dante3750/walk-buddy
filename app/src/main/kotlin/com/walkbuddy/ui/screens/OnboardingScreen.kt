@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.walkbuddy.domain.Copy
import com.walkbuddy.domain.RingBuddy
import com.walkbuddy.domain.UnitSystem
import com.walkbuddy.ui.PermState
import com.walkbuddy.ui.components.StepHero
import com.walkbuddy.ui.rememberPermState
import com.walkbuddy.ui.rememberPermissionRequester
import com.walkbuddy.ui.theme.WbTheme
import kotlinx.coroutines.delay

private const val PAGES = 3

/**
 * Three short steps. 1: hello and your name, with the hero ring counting up as a taste of what is coming.
 * 2: make it yours (units, step counting, gentle notifications). 3: walking with someone, then start or try the demo.
 * Every permission is optional, and location is asked for later, when you start your first walk.
 */
@Composable
fun OnboardingScreen(onName: (String) -> Unit, onUnits: (UnitSystem) -> Unit, onFinish: (demo: Boolean) -> Unit) {
    val perms = rememberPermState()
    val reduce = WbTheme.motion.reduceMotion
    var page by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var units by remember { mutableStateOf(UnitSystem.Metric) }
    val askActivity = rememberPermissionRequester(perms)
    val askNotifications = rememberPermissionRequester(perms)

    BackHandler(enabled = page > 0) { page -= 1 }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.SpaceBetween) {
        AnimatedContent(
            targetState = page, modifier = Modifier.weight(1f), label = "onboarding",
            transitionSpec = {
                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                else (slideInHorizontally { it / 5 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 5 } + fadeOut())
            },
        ) { p ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (p) {
                    0 -> {
                        var shown by remember { mutableIntStateOf(0) }
                        LaunchedEffect(Unit) { delay(350); shown = 6_284 }
                        StepHero(
                            steps = shown, goal = 8_000, buddies = listOf(RingBuddy("preview", "Sam", 5_200, 8_000)),
                            walking = true, maxSize = 270.dp,
                        )
                        Text("Walk Buddy", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Text(
                            "Your steps, big and honest. Walk with someone you love and watch the day fill up, side by side.",
                            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = name, onValueChange = { name = it.take(24) }, label = { Text("What should your buddy call you?") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    1 -> {
                        Text("Make it yours", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Text("Distances in", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = units == UnitSystem.Metric, onClick = { units = UnitSystem.Metric; onUnits(units) }, label = { Text("Kilometres") }, modifier = Modifier.heightIn(min = 48.dp))
                            FilterChip(selected = units == UnitSystem.Imperial, onClick = { units = UnitSystem.Imperial; onUnits(units) }, label = { Text("Miles") }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                        PermissionCard(
                            title = "Count my steps",
                            body = "Android calls this physical activity. Walk Buddy reads your phone's built-in step counter. Nothing leaves your phone.",
                            granted = perms.activity, allow = { askActivity(PermState.ACTIVITY) },
                        )
                        PermissionCard(
                            title = "Gentle notifications",
                            body = "For the walk-in-progress notice and reminders you choose. Quiet hours and quiet mode are in Settings.",
                            granted = perms.notifications, allow = { askNotifications(PermState.NOTIFICATIONS) },
                        )
                        Text(
                            "Location is only asked for when you start a walk, and sharing stops when the walk ends.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    }
                    else -> {
                        Text("Walk with someone", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Text(
                            "Start a walk, share the code or QR, and your buddy appears on your ring. Steps and places go straight between your phones.",
                            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(Copy.LOCAL_ONLY, textAlign = TextAlign.Center)
                        Text("No accounts, no ads, no analytics, no voice or video.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Copy.WELLNESS, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = "Step ${page + 1} of $PAGES" }) {
                repeat(PAGES) { i ->
                    Surface(
                        shape = CircleShape, modifier = Modifier.size(if (i == page) 22.dp else 8.dp, 8.dp),
                        color = if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ) {}
                }
            }
            Spacer(Modifier.size(4.dp))
            if (page < PAGES - 1) {
                Button(
                    onClick = { if (page == 0) onName(name.trim()); page += 1 },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Continue") }
            } else {
                Button(onClick = { onFinish(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Start walking") }
                OutlinedButton(onClick = { onFinish(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Look around with demo data first") }
            }
            if (page > 0) TextButton(onClick = { page -= 1 }) { Text("Back") }
        }
    }
}

@Composable
private fun PermissionCard(title: String, body: String, granted: Boolean, allow: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (granted) Text("Allowed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            else OutlinedButton(onClick = allow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow") }
        }
    }
}
