package com.walkbuddy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.WalkBuddyApp
import com.walkbuddy.ui.theme.WalkBuddyTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLink(intent)
        setContent {
            WalkBuddyTheme {
                WalkBuddyApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    /** A walkbuddy://join/CODE link only pre-fills a confirmation dialog; nothing starts until the user taps Join. */
    private fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "walkbuddy") viewModel.pendingJoin.value = data.toString()
    }
}
