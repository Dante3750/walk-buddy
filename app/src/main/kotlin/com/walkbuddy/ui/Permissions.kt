package com.walkbuddy.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Permissions are asked one at a time, in context, and every one of them is optional to grant. There is no microphone permission. */
@Stable
class PermState(private val context: Context) {
    private var version by mutableIntStateOf(0)

    fun refresh() { version++ }

    private fun granted(p: String): Boolean {
        if (version < 0) return false // reads the state so composables re-check after a refresh
        return ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }

    val location: Boolean get() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    /** Physical activity (step counting). A runtime permission only from Android 10; before that the step sensors need no permission. */
    val activity: Boolean get() = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION)
    val notifications: Boolean get() = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    companion object {
        val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val ACTIVITY: Array<String> =
            if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyArray()
        val NOTIFICATIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    }
}

@Composable
fun rememberPermState(): PermState {
    val ctx = LocalContext.current
    return remember { PermState(ctx) }
}

/** Returns a function that asks for [permissions] and refreshes [state] afterwards. */
@Composable
fun rememberPermissionRequester(state: PermState, onDone: () -> Unit = {}): (Array<String>) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state.refresh()
        onDone()
    }
    return remember(launcher) { { perms: Array<String> -> if (perms.isEmpty()) onDone() else launcher.launch(perms) } }
}
