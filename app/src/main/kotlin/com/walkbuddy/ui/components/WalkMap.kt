@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkbuddy.domain.Geo
import com.walkbuddy.domain.GroupRole
import com.walkbuddy.domain.LatLon
import com.walkbuddy.domain.MapCamera
import com.walkbuddy.domain.MapFollow
import com.walkbuddy.domain.MapViewport
import com.walkbuddy.domain.MeetingPin
import com.walkbuddy.domain.ScaleBar
import com.walkbuddy.domain.SlippyTiles
import com.walkbuddy.domain.WebMercator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** One person on the map. Trails and positions are whatever the walk engine knows; nothing here is stored. */
@Immutable
data class MapPerson(
    val id: String,
    val name: String,
    val pos: LatLon?,
    val trail: List<LatLon> = emptyList(),
    val isMe: Boolean = false,
    /** Not heard from for a while. Drawn faded. */
    val stale: Boolean = false,
    /** A little apart from the group. Drawn with a soft ring, never with a warning colour. */
    val apart: Boolean = false,
    val role: GroupRole? = null,
)

object MapColors {
    private val palette = listOf(
        Color(0xFFC2410C), Color(0xFF0E7490), Color(0xFF6D28D9), Color(0xFF15803D), Color(0xFFBE185D),
        Color(0xFF1D4ED8), Color(0xFF9A6700), Color(0xFF475569), Color(0xFF9333EA), Color(0xFF0F766E),
    )

    /** A stable colour per person id. Colour never carries the meaning alone: every dot also shows an initial. */
    fun forId(id: String): Color = palette[(id.hashCode() and 0x7fffffff) % palette.size]
}

private fun initial(name: String) = name.trim().take(1).uppercase().ifEmpty { "?" }

/**
 * The map used by both partner and group walks. Default: an OFFLINE canvas view (a soft grid, trails, coloured dots with initials,
 * a meeting-point flag and a scale bar), so no tile server ever learns where you are. An optional OpenStreetMap layer can be turned
 * on in Settings ([tilesEnabled]); it draws the required attribution.
 */
@Composable
fun WalkMap(
    people: List<MapPerson>,
    pin: MeetingPin?,
    tilesEnabled: Boolean,
    imperial: Boolean,
    canPin: Boolean,
    onSetPin: (LatLon) -> Unit,
    onClearPin: () -> Unit,
    modifier: Modifier = Modifier,
    initialFollow: MapFollow = MapFollow.Group,
    /** Why my own position may be missing, with the one button that fixes it. Null when all is well. */
    notice: com.walkbuddy.domain.LocationNotice? = null,
    onNoticeAction: (com.walkbuddy.domain.LocationAction) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var followName by rememberSaveable { mutableStateOf(initialFollow.name) }
    val follow = MapFollow.values().firstOrNull { it.name == followName } ?: MapFollow.Group
    var vp by remember { mutableStateOf(MapViewport(LatLon(20.0, 0.0), 3.0, 1, 1)) }
    val loaded = remember { mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>() }
    val canPinNow by rememberUpdatedState(canPin)
    val onSetPinNow by rememberUpdatedState(onSetPin)

    val me = people.firstOrNull { it.isMe }?.pos
    val others = people.filter { !it.isMe && !it.stale }.mapNotNull { it.pos }
    val located = people.count { it.pos != null }

    // Keep the camera where the follow mode says. Free mode (after a drag or pinch) leaves it alone.
    LaunchedEffect(size, me, others, pin?.pos, follow) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        val base = vp.resized(size.width, size.height)
        val t = MapCamera.target(follow, me, others, pin?.pos, base)
        vp = if (t == null) base else t.copy(zoom = floor(t.zoom * 4.0) / 4.0)
    }

    // Optional tile layer: load what the current view needs, a moment after the view settles.
    val tileRefs = if (tilesEnabled && size.width > 0) SlippyTiles.tilesFor(vp) else emptyList()
    val tileKeys = tileRefs.map { TileLoader.key(it.z, it.x, it.y) }
    LaunchedEffect(tileKeys, tilesEnabled) {
        if (!tilesEnabled) return@LaunchedEffect
        for (k in tileKeys) TileLoader.cached(k)?.let { loaded[k] = it }
        delay(250)
        coroutineScope {
            tileRefs.filter { loaded[TileLoader.key(it.z, it.x, it.y)] == null }.map { r ->
                async { TileLoader.load(r.z, r.x, r.y)?.let { loaded[TileLoader.key(r.z, r.x, r.y)] = it } }
            }.awaitAll()
        }
    }

    val bg = MaterialTheme.colorScheme.surfaceContainerLow
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val panel = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
    val meColor = MaterialTheme.colorScheme.primary
    val pinColor = MaterialTheme.colorScheme.tertiary
    val summary = remember(people, pin) { describe(people, pin) }

    Box(modifier.background(bg, RoundedCornerShape(20.dp)).onSizeChanged { size = it }) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = summary }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        followName = MapFollow.Free.name
                        vp = vp.panBy(pan.x.toDouble(), pan.y.toDouble()).zoomBy(zoom.toDouble(), centroid.x.toDouble(), centroid.y.toDouble())
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { o ->
                            followName = MapFollow.Free.name
                            vp = vp.zoomBy(2.0, o.x.toDouble(), o.y.toDouble())
                        },
                        onLongPress = { o -> if (canPinNow) onSetPinNow(vp.fromScreen(o.x.toDouble(), o.y.toDouble())) },
                    )
                },
        ) {
            val v = if (vp.widthPx == size.width && vp.heightPx == size.height) vp else vp.resized(size.width, size.height)
            if (tilesEnabled) drawTiles(v, loaded)
            drawGrid(v, grid.copy(alpha = if (tilesEnabled) 0f else 0.5f))
            for (p in people) drawTrail(v, p, if (p.isMe) meColor else MapColors.forId(p.id))
            pin?.let { drawPin(v, it, pinColor, ink, panel, measurer) }
            // Others first, me last so my dot is never hidden.
            for (p in people.sortedBy { it.isMe }) drawPerson(v, p, if (p.isMe) meColor else MapColors.forId(p.id), measurer)
            drawScaleBar(v, ink, panel, measurer, imperial)
            if (tilesEnabled) drawAttribution(ink, panel, measurer)
        }

        if (notice != null) {
            LocationNoticeCard(notice, onNoticeAction, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        } else if (located == 0) {
            Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = RoundedCornerShape(16.dp), color = panel) {
                Text("Waiting for a location fix. Stepping outside helps.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
            FilterChip(
                selected = follow == MapFollow.Me, onClick = { followName = MapFollow.Me.name },
                label = { Text("Follow me") }, modifier = Modifier.heightIn(min = 48.dp),
            )
            FilterChip(
                selected = follow == MapFollow.Group, onClick = { followName = MapFollow.Group.name },
                label = { Text("Whole group") }, modifier = Modifier.heightIn(min = 48.dp),
            )
            Surface(shape = RoundedCornerShape(24.dp), color = panel) {
                Column {
                    TextButton(onClick = { followName = MapFollow.Free.name; vp = vp.zoomBy(1.6) }, Modifier.size(48.dp)) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    TextButton(onClick = { followName = MapFollow.Free.name; vp = vp.zoomBy(1 / 1.6) }, Modifier.size(48.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
                }
            }
            if (pin != null && canPin) {
                FilterChip(selected = false, onClick = onClearPin, label = { Text("Clear pin") }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (canPin && pin == null && located > 0 && notice == null) {
            Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), shape = RoundedCornerShape(16.dp), color = panel) {
                Text("Press and hold the map to set a meeting point", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun describe(people: List<MapPerson>, pin: MeetingPin?): String {
    val me = people.firstOrNull { it.isMe }?.pos
    val placed = people.filter { !it.isMe }.mapNotNull { o -> o.pos?.let { p -> o to p } }
    val near = if (me == null) null else placed.minByOrNull { (_, p) -> Geo.haversine(me, p) }
    val nearText = if (me != null && near != null) " Nearest: ${near.first.name}, about ${Geo.haversine(me, near.second).roundToInt()} metres from you." else ""
    return "Map showing ${people.count { it.pos != null }} of ${people.size} people.$nearText" + if (pin != null) " A meeting point is set." else ""
}

private fun DrawScope.drawTiles(v: MapViewport, loaded: Map<String, androidx.compose.ui.graphics.ImageBitmap>) {
    for (t in SlippyTiles.tilesFor(v)) {
        val img = loaded[TileLoader.key(t.z, t.x, t.y)] ?: continue
        drawImage(
            img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(floor(t.screenX).toInt(), floor(t.screenY).toInt()),
            dstSize = IntSize(ceil(t.sizePx).toInt() + 1, ceil(t.sizePx).toInt() + 1),
        )
    }
}

/** A soft metric grid that moves with the map, so panning and zooming read clearly even with no tiles. */
private fun DrawScope.drawGrid(v: MapViewport, color: Color) {
    if (color.alpha <= 0f) return
    val mpp = v.metersPerPixel()
    if (!(mpp > 0.0)) return
    val stepM = ScaleBar.niceMeters(mpp * 90.0)
    val stepPx = (stepM / mpp)
    if (stepPx < 12.0) return
    val originX = WebMercator.x(v.center.lon, v.zoom) - v.widthPx / 2.0
    val originY = WebMercator.y(v.center.lat, v.zoom) - v.heightPx / 2.0
    var x = -(originX % stepPx)
    if (x > 0) x -= stepPx
    while (x < size.width) { drawLine(color, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height), 1.dp.toPx()); x += stepPx }
    var y = -(originY % stepPx)
    if (y > 0) y -= stepPx
    while (y < size.height) { drawLine(color, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), 1.dp.toPx()); y += stepPx }
}

private fun DrawScope.drawTrail(v: MapViewport, p: MapPerson, color: Color) {
    if (p.trail.size < 2) return
    val path = Path()
    p.trail.forEachIndexed { i, ll ->
        val s = v.toScreen(ll)
        if (i == 0) path.moveTo(s.x.toFloat(), s.y.toFloat()) else path.lineTo(s.x.toFloat(), s.y.toFloat())
    }
    drawPath(path, color.copy(alpha = if (p.stale) 0.25f else 0.6f), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawPerson(v: MapViewport, p: MapPerson, color: Color, m: TextMeasurer) {
    val ll = p.pos ?: return
    val s = v.toScreen(ll)
    val r = (if (p.isMe) 15.dp else 13.dp).toPx()
    val alpha = if (p.stale) 0.45f else 1f
    val inset = 22.dp.toPx()
    val inside = s.x in inset.toDouble()..(size.width - inset).toDouble() && s.y in inset.toDouble()..(size.height - inset).toDouble()
    var cx = s.x.toFloat(); var cy = s.y.toFloat()
    var rr = r
    if (!inside) {
        // Off the edge: park a smaller dot on the border, pointing the way.
        val dx = cx - size.width / 2f; val dy = cy - size.height / 2f
        val k = min((size.width / 2f - inset) / max(abs(dx), 0.001f), (size.height / 2f - inset) / max(abs(dy), 0.001f))
        cx = size.width / 2f + dx * k; cy = size.height / 2f + dy * k
        rr = r * 0.75f
    }
    if (p.isMe) drawCircle(color.copy(alpha = 0.22f), rr + 8.dp.toPx(), Offset(cx, cy))
    if (p.apart) drawCircle(color.copy(alpha = 0.45f * alpha), rr + 6.dp.toPx(), Offset(cx, cy), style = Stroke(2.dp.toPx()))
    drawCircle(Color.White.copy(alpha = alpha), rr + 2.dp.toPx(), Offset(cx, cy))
    drawCircle(color.copy(alpha = alpha), rr, Offset(cx, cy))
    val layout = m.measure(initial(p.name), TextStyle(color = Color.White.copy(alpha = alpha), fontSize = (if (rr > r * 0.9f) 14 else 11).sp, fontWeight = FontWeight.Bold))
    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
}

private fun DrawScope.drawPin(v: MapViewport, pin: MeetingPin, color: Color, ink: Color, panel: Color, m: TextMeasurer) {
    val s = v.toScreen(pin.pos)
    val x = s.x.toFloat(); val y = s.y.toFloat()
    if (x < -40 || y < -40 || x > size.width + 40 || y > size.height + 40) return
    val h = 30.dp.toPx()
    drawLine(ink, Offset(x, y), Offset(x, y - h), 2.dp.toPx(), StrokeCap.Round)
    val flag = Path().apply { moveTo(x, y - h); lineTo(x + 18.dp.toPx(), y - h + 7.dp.toPx()); lineTo(x, y - h + 14.dp.toPx()); close() }
    drawPath(flag, color)
    drawCircle(ink, 3.dp.toPx(), Offset(x, y))
    if (pin.label.isNotBlank()) {
        val layout = m.measure(pin.label, TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        val pad = 4.dp.toPx()
        val tl = Offset(x + 22.dp.toPx(), y - h - 2.dp.toPx())
        drawRoundRect(panel, tl - Offset(pad, pad / 2), Size(layout.size.width + pad * 2, layout.size.height + pad), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        drawText(layout, topLeft = tl)
    }
}

private fun DrawScope.drawScaleBar(v: MapViewport, ink: Color, panel: Color, m: TextMeasurer, imperial: Boolean) {
    val spec = ScaleBar.forViewport(v, 110.dp.toPx().toDouble(), imperial)
    if (spec.lengthPx <= 0.0) return
    val len = spec.lengthPx.toFloat()
    val left = 14.dp.toPx(); val base = size.height - 16.dp.toPx()
    val layout = m.measure(spec.label, TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.Medium))
    val pad = 5.dp.toPx()
    drawRoundRect(
        panel, Offset(left - pad, base - layout.size.height - 10.dp.toPx() - pad / 2),
        Size(max(len, layout.size.width.toFloat()) + pad * 2, layout.size.height + 18.dp.toPx() + pad), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
    )
    drawText(layout, topLeft = Offset(left, base - layout.size.height - 6.dp.toPx()))
    drawLine(ink, Offset(left, base), Offset(left + len, base), 2.dp.toPx())
    drawLine(ink, Offset(left, base - 5.dp.toPx()), Offset(left, base + 1.dp.toPx()), 2.dp.toPx())
    drawLine(ink, Offset(left + len, base - 5.dp.toPx()), Offset(left + len, base + 1.dp.toPx()), 2.dp.toPx())
}

private fun DrawScope.drawAttribution(ink: Color, panel: Color, m: TextMeasurer) {
    val layout = m.measure(SlippyTiles.ATTRIBUTION, TextStyle(color = ink, fontSize = 10.sp))
    val pad = 4.dp.toPx()
    val tl = Offset(size.width - layout.size.width - 8.dp.toPx(), size.height - layout.size.height - 6.dp.toPx())
    drawRoundRect(panel, tl - Offset(pad, pad / 2), Size(layout.size.width + pad * 2, layout.size.height + pad), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
    drawText(layout, topLeft = tl)
}
