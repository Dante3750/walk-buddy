package com.walkbuddy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.walkbuddy.domain.LinkMode
import com.walkbuddy.domain.LocationAction
import com.walkbuddy.domain.LocationNotice

/**
 * A small label for how the link to the buddy is doing: Direct, Via server, Reconnecting. A dot AND words, so colour never carries
 * the meaning alone, and never a warning colour: a slower path is normal, not an error.
 */
@Composable
fun LinkChip(mode: LinkMode, modifier: Modifier = Modifier) {
    val dot = when (mode) {
        LinkMode.Direct -> MaterialTheme.colorScheme.secondary
        LinkMode.ViaServer -> MaterialTheme.colorScheme.primary
        LinkMode.Reconnecting -> MaterialTheme.colorScheme.tertiary
        LinkMode.Waiting, LinkMode.Offline -> MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Connection: ${mode.label}. ${mode.explanation}" },
        shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.size(9.dp), shape = CircleShape, color = dot) {}
            Text(mode.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Why the map or the Track may be empty, in words, with the one button that fixes it. */
@Composable
fun LocationNoticeCard(notice: LocationNotice, onAction: (LocationAction) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${notice.title}. ${notice.body}" },
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(notice.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(notice.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            val label = notice.actionLabel
            if (label != null && notice.action != LocationAction.None) {
                Button(onClick = { onAction(notice.action) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            }
        }
    }
}
