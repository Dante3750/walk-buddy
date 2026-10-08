package com.walkbuddy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.walkbuddy.domain.RingBuddy
import com.walkbuddy.ui.components.StatPill
import com.walkbuddy.ui.components.StepHero
import com.walkbuddy.ui.components.VerifiedChip
import com.walkbuddy.ui.theme.WalkBuddyTheme

/** One annotation, three looks: light, true-black dark, and 1.6x font scale. */
@Preview(name = "Light", showBackground = true, widthDp = 380)
@Preview(name = "Dark", showBackground = true, widthDp = 380, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large font", showBackground = true, widthDp = 380, fontScale = 1.6f)
annotation class WalkPreviews

@WalkPreviews
@Composable
private fun HeroPreview() {
    WalkBuddyTheme(reduceMotion = true) {
        Surface {
            Column(Modifier.padding(16.dp)) {
                StepHero(
                    steps = 5247, goal = 7000, walking = true,
                    buddies = listOf(RingBuddy("b", "Sam", 6100, 7000)),
                )
                VerifiedChip(verified = 5247, raw = 5390)
                Row { StatPill("Distance", "3.8", "km"); StatPill("Active", "41", "min") }
            }
        }
    }
}
