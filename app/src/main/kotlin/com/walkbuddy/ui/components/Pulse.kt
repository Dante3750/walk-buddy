package com.walkbuddy.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * A slow back-and-forth 0..1..0 value for decorative glows and flickers, built to be cheap:
 *  - it advances at [fps] frames per second (default 12), not at the display rate, so the phone is not redrawing at 60 or 120 Hz forever;
 *  - it runs only while the screen is showing this UI (lifecycle RESUMED) and stops completely otherwise;
 *  - it is a [State] meant to be READ INSIDE a draw block (Canvas / drawBehind), so only drawing is redone, not composition;
 *  - [enabled] false (reduce motion, Battery Saver, nothing to show) means no loop at all and a constant [rest] value.
 */
@Composable
fun rememberPulse(periodMs: Int, enabled: Boolean, fps: Int = 12, rest: Float = 0.5f): State<Float> {
    val value = remember { mutableFloatStateOf(rest) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(enabled, periodMs, fps, owner) {
        if (!enabled) {
            value.floatValue = rest
            return@LaunchedEffect
        }
        val frameMs = (1000L / fps.coerceIn(1, 30))
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val t = (SystemClock.uptimeMillis() % (2L * periodMs)).toFloat() / periodMs
                value.floatValue = if (t <= 1f) t else 2f - t
                delay(frameMs)
            }
        }
    }
    return value
}
