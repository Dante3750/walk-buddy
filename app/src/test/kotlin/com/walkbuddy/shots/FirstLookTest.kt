package com.walkbuddy.shots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.walkbuddy.domain.FlameInfo
import com.walkbuddy.domain.FlameLevel
import com.walkbuddy.domain.RingBuddy
import com.walkbuddy.ui.components.StatPill
import com.walkbuddy.ui.components.StepHero
import com.walkbuddy.ui.components.StreakCard
import com.walkbuddy.ui.components.VerifiedChip
import org.junit.Rule
import org.junit.Test

/** The very first render of the existing hero components, to prove the screenshot loop works. */
class FirstLookTest {
    @get:Rule val paparazzi = newPaparazzi()

    private val flame = FlameInfo(FlameLevel.Blaze, 9, 2, false, "9 day streak", "2 rest tokens ready")

    private fun content() = @androidx.compose.runtime.Composable {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StepHero(steps = 5247, goal = 7000, walking = true, buddies = listOf(RingBuddy("b", "Sam", 6120, 8000)))
            VerifiedChip(5247, 5390)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatPill("Distance", "3.8", "km", Modifier.weight(1f))
                StatPill("Active", "41", "min", Modifier.weight(1f))
            }
            StreakCard(flame)
        }
    }

    @Test fun hero_light() = paparazzi.shot("hero", Look.Light, 1800, content())
    @Test fun hero_dark() = paparazzi.shot("hero", Look.Dark, 1800, content())
    @Test fun hero_large() = paparazzi.shot("hero", Look.Large, 2000, content())
}
