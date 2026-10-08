package com.walkbuddy.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.walkbuddy.ui.theme.WalkBuddyTheme

/** Screen look: light, true-black dark, or light at 1.3x font scale. */
enum class Look(val key: String, val dark: Boolean, val fontScale: Float) {
    Light("light", false, 1f),
    Dark("dark", true, 1f),
    Large("large", false, 1.3f),
}

fun newPaparazzi() = Paparazzi(
    deviceConfig = DeviceConfig.PIXEL_5,
    theme = "android:Theme.Material.Light.NoActionBar",
    showSystemUi = false,
    // Full device resolution: Paparazzi otherwise shrinks every image to 1000 px, too small to judge type and spacing.
    useDeviceResolution = true,
)

/** One screenshot of [content] inside the real app theme. The PNG is named after the test method. [heightPx] lets long scrolling screens render in full. */
fun Paparazzi.shot(name: String, look: Look, heightPx: Int = 2340, content: @Composable () -> Unit) {
    unsafeUpdateConfig(
        DeviceConfig.PIXEL_5.copy(
            nightMode = if (look.dark) NightMode.NIGHT else NightMode.NOTNIGHT,
            fontScale = look.fontScale,
            screenHeight = heightPx,
        ),
    )
    snapshot {
        WalkBuddyTheme(darkTheme = look.dark, reduceMotion = true) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
        }
    }
}
