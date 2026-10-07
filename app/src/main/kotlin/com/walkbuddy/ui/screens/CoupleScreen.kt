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
import com.walkbuddy.domain.CalendarIntentSpec
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

@Composable
fun CoupleScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val dates by vm.dates.collectAsStateWithLifecycle()
    val spots by vm.spots.collectAsStateWithLifecycle()
    val distance by vm.coupleDistanceM.collectAsStateWithLifecycle()
    val days by vm.coupleDays.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf("Walk date") }
    var weekly by remember { mutableStateOf(false) }
    var spotName by remember { mutableStateOf("") }
    val streak = TogetherStreak.lastDays(days, Clock.today())
    val odo = Odometer.state(distance)
    val now = System.currentTimeMillis()
    val upcoming = WalkDatePlanner.upcoming(dates, now)
    val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Us", style = MaterialTheme.typography.titleLarge)
        Disclaimer("For two people walking together. No accounts: you simply share a session code when you walk.")

        SectionCard("Together streak") {
            Text(streak.text, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { streak.daysTogether / streak.windowDays.toFloat() },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = streak.text },
            )
        }

        SectionCard("Our distance") {
            Text("%.1f km walked together".format(distance / 1000.0), style = MaterialTheme.typography.headlineMedium)
            val next = odo.next
            if (next != null) {
                LinearProgressIndicator(
                    progress = { odo.fractionToNext.toFloat() },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Progress to ${next.name}" },
                )
                Text("Next: ${next.label}, %.1f km to go".format(odo.remainingToNextKm ?: 0.0))
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
                    TextButton(onClick = { openCalendarInsert(ctx, WalkDatePlanner.toCalendarIntent(d.copy(startMs = at))) }) { Text("Calendar") }
                    TextButton(onClick = { vm.deleteDate(d.id) }) { Text("Remove") }
                }
            }
            OutlinedTextField(value = title, onValueChange = { title = it.take(40) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = weekly, onCheckedChange = { weekly = it })
                Text("Repeat every week")
            }
            Button(
                onClick = {
                    pickDateTime(ctx) { ms ->
                        val d = WalkDate(0, ms, 30, weekly, title.ifBlank { "Walk date" })
                        vm.addDate(d)
                        openCalendarInsert(ctx, WalkDatePlanner.toCalendarIntent(d))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Plan a walk date") }
        }

        SectionCard("Favorite walking spots") {
            if (spots.isEmpty()) Text("Save places you love. They stay on this phone, and you can share one with your partner during a walk.")
            spots.forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.spot.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (session.phase != Phase.Idle && !session.solo) TextButton(onClick = { vm.shareSpot(row.spot) }) { Text("Share") }
                    TextButton(onClick = { vm.deleteSpot(row.id) }) { Text("Remove") }
                }
            }
            OutlinedTextField(value = spotName, onValueChange = { spotName = it.take(40) }, label = { Text("Name this place") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                enabled = spotName.isNotBlank(),
                onClick = { vm.saveSpotHere(spotName) { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); if (msg == "Saved") spotName = "" } },
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
