package com.walkbuddy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkbuddy.domain.Mood
import com.walkbuddy.domain.MoodNote

/** Five faces in a row. Each is a 56 dp radio button with a spoken label; the choice is never colour-only. */
@Composable
fun MoodFaces(selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Mood.values().forEach { m ->
            val on = selected == m.score
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 56.dp, minHeight = 56.dp)
                        .clip(CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .selectable(selected = on, onClick = { onSelect(m.score) }, role = Role.RadioButton)
                        .semantics { contentDescription = m.label },
                    contentAlignment = Alignment.Center,
                ) { Text(m.emoji, fontSize = 28.sp) }
                Text(m.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A one-line note field plus the faces. Everything stays on this phone and is never shared with a buddy. */
@Composable
fun MoodCheckIn(onSave: (mood: Int, note: String) -> Unit, modifier: Modifier = Modifier, saveLabel: String = "Save check-in") {
    var mood by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MoodFaces(mood, { mood = it })
        OutlinedTextField(
            value = note, onValueChange = { note = it.take(MoodNote.MAX) }, singleLine = true,
            label = { Text("One line, if you like") }, modifier = Modifier.fillMaxWidth(),
        )
        TextButton(enabled = mood != null, onClick = { mood?.let { onSave(it, note) } }) { Text(saveLabel) }
        Text("Only on this phone. Your buddy never sees it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MoodDialog(onSave: (Int, String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How are you feeling?") },
        text = { MoodCheckIn(onSave = { m, n -> onSave(m, n); onDismiss() }) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.padding(end = 4.dp)) { Text("Not now") } },
    )
}
