@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkbuddy.data.Clock
import com.walkbuddy.data.SpotRow
import com.walkbuddy.domain.FavoriteSpot
import com.walkbuddy.domain.AnniversaryCountdown
import com.walkbuddy.domain.CalendarIntentSpec
import com.walkbuddy.domain.Units
import com.walkbuddy.domain.UnitSystem
import java.time.LocalDate
import com.walkbuddy.domain.Copy
import com.walkbuddy.domain.Odometer
import com.walkbuddy.domain.TogetherStreak
import com.walkbuddy.domain.WalkDate
import com.walkbuddy.domain.WalkDatePlanner
import com.walkbuddy.session.Phase
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.EmptyState
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.ScreenTitle
import com.walkbuddy.ui.components.WbProgress
import java.text.DateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date

/** Opens the system calendar's "new event" screen: no calendar permission needed, and the user stays in control. */
fun openCalendarInsert(context: Context, spec: CalendarIntentSpec) {
    val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, spec.beginMs)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, spec.endMs)
        .putExtra(CalendarContract.Events.TITLE, spec.title)
        .putExtra(CalendarContract.Events.DESCRIPTION, spec.description)
    spec.rrule?.let { i.putExtra(CalendarContract.Events.RRULE, it) }
    try {
        context.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show()
    }
}

private fun pickDateTime(context: Context, onPicked: (Long) -> Unit) {
    val now = Calendar.getInstance()
    DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, min ->
            val ms = LocalDateTime.of(y, m + 1, d, h, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            onPicked(ms)
        }, 18, 0, android.text.format.DateFormat.is24HourFormat(context)).show()
    }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
}

private fun pickDate(context: Context, onPicked: (LocalDate) -> Unit) {
    val now = Calendar.getInstance()
    DatePickerDialog(context, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
}

class CoupleActions(
    val onPickAnniversary: (label: String) -> Unit = {},
    val onRemoveAnniversary: () -> Unit = {},
    val onPlanDate: (title: String, weekly: Boolean) -> Unit = { _, _ -> },
    val onCalendar: (WalkDate, Long) -> Unit = { _, _ -> },
    val onDeleteDate: (Long) -> Unit = {},
    val onShareSpot: (FavoriteSpot) -> Unit = {},
    val onDeleteSpot: (Long) -> Unit = {},
    /** Saves the current location under a name; [done] gets the result message ("Saved" on success). */
    val onSaveSpot: (name: String, done: (String) -> Unit) -> Unit = { _, _ -> },
)

@Composable
fun CoupleScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val dates by vm.dates.collectAsStateWithLifecycle()
    val spots by vm.spots.collectAsStateWithLifecycle()
    val distance by vm.coupleDistanceM.collectAsStateWithLifecycle()
    val days by vm.coupleDays.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    CoupleContent(
        anniversaryDate = settings?.anniversaryDate, anniversaryLabel = settings?.anniversaryLabel.orEmpty(),
        unit = settings?.unitSystem ?: UnitSystem.Metric, dates = dates, spots = spots, distanceM = distance, coupleDays = days,
        canShareSpots = session.phase != Phase.Idle && !session.solo, today = Clock.today(), nowMs = System.currentTimeMillis(),
        a = CoupleActions(
            onPickAnniversary = { label -> pickDate(ctx) { d -> vm.saveSettings { setAnniversary(d.toString(), label) } } },
            onRemoveAnniversary = { vm.saveSettings { setAnniversary("", "") } },
            onPlanDate = { title, weekly ->
                pickDateTime(ctx) { ms ->
                    val d = WalkDate(0, ms, 30, weekly, title.ifBlank { "Walk date" })
                    vm.addDate(d)
                    openCalendarInsert(ctx, WalkDatePlanner.toCalendarIntent(d))
                }
            },
            onCalendar = { d, at -> openCalendarInsert(ctx, WalkDatePlanner.toCalendarIntent(d.copy(startMs = at))) },
            onDeleteDate = { vm.deleteDate(it) },
            onShareSpot = { vm.shareSpot(it) },
            onDeleteSpot = { vm.deleteSpot(it) },
            onSaveSpot = { name, done -> vm.saveSpotHere(name) { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); done(msg) } },
        ),
    )
}

@Composable
fun CoupleContent(
    anniversaryDate: String?,
    anniversaryLabel: String,
    unit: UnitSystem,
    dates: List<WalkDate>,
    spots: List<SpotRow>,
    distanceM: Double,
    coupleDays: Set<Long>,
    canShareSpots: Boolean,
    today: Long,
    nowMs: Long,
    a: CoupleActions,
) {
    var annivLabel by remember(anniversaryLabel) { mutableStateOf(anniversaryLabel) }
    var title by remember { mutableStateOf("Walk date") }
    var weekly by remember { mutableStateOf(false) }
    var spotName by remember { mutableStateOf("") }
    val streak = TogetherStreak.lastDays(coupleDays, today)
    val odo = Odometer.state(distanceM)
    val upcoming = WalkDatePlanner.upcoming(dates, nowMs)
    val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("Us", subtitle = "For two people walking together. No accounts: you simply share a session code.")

        SectionCard("Together streak") {
            Text(streak.text, style = MaterialTheme.typography.titleMedium)
            WbProgress(streak.daysTogether / streak.windowDays.toFloat(), Modifier.semantics { contentDescription = streak.text })
        }

        SectionCard("Our day") {
            val date = AnniversaryCountdown.parse(anniversaryDate)
            if (date == null) {
                Text("Add an anniversary or a date to look forward to. It stays on this phone, and you get one gentle reminder the day before.", style = MaterialTheme.typography.bodyMedium)
            } else {
                AnniversaryCountdown.daysTogether(date, LocalDate.now())?.let { Text("Day ${com.walkbuddy.domain.Hero.thousands(it.toInt())} together", style = MaterialTheme.typography.headlineSmall) }
                Text(AnniversaryCountdown.info(date, anniversaryLabel, LocalDate.now()).text, style = MaterialTheme.typography.titleMedium)
            }
            OutlinedTextField(value = annivLabel, onValueChange = { annivLabel = it.take(30) }, label = { Text("What are we counting to?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { a.onPickAnniversary(annivLabel) }) { Text(if (date == null) "Pick the date" else "Change date") }
                if (date != null) TextButton(onClick = a.onRemoveAnniversary) { Text("Remove") }
            }
        }

        SectionCard("Our distance") {
            Text(Units.longDistance(distanceM, unit) + " walked together", style = MaterialTheme.typography.headlineMedium)
            val next = odo.next
            if (next != null) {
                WbProgress(odo.fractionToNext.toFloat(), Modifier.semantics { contentDescription = "Progress to ${next.name}" })
                Text("Next: ${next.label}, " + Units.longDistance((odo.remainingToNextKm ?: 0.0) * 1000.0, unit) + " to go")
            } else {
                Text("You have walked past every milestone. Lovely.")
            }
            odo.reached.forEach { Text("Reached: ${it.label}", style = MaterialTheme.typography.bodySmall) }
            Disclaimer("Road distances are approximate and only for fun.")
        }

        SectionCard("Walk dates") {
            if (upcoming.isEmpty()) {
                EmptyState("No dates planned", "Pick a day and time. It opens your calendar so you get a reminder.")
            }
            upcoming.forEach { (d, at) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = MaterialTheme.typography.titleMedium)
                        Text(dateFmt.format(Date(at)) + if (d.weekly) " (every week)" else "", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { a.onCalendar(d, at) }) { Text("Calendar") }
                    TextButton(onClick = { a.onDeleteDate(d.id) }) { Text("Remove") }
                }
            }
            OutlinedTextField(value = title, onValueChange = { title = it.take(40) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = weekly, onCheckedChange = { weekly = it })
                Text("Repeat every week")
            }
            Button(
                onClick = { a.onPlanDate(title, weekly) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Plan a walk date") }
        }

        SectionCard("Favorite walking spots") {
            if (spots.isEmpty()) Text("Save places you love. They stay on this phone, and you can share one with your partner during a walk.")
            spots.forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.spot.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (canShareSpots) TextButton(onClick = { a.onShareSpot(row.spot) }) { Text("Share") }
                    TextButton(onClick = { a.onDeleteSpot(row.id) }) { Text("Remove") }
                }
            }
            OutlinedTextField(value = spotName, onValueChange = { spotName = it.take(40) }, label = { Text("Name this place") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                enabled = spotName.isNotBlank(),
                onClick = { a.onSaveSpot(spotName) { msg -> if (msg == "Saved") spotName = "" } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save where I am now") }
            Disclaimer("Coordinates are stored only on this phone. Sharing sends one name and one point to your partner.")
        }

        SectionCard("Little extras") {
            Text("In Settings you can turn on the 'thinking of you' ping, pace-sync mode (the faster partner gets the gentle nudge) and quiet mode.")
        }
        Disclaimer(Copy.SHARE_LOCATION)
    }
}
