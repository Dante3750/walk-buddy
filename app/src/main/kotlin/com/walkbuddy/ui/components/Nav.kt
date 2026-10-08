package com.walkbuddy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

enum class TabGlyph { Today, Trends, Fuel, Us, Settings }

@Immutable
data class NavItem(val label: String, val glyph: TabGlyph)

/** One consistent set of 2 dp rounded line icons, drawn in code so they match the brand and need no icon library. */
@Composable
fun TabIcon(glyph: TabGlyph, selected: Boolean, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(26.dp)) {
        val w = size.width
        val sw = (if (selected) 2.4.dp else 2.dp).toPx()
        val line = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            TabGlyph.Today -> {
                drawCircle(color, radius = w * 0.38f, center = center, style = line)
                drawCircle(color, radius = w * 0.14f, center = center)
            }
            TabGlyph.Trends -> {
                listOf(0.22f to 0.62f, 0.5f to 0.34f, 0.78f to 0.14f).forEach { (x, top) ->
                    drawLine(color, Offset(w * x, w * 0.88f), Offset(w * x, w * (top + 0.1f)), strokeWidth = sw * 1.5f, cap = StrokeCap.Round)
                }
            }
            TabGlyph.Fuel -> {
                val p = Path().apply {
                    moveTo(w * 0.5f, w * 0.1f)
                    cubicTo(w * 0.86f, w * 0.48f, w * 0.84f, w * 0.9f, w * 0.5f, w * 0.9f)
                    cubicTo(w * 0.16f, w * 0.9f, w * 0.14f, w * 0.48f, w * 0.5f, w * 0.1f)
                    close()
                }
                drawPath(p, color, style = line)
            }
            TabGlyph.Us -> {
                drawCircle(color, radius = w * 0.24f, center = Offset(w * 0.35f, w * 0.5f), style = line)
                drawCircle(color, radius = w * 0.24f, center = Offset(w * 0.65f, w * 0.5f), style = line)
            }
            TabGlyph.Settings -> {
                listOf(0.27f to 0.64f, 0.5f to 0.34f, 0.73f to 0.58f).forEach { (y, knob) ->
                    drawLine(color, Offset(w * 0.12f, w * y), Offset(w * 0.88f, w * y), strokeWidth = sw, cap = StrokeCap.Round)
                    drawCircle(color, radius = w * 0.1f, center = Offset(w * knob, w * y))
                }
            }
        }
    }
}

@Composable
fun WbBottomBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = cardColor(), tonalElevation = 0.dp) {
        items.forEachIndexed { i, item ->
            NavigationBarItem(
                selected = i == selected, onClick = { onSelect(i) },
                icon = { TabIcon(item.glyph, i == selected) },
                label = { Text(item.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
fun WbRail(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    NavigationRail(containerColor = cardColor()) {
        items.forEachIndexed { i, item ->
            NavigationRailItem(
                selected = i == selected, onClick = { onSelect(i) },
                icon = { TabIcon(item.glyph, i == selected) },
                label = { Text(item.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
