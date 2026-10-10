@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.domain.Format
import com.walkbuddy.domain.LatLon
import com.walkbuddy.domain.Polyline
import com.walkbuddy.domain.SharedHistory
import com.walkbuddy.domain.SharedMode
import com.walkbuddy.domain.SharedReplay
import com.walkbuddy.domain.SharedWalkExport
import com.walkbuddy.domain.SharedWalkRecord
import com.walkbuddy.domain.TrackBuilders
import com.walkbuddy.domain.UnitSystem
import com.walkbuddy.domain.Units
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.components.ScreenTitle
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.StatLine
import com.walkbuddy.ui.components.TrackView
import com.walkbuddy.ui.components.WalkerBadge
import com.walkbuddy.ui.components.cardBorder
import com.walkbuddy.ui.components.cardColor
import java.io.File
import kotlinx.coroutines.delay

/** "Walks together": every finished partner or group walk, newest first, grouped by month. Stays on this phone. */
@Composable
fun HistoryScreen(vm: AppViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val walks by vm.sharedWalks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val unit = settings?.unitSystem ?: UnitSystem.Metric
    val ctx = LocalContext.current
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val months = remember(walks) { SharedHistory.groupByMonth(walks) }
    val totals = remember(walks) { SharedHistory.totals(walks) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
            }
            ScreenTitle("Walks together", subtitle = "Every walk you shared with a buddy or a group. It lives on this phone only.")
        }
        if (walks.isEmpty()) {
            item {
                EmptyState(
                    "No walks together yet",
                    "When you finish a walk with a buddy or a group, it is saved here with a replay of how you moved along the Track.",
                )
            }
        } else {
            item {
                SectionCard(null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Total("${totals.count}", if (totals.count == 1) "walk" else "walks")
                        Total(Units.distance(totals.distanceM, unit), "together")
                        Total(Format.duration(totals.durationMs), "walking")
                        Total("${totals.avgTogetherPct}%", "side by side")
                    }
                }
            }
            months.forEach { month ->
                item(key = "m-${month.year}-${month.month}") {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(month.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${month.items.size} ${if (month.items.size == 1) "walk" else "walks"}  ·  ${Units.distance(month.distanceM, unit)}  ·  ${Format.duration(month.durationMs)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(month.items, key = { it.id }) { r -> WalkRow(r, unit) { onOpen(r.id) } }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { shareFile(ctx, "walks-together.json", SharedWalkExport.json(walks), "application/json", "Share walks (JSON)") },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("Export all (JSON)") }
                    OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Clear history") }
                }
                Disclaimer("The export is a file you choose where to send. Walk Buddy never uploads your walks.")
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all walks together?") },
            text = { Text("This removes the saved shared walks from this phone. Your daily steps and solo walks are not touched.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearSharedWalks() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Total(big: String, small: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(big, style = MaterialTheme.typography.titleMedium)
        Text(small, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WalkRow(r: SharedWalkRecord, unit: UnitSystem, onClick: () -> Unit) {
    val title = SharedHistory.title(r)
    val day = SharedHistory.dayTitle(r)
    Surface(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open this walk", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$day. $title. ${SharedHistory.subtitle(r, unit)}" },
        shape = com.walkbuddy.ui.components.CardShape, color = cardColor(), border = cardBorder(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                r.lanes.take(3).forEach { l -> WalkerBadge(l.avatar, size = 40.dp) }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(day, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(SharedHistory.subtitle(r, unit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One shared walk: the Track replayed (play, pause, scrub) and the numbers. */
@Composable
fun HistoryDetailScreen(vm: AppViewModel, id: Long, onBack: () -> Unit) {
    val walks by vm.sharedWalks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val unit = settings?.unitSystem ?: UnitSystem.Metric
    val ctx = LocalContext.current
    val r = walks.firstOrNull { it.id == id }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    if (r == null) {
        // Deleted (or never existed): nothing to show, go back.
        Column(Modifier.padding(16.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            EmptyState("This walk is gone", "It may have been deleted.")
        }
        return
    }
    val total = SharedReplay.durationSec(r).coerceAtLeast(1)
    var pos by rememberSaveable(r.id) { mutableFloatStateOf(0f) }
    var playing by rememberSaveable(r.id) { mutableStateOf(false) }
    // Playback: the whole walk in about 40 seconds at most. Never starts by itself; the slider always works.
    LaunchedEffect(playing, r.id) {
        if (!playing) return@LaunchedEffect
        val speed = maxOf(1f, total / 40f)
        if (pos >= total) pos = 0f
        while (playing) {
            delay(100)
            pos = minOf(total.toFloat(), pos + speed * 0.1f)
            if (pos >= total) playing = false
        }
    }
    val walkers = remember(r, pos) { SharedReplay.walkers(r, pos.toDouble()) }
    val gap = remember(r, pos) { replayGap(r, pos) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        ScreenTitle(SharedHistory.title(r), subtitle = SharedHistory.dayTitle(r) + "  ·  " + SharedHistory.subtitle(r, unit))
        TrackView(walkers = walkers, realGapM = gap, unit = unit, still = true, modifier = Modifier.fillMaxWidth().height(260.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { playing = !playing }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (playing) "Pause" else "Play") }
            Text(SharedHistory.clock(pos.toInt()) + " / " + SharedHistory.clock(total), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = pos, onValueChange = { playing = false; pos = it }, valueRange = 0f..total.toFloat(),
            modifier = Modifier.semantics { contentDescription = "Replay position" },
        )
        SectionCard("The walk") {
            StatLine("Duration", Format.duration(r.durationMs))
            StatLine("Distance (you)", Units.distance(r.myDistanceM, unit))
            StatLine("Steps (you)", "${r.mySteps}")
            r.others.forEach { o -> StatLine("Steps (${o.name})", "${o.steps}") }
            StatLine("People", "${r.memberCount}")
        }
        SectionCard("Together") {
            StatLine("Time side by side", "${r.togetherPct}%  (${Format.duration(r.timeTogetherMs)})")
            StatLine("Longest stretch together", Format.duration(r.longestTogetherMs))
            StatLine("Furthest apart", Units.distance(r.maxGapM, unit))
        }
        RouteSection(r)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { shareFile(ctx, SharedWalkExport.fileName(r, ext = "json"), SharedWalkExport.json(listOf(r)), "application/json", "Share walk (JSON)") },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Share JSON") }
            val gpx = remember(r) { SharedWalkExport.gpx(r) }
            if (gpx != null) {
                OutlinedButton(
                    onClick = { shareFile(ctx, SharedWalkExport.fileName(r, ext = "gpx"), gpx, "application/gpx+xml", "Share my route (GPX)") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Share route (GPX)") }
            }
        }
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Delete this walk") }
        Disclaimer("Only your own route is ever kept, and only if you turned on route saving. Your buddy's position is never stored as a map.")
        Spacer(Modifier.height(8.dp))
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this walk?") },
            text = { Text("It is removed from this phone. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSharedWalk(r.id); onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

/** The gap to show during the replay: along the line for the pair or the front to the back in a group. */
private fun replayGap(r: SharedWalkRecord, pos: Float): Double? {
    val xs = SharedReplay.positionsAt(r, pos.toDouble()).filterNotNull()
    return if (xs.size >= 2) xs.max() - xs.min() else null
}

/** My own route as a small picture, when route saving was on for this walk. */
@Composable
private fun RouteSection(r: SharedWalkRecord) {
    val pts = remember(r.routePolyline) { Polyline.decode(r.routePolyline) }
    if (pts.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.secondary
    SectionCard("My route") {
        Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = "Outline of my route" }) {
            val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
            val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
            val cosLat = Math.cos(Math.toRadians((minLat + maxLat) / 2.0))
            val wDeg = (maxLon - minLon) * cosLat
            val hDeg = maxLat - minLat
            val scale = minOf(size.width / (wDeg.toFloat().coerceAtLeast(1e-7f)), size.height / (hDeg.toFloat().coerceAtLeast(1e-7f))) * 0.9f
            val offX = (size.width - wDeg.toFloat() * scale) / 2f
            val offY = (size.height - hDeg.toFloat() * scale) / 2f
            fun at(p: LatLon) = Offset(offX + ((p.lon - minLon) * cosLat).toFloat() * scale, offY + ((maxLat - p.lat)).toFloat() * scale)
            val path = Path()
            pts.forEachIndexed { i, p -> val o = at(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(dot, 6.dp.toPx(), at(pts.first()))
            drawCircle(line, 6.dp.toPx(), at(pts.last()))
        }
        Disclaimer("An outline only, drawn from points saved on this phone.")
    }
}

/** Writes [text] to the app's share cache and offers it to the system share sheet. Nothing is uploaded by Walk Buddy. */
internal fun shareFile(ctx: Context, name: String, text: String, mime: String, chooserTitle: String) {
    try {
        val dir = File(ctx.cacheDir, "shared").also { it.mkdirs() }
        val f = File(dir, name)
        f.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, chooserTitle))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app to share with", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(ctx, "Could not prepare the file", Toast.LENGTH_SHORT).show()
    }
}
