package com.walkbuddy.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.walkbuddy.R
import com.walkbuddy.domain.Angles
import com.walkbuddy.domain.ArrowCandidate
import com.walkbuddy.domain.ArrowPick
import com.walkbuddy.domain.ArrowPicker
import com.walkbuddy.domain.ArrowTarget
import com.walkbuddy.domain.CompassFilter
import com.walkbuddy.domain.CompassMath
import com.walkbuddy.domain.Geo
import com.walkbuddy.domain.LatLon
import com.walkbuddy.domain.MeetingLeg
import com.walkbuddy.domain.MeetingNav
import com.walkbuddy.domain.UnitSystem
import com.walkbuddy.domain.Units
import kotlin.math.abs

/** The phone's heading in degrees from north, or null while unknown. [needsCalibration] asks for the figure-8 wave. */
class HeadingInfo(val deg: Double?, val needsCalibration: Boolean, val available: Boolean)

/**
 * Live compass. The sensors are registered only while the screen is showing this UI (resumed) and are released the moment it is paused,
 * so a pocketed phone costs nothing. The raw angle is smoothed by [CompassFilter]. Prefers the fused rotation-vector sensor and falls back
 * to accelerometer plus magnetometer.
 */
@Composable
fun rememberHeading(): State<HeadingInfo> {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(HeadingInfo(null, false, true)) }
    DisposableEffect(owner) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotation = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnet = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val usable = sm != null && (rotation != null || (accel != null && magnet != null))
        if (!usable) {
            state.value = HeadingInfo(null, false, false)
            return@DisposableEffect onDispose { }
        }
        val filter = CompassFilter()
        var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        var lastShown = Double.NaN
        val gravity = FloatArray(3)
        val magnetic = FloatArray(3)
        var haveG = false
        var haveM = false
        val r = FloatArray(9)
        val r2 = FloatArray(9)
        val o = FloatArray(3)

        fun publish(rawDeg: Double) {
            val smooth = filter.update(rawDeg)
            if (lastShown.isNaN() || abs(Angles.diff(smooth, lastShown)) >= 1.0 || state.value.needsCalibration != CompassMath.needsCalibration(accuracy)) {
                lastShown = smooth
                state.value = HeadingInfo(smooth, CompassMath.needsCalibration(accuracy), true)
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(r, e.values)
                        SensorManager.getOrientation(r, o)
                        // Held upright (the usual way to read a compass while walking) the "flat" azimuth is unstable: look out of the back instead.
                        var az = Math.toDegrees(o[0].toDouble())
                        if (abs(Math.toDegrees(o[1].toDouble())) > 45.0) {
                            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, r2)
                            SensorManager.getOrientation(r2, o)
                            az = Math.toDegrees(o[0].toDouble())
                        }
                        publish(az)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        // A light low-pass on the raw gravity so the arrow does not tremble.
                        for (i in 0..2) gravity[i] = if (haveG) gravity[i] * 0.85f + e.values[i] * 0.15f else e.values[i]
                        haveG = true
                        if (haveM) CompassMath.azimuthDeg(gravity, magnetic)?.let { publish(it) }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        for (i in 0..2) magnetic[i] = if (haveM) magnetic[i] * 0.85f + e.values[i] * 0.15f else e.values[i]
                        haveM = true
                        if (haveG) CompassMath.azimuthDeg(gravity, magnetic)?.let { publish(it) }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, a: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD || sensor.type == Sensor.TYPE_ROTATION_VECTOR) accuracy = a
            }
        }

        fun register() {
            if (rotation != null) sm!!.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
            else {
                sm!!.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
                sm.registerListener(listener, magnet, SensorManager.SENSOR_DELAY_UI)
            }
        }

        fun unregister() {
            sm!!.unregisterListener(listener)
            filter.reset(); haveG = false; haveM = false
        }

        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> unregister()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
        onDispose {
            owner.lifecycle.removeObserver(obs)
            sm!!.unregisterListener(listener)
        }
    }
    return state
}

@Composable
private fun turnText(rel: Double): String {
    val a = abs(rel)
    return stringResource(
        when {
            a < 20 -> R.string.nav_turn_ahead
            a < 60 -> if (rel < 0) R.string.nav_turn_slight_left else R.string.nav_turn_slight_right
            a < 135 -> if (rel < 0) R.string.nav_turn_left else R.string.nav_turn_right
            else -> R.string.nav_turn_behind
        },
    )
}

@Composable
private fun targetLabel(t: ArrowTarget): String = stringResource(
    when (t) {
        ArrowTarget.Partner -> R.string.nav_target_partner
        ArrowTarget.Behind -> R.string.nav_target_behind
        ArrowTarget.Pin -> R.string.nav_target_pin
    },
)

@Composable
fun etaText(leg: MeetingLeg): String {
    val res = LocalContext.current.resources
    val s = leg.etaSec
    return when {
        leg.arrived -> stringResource(R.string.nav_there)
        s == null -> "-"
        s < 60 -> stringResource(R.string.nav_under_minute)
        s < 3600 -> stringResource(if (leg.etaEstimated) R.string.nav_about_min else R.string.nav_min, ((s + 30) / 60).toInt())
        else -> stringResource(R.string.nav_hours_min, (s / 3600).toInt(), (((s % 3600) + 30) / 60).toInt())
    }
}

/** The arrow itself: it points where the target is, relative to where the phone is facing. */
@Composable
fun CompassArrow(relativeDeg: Double?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(96.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val rad = size.minDimension / 2f - 4f * density
        drawCircle(ring, rad, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f * density))
        if (relativeDeg == null) return@Canvas
        rotate(relativeDeg.toFloat(), c) {
            val p = Path().apply {
                moveTo(c.x, c.y - rad * 0.8f)
                lineTo(c.x + rad * 0.35f, c.y + rad * 0.45f)
                lineTo(c.x, c.y + rad * 0.2f)
                lineTo(c.x - rad * 0.35f, c.y + rad * 0.45f)
                close()
            }
            drawPath(p, color)
        }
    }
}

/**
 * Compass arrow plus meeting-point times. [others] are the people who can be pointed at; [pin] the meeting point; [legs] the per-person
 * distance, bearing and time to the pin. Shown only when there is something to point at.
 */
@Composable
fun NavSection(
    myPos: LatLon?, others: List<ArrowCandidate>, pin: LatLon?, isGroup: Boolean, legs: List<MeetingLeg>, unit: UnitSystem,
    modifier: Modifier = Modifier, headingOverride: HeadingInfo? = null,
    onMeetHere: (() -> Unit)? = null, onClearPin: (() -> Unit)? = null,
) {
    val available = ArrowPicker.available(others, pin, isGroup)
    if (available.isEmpty() && legs.isEmpty() && onMeetHere == null) return
    var wanted by remember { mutableStateOf<ArrowTarget?>(null) }
    val live by rememberHeading()
    val heading = headingOverride ?: live
    val choice = wanted?.takeIf { it in available } ?: available.firstOrNull() ?: ArrowTarget.Pin
    val pick: ArrowPick? = ArrowPicker.pick(choice, others, pin)
    SectionCard(stringResource(R.string.nav_title), modifier) {
        if (pick != null) {
            if (available.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    available.forEach { t ->
                        FilterChip(selected = t == pick.target, onClick = { wanted = t }, label = { Text(targetLabel(t)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            val bearing = if (myPos != null && myPos.isValid) Geo.bearingDeg(myPos, pick.to) else null
            val dist = if (myPos != null && myPos.isValid) Geo.haversine(myPos, pick.to) else null
            val rel = if (bearing != null && heading.deg != null) CompassMath.relativeDeg(bearing, heading.deg) else null
            val who = pick.name ?: targetLabel(pick.target)
            val desc = if (rel != null && dist != null) "$who: ${turnText(rel)}, ${Units.distance(dist, unit)}" else who
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = desc }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CompassArrow(rel)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(who, style = MaterialTheme.typography.titleMedium)
                    if (dist != null) Text(Units.distance(dist, unit), style = MaterialTheme.typography.headlineSmall)
                    if (rel != null) Text(turnText(rel), style = MaterialTheme.typography.bodyMedium)
                    else if (myPos == null || !myPos.isValid) Text(stringResource(R.string.nav_waiting_gps), style = MaterialTheme.typography.bodySmall)
                    else if (!heading.available) Text(stringResource(R.string.nav_no_compass), style = MaterialTheme.typography.bodySmall)
                    if (bearing != null && !heading.available) Text(MeetingNav.compassPoint(bearing), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (heading.needsCalibration) Text(stringResource(R.string.nav_calibrate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        if (onMeetHere != null || (pin != null && onClearPin != null)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onMeetHere != null && myPos != null) {
                    androidx.compose.material3.OutlinedButton(onClick = onMeetHere, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nav_meet_here)) }
                }
                if (pin != null && onClearPin != null) {
                    androidx.compose.material3.TextButton(onClick = onClearPin, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nav_clear_pin)) }
                }
            }
        }
        if (legs.isNotEmpty()) {
            Text(stringResource(R.string.nav_legs_title), style = MaterialTheme.typography.titleSmall)
            legs.forEach { leg ->
                val name = if (leg.isMe) stringResource(R.string.nav_you) else leg.name
                val line = "$name: ${Units.distance(leg.distanceM, unit)}, ${MeetingNav.compassPoint(leg.bearingDeg)}, ${etaText(leg)}"
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = line }, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (leg.arrived) etaText(leg) else "${Units.distance(leg.distanceM, unit)}  ·  ${MeetingNav.compassPoint(leg.bearingDeg)}  ·  ${etaText(leg)}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Disclaimer(stringResource(R.string.nav_eta_note))
        }
    }
}

/** Me first, then the buddy, each with distance, bearing and time to the meeting point. Empty without a point. */
fun partnerLegs(w: com.walkbuddy.domain.WalkState, pin: LatLon?): List<MeetingLeg> {
    if (pin == null) return emptyList()
    val me = MeetingNav.leg("me", "You", true, w.myPos, pin, w.mySpeedMps)
    return MeetingNav.legs(me, w.buddies.map { b -> MeetingNav.leg(b.id, b.name, false, b.pos ?: b.lastPos, pin, b.speedMps) })
}

fun groupLegs(w: com.walkbuddy.domain.GroupState, pin: LatLon?): List<MeetingLeg> {
    if (pin == null) return emptyList()
    val me = MeetingNav.leg("me", "You", true, w.myPos, pin, w.mySpeedMps)
    return MeetingNav.legs(me, w.members.take(12).map { m -> MeetingNav.leg(m.id, m.name, false, m.pos ?: m.lastPos, pin, m.speedMps) })
}
