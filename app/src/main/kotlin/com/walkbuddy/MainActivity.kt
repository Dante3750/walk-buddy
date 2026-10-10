package com.walkbuddy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.domain.PowerMode
import com.walkbuddy.domain.SamplingPolicy
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.WalkBuddyApp
import com.walkbuddy.ui.theme.WalkBuddyTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.walkbuddy.ui.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash stays up only until the saved settings are read, so there is no flash of the wrong screen.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.settings.value == null }
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val power by viewModel.powerState.collectAsStateWithLifecycle()
            // Battery Saver (Android's or ours) switches off the endless decorative animations, the same way "reduce motion" does.
            val saving = SamplingPolicy.mode(power) == PowerMode.Saving
            WalkBuddyTheme(
                dynamicColor = settings?.dynamicColor == true,
                reduceMotion = settings?.reduceMotion == true || saving,
                haptics = settings?.haptics != false,
            ) {
                WalkBuddyApp(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground(true)
    }

    override fun onStop() {
        viewModel.onForeground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * A walkbuddy://join/CODE link only pre-fills a confirmation dialog; nothing starts until the user taps Join.
     * Shortcuts and the quick settings tile ask for an action, which the home screen carries out.
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data
        if (data != null && data.scheme == "walkbuddy") viewModel.pendingJoin.value = data.toString()
        when (intent.action) {
            ACTION_START_SOLO -> viewModel.pendingAction.value = "start_solo"
            ACTION_RECAP -> viewModel.pendingAction.value = "recap"
        }
    }

    companion object {
        const val ACTION_START_SOLO = "com.walkbuddy.action.START_SOLO"
        const val ACTION_RECAP = "com.walkbuddy.action.RECAP"
    }
}
