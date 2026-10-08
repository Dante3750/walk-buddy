@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.BadgeRow
import com.walkbuddy.ui.components.BadgeMedal
import java.text.DateFormat
import java.util.Date

@Composable
fun BadgesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val badges by vm.badges.collectAsStateWithLifecycle()
    BadgesContent(badges, onBack)
}

@Composable
fun BadgesContent(badges: List<BadgeRow>, onBack: () -> Unit) {
    val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Badges", style = MaterialTheme.typography.headlineSmall)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "${badges.count { it.earned }} of ${badges.size} earned. Badges are for fun and never ranked against anyone.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(badges, key = { it.id.name }) { b ->
                val status = if (b.earned) "earned" + (b.unlockedMs?.let { " on " + fmt.format(Date(it)) } ?: "") else "locked, ${b.progress.text}"
                Column(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${b.id.title}. ${b.id.blurb} $status" },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BadgeMedal(b.id, b.earned, b.progress.fraction.toFloat())
                    Text(b.id.title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                    Text(
                        if (b.earned) (b.unlockedMs?.let { fmt.format(Date(it)) } ?: "Earned") else b.progress.text,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        color = if (b.earned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
