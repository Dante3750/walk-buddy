package com.walkbuddy.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.walkbuddy.R
import com.walkbuddy.data.WalkMedia
import com.walkbuddy.domain.SharedMode
import com.walkbuddy.domain.SharedWalkRecord
import com.walkbuddy.domain.SummaryCard
import com.walkbuddy.domain.UnitSystem
import com.walkbuddy.domain.WalkNotes
import com.walkbuddy.share.SummaryCardRenderer
import com.walkbuddy.ui.AppViewModel
import com.walkbuddy.ui.components.Disclaimer
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.components.ToggleRow
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A private note and one photo for a walk. Both stay in this app's own storage and are deleted with the walk. */
@Composable
fun WalkNotesSection(vm: AppViewModel, id: Long) {
    val ctx = LocalContext.current
    var note by remember(id) { mutableStateOf("") }
    var photo by remember(id) { mutableStateOf<String?>(null) }
    var version by remember(id) { mutableStateOf(0) }
    LaunchedEffect(id, version) {
        val (n, p) = vm.walkExtras(id)
        if (version == 0) note = n.orEmpty()
        photo = p
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.attachWalkPhoto(id, uri) { name ->
            if (name == null) Toast.makeText(ctx, ctx.getString(R.string.photo_failed), Toast.LENGTH_SHORT).show() else version++
        }
    }
    SectionCard(stringResource(R.string.notes_title)) {
        OutlinedTextField(
            value = note, onValueChange = { note = it.take(WalkNotes.MAX_NOTE) }, label = { Text(stringResource(R.string.notes_hint)) },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.saveWalkNote(id, note); Toast.makeText(ctx, ctx.getString(R.string.notes_saved), Toast.LENGTH_SHORT).show() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.notes_save)) }
            OutlinedButton(
                onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(if (photo == null) R.string.photo_add else R.string.photo_change)) }
        }
        val name = photo
        if (name != null) {
            val bmp by produceBitmap(ctx, name, version)
            bmp?.let {
                Image(
                    it.asImageBitmap(), contentDescription = stringResource(R.string.photo_desc),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit,
                )
            }
            TextButton(onClick = { vm.removeWalkPhoto(id); photo = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.photo_remove)) }
        }
        Disclaimer(stringResource(R.string.photo_note))
    }
}

@Composable
private fun produceBitmap(ctx: android.content.Context, name: String, version: Int) = androidx.compose.runtime.produceState<Bitmap?>(null, name, version) {
    value = withContext(Dispatchers.IO) {
        runCatching {
            val f = WalkMedia.file(ctx, name)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / s > 1400) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull()
    }
}

/** The shareable summary card: previewed here, route hidden unless switched on, shared through the system share sheet. */
@Composable
fun SummaryCardSection(vm: AppViewModel, r: SharedWalkRecord, unit: UnitSystem, includeRoute: Boolean) {
    val ctx = LocalContext.current
    val title = stringResource(if (r.mode == SharedMode.Group) R.string.card_title_group else R.string.card_title_partner)
    val labels = mapOf(
        "distance" to stringResource(R.string.card_distance), "time" to stringResource(R.string.card_time),
        "steps" to stringResource(R.string.card_steps), "together" to stringResource(R.string.card_together),
    )
    val footer = stringResource(R.string.app_name)
    val bmp = remember(r.id, unit, includeRoute, title) {
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(r.startMs))
        SummaryCardRenderer.render(SummaryCard.plan(r, unit, includeRoute, title, date), labels, footer, labels["together"].orEmpty())
    }
    SectionCard(stringResource(R.string.card_section)) {
        Image(
            bmp.asImageBitmap(), contentDescription = stringResource(R.string.card_desc),
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).semantics { }, contentScale = ContentScale.Fit,
        )
        if (r.routePolyline != null) {
            ToggleRow(stringResource(R.string.card_route), stringResource(R.string.card_route_sub), includeRoute) { on -> vm.saveSettings { setCardRoute(on) } }
        }
        OutlinedButton(
            onClick = {
                if (!SummaryCardRenderer.share(ctx, bmp, ctx.getString(R.string.share_chooser))) Toast.makeText(ctx, ctx.getString(R.string.no_share_app), Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.card_share)) }
        Disclaimer(stringResource(R.string.card_note))
    }
}
