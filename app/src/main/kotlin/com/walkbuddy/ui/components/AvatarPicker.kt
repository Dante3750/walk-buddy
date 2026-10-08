package com.walkbuddy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.walkbuddy.domain.AvatarPalette
import com.walkbuddy.domain.AvatarStyle
import com.walkbuddy.domain.Avatar as WalkerAvatar

/** Choose the look (boy, girl or round) and the colour of your walker. Every choice has a text label for screen readers. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AvatarPicker(current: WalkerAvatar, onChange: (WalkerAvatar) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarStyle.values().forEach { style ->
                val sel = style == current.style
                Box(
                    Modifier.size(72.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
                        .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                        .clickable(role = Role.RadioButton) { onChange(WalkerAvatar(style, current.colorIndex)) }
                        .semantics { selected = sel; contentDescription = "${style.label} walker" },
                    contentAlignment = Alignment.Center,
                ) {
                    WalkerBadge(WalkerAvatar(style, current.colorIndex), size = 56.dp)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until AvatarPalette.SIZE) {
                val sel = i == current.colorIndex
                Box(
                    Modifier.size(48.dp)
                        .clickable(role = Role.RadioButton) { onChange(WalkerAvatar(current.style, i)) }
                        .semantics { selected = sel; contentDescription = "Colour ${WalkerPalette.names[i]}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(34.dp).background(WalkerPalette.color(i), CircleShape)
                            .border(if (sel) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                    )
                }
            }
        }
        Text("${current.style.label}, ${WalkerPalette.names[current.colorIndex].lowercase()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
