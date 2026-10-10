package com.walkbuddy.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import com.walkbuddy.domain.ReminderSlot
import com.walkbuddy.domain.WalkReminders
import com.walkbuddy.ui.components.ToggleRow
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.walkbuddy.R
import com.walkbuddy.data.Settings
import com.walkbuddy.domain.AppLanguages
import com.walkbuddy.domain.Seasons
import com.walkbuddy.domain.TrackTheme
import com.walkbuddy.ui.AppLocale
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.drawScene
import com.walkbuddy.ui.components.sceneStyle

@Composable
private fun themeName(t: TrackTheme): String = stringResource(
    when (t) {
        TrackTheme.Metro -> R.string.theme_metro
        TrackTheme.Train -> R.string.theme_train
        TrackTheme.Trail -> R.string.theme_trail
        TrackTheme.NightSky -> R.string.theme_night
        TrackTheme.Seasonal -> R.string.theme_seasonal
    },
)

/** Track scenery picker with a small still preview. All themes are original Canvas drawings. */
@Composable
internal fun ThemeSection(a: SettingsActions, s: Settings) {
    val current = TrackTheme.fromId(s.trackTheme)
    SectionCard(stringResource(R.string.theme_title)) {
        Text(stringResource(R.string.theme_body))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrackTheme.values().forEach { t ->
                FilterChip(selected = t == current, onClick = { a.save { setTrackTheme(t.id) } }, label = { Text(themeName(t)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        val scene = Seasons.scene(current, java.time.LocalDate.now().monthValue)
        val style = sceneStyle(scene)
        val fallback = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            if (style.sky == null) drawRoundRect(fallback, cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f * density)) else drawScene(scene, style)
        }
        Disclaimer(stringResource(R.string.theme_note))
    }
}

/** In-app language: the system default, English or Hindi. */
@Composable
internal fun LanguageSection() {
    val ctx = LocalContext.current
    val activity = generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
    val current = AppLocale.current(ctx)
    SectionCard(stringResource(R.string.lang_title)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguages.all.forEach { l ->
                val label = if (l.tag.isEmpty()) stringResource(R.string.lang_system) else l.nativeName
                FilterChip(
                    selected = l.tag == current, onClick = { if (l.tag != current && activity != null) AppLocale.set(activity, l.tag) },
                    label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Disclaimer(stringResource(R.string.lang_note))
    }
}


private val dayKeys = listOf(R.string.day_mon, R.string.day_tue, R.string.day_wed, R.string.day_thu, R.string.day_fri, R.string.day_sat, R.string.day_sun)

/** Walk reminders at times you choose. Each one can be switched off, moved to other days or removed. */
@Composable
internal fun RemindersSection(a: SettingsActions, s: Settings) {
    val ctx = LocalContext.current
    val slots = WalkReminders.decode(s.reminders)
    val fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    SectionCard(stringResource(R.string.rem_title)) {
        Text(stringResource(R.string.rem_body))
        if (slots.isEmpty()) Text(stringResource(R.string.rem_none), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        slots.forEach { slot ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(LocalTime.of(slot.hour, slot.minute).format(fmt), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(
                            checked = slot.enabled,
                            onCheckedChange = { on -> a.setReminders(slots.map { if (it.id == slot.id) it.copy(enabled = on) else it }) },
                        )
                        TextButton(onClick = { a.setReminders(slots.filter { it.id != slot.id }) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.rem_remove)) }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dayKeys.forEachIndexed { i, key ->
                        val day = i + 1
                        FilterChip(
                            selected = day in slot.weekdays,
                            onClick = {
                                val nd = if (day in slot.weekdays) slot.weekdays - day else slot.weekdays + day
                                if (nd.isNotEmpty()) a.setReminders(slots.map { if (it.id == slot.id) it.copy(weekdays = nd) else it })
                            },
                            label = { Text(stringResource(key)) }, modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }
        if (slots.size < WalkReminders.MAX_SLOTS) {
            OutlinedButton(
                onClick = {
                    android.app.TimePickerDialog(
                        ctx,
                        { _, h, m ->
                            if (!a.notificationsAllowed) a.askNotifications()
                            a.setReminders(slots + ReminderSlot(WalkReminders.nextId(slots), WalkReminders.everyDay, h * 60 + m))
                        },
                        18, 0, android.text.format.DateFormat.is24HourFormat(ctx),
                    ).show()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.rem_add)) }
        }
        ToggleRow(stringResource(R.string.rem_partner), stringResource(R.string.rem_partner_sub), s.reminderPartnerText) { on -> a.save { setReminderPartnerText(on) } }
        if (slots.isNotEmpty() && !a.notificationsAllowed) {
            Disclaimer(stringResource(R.string.rem_needs_perm))
            OutlinedButton(onClick = a.askNotifications, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.fix_allow)) }
        }
        Disclaimer(stringResource(R.string.rem_note))
    }
}


/** Optional gentle pace hints during walks together. Off by default; never shown in quiet mode or quiet hours. */
@Composable
internal fun CoachSection(a: SettingsActions, s: Settings) {
    val mode = com.walkbuddy.domain.CoachMode.fromName(s.coachMode)
    SectionCard(stringResource(R.string.coach_title)) {
        Text(stringResource(R.string.coach_body))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.walkbuddy.domain.CoachMode.values().forEach { m ->
                FilterChip(
                    selected = m == mode, onClick = { a.save { setCoachMode(m.name) } }, modifier = Modifier.heightIn(min = 48.dp),
                    label = {
                        Text(
                            stringResource(
                                when (m) {
                                    com.walkbuddy.domain.CoachMode.Off -> R.string.coach_off
                                    com.walkbuddy.domain.CoachMode.Gentle -> R.string.coach_gentle
                                    com.walkbuddy.domain.CoachMode.SlowestPace -> R.string.coach_slowest_mode
                                },
                            ),
                        )
                    },
                )
            }
        }
        Disclaimer(stringResource(R.string.coach_note))
    }
}


/** Opt-in weather suggestion. Off by default; the text says exactly what is sent. */
@Composable
internal fun WeatherSection(a: SettingsActions, s: Settings) {
    SectionCard(stringResource(R.string.wx_settings_title)) {
        ToggleRow(stringResource(R.string.wx_toggle), stringResource(R.string.wx_toggle_sub), s.weatherOn) { on -> a.save { setWeatherOn(on) } }
        Disclaimer(stringResource(R.string.wx_privacy))
    }
}
