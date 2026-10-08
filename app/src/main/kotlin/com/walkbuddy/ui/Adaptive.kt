package com.walkbuddy.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Window width buckets (the same breakpoints as Material's WindowSizeClass: 600 and 840 dp). */
enum class WidthClass { Compact, Medium, Expanded }

@Composable
fun rememberWidthClass(): WidthClass {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w < 600 -> WidthClass.Compact
        w < 840 -> WidthClass.Medium
        else -> WidthClass.Expanded
    }
}
