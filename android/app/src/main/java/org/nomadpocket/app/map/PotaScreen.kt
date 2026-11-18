// PotaScreen.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.app.map

import org.nomadpocket.app.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nomadpocket.app.conditions.ConditionsFormat

/** Parks on the Air near you: who is on the air now, and the parks around you. */
@Composable
fun PotaDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PotaStore.get(context) }
    val state by store.state.collectAsState()
    val here = org.nomadpocket.app.location.rememberLocation()
    val near = here?.let { it.latitude to it.longitude }
    val scope = rememberCoroutineScope()
    val metric = remember { ConditionsFormat.current().metric }
    var logging by remember { mutableStateOf<org.nomadpocket.ham.Qso?>(null) }
    logging?.let { org.nomadpocket.ham.QsoEditorDialog(it) { logging = null } }
    val now = System.currentTimeMillis()
    val spots = store.activeSpots(near, now)
    val parks = state.snapshot?.parks.orEmpty().let { p -> if (near == null) p else p.sortedBy { KnownRadioParser.distance(near.first, near.second, it.latitude, it.longitude) } }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("Parks on the Air")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.busy && here != null, onClick = { scope.launch { store.refresh(here!!.latitude, here.longitude, force = true) } }) { Text(if (state.busy) "Updating…" else "Update") }
                if (here == null) Text("Waiting for your position…", style = MaterialTheme.typography.bodySmall)
                state.snapshot?.let { Text("Updated ${PotaParsers.ageText(it.fetchedMillis, now)}", style = MaterialTheme.typography.bodySmall) }
                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text("Parks on the Air (POTA) is an amateur radio award programme: operators go to parks and make contacts. Download once with signal; the last copy stays readable with no signal.",
                    style = MaterialTheme.typography.labelSmall)
                Text("On the air now (${spots.size})", style = MaterialTheme.typography.titleMedium)
                if (spots.isEmpty()) Text(if (state.snapshot == null) "Tap Update." else "No fresh spots in the last 45 minutes.", style = MaterialTheme.typography.bodySmall)
                spots.take(60).forEach { s ->
                    Column {
                        Row { Text(s.activator, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text("${s.frequencyText} ${s.mode}", fontFamily = FontFamily.Monospace) }
                        Text("${s.parkName} · ${s.reference}", style = MaterialTheme.typography.bodySmall)
                        Text(listOfNotNull(near?.let { ShapeMath.distanceText(KnownRadioParser.distance(it.first, it.second, s.latitude, s.longitude), metric) }, PotaParsers.ageText(s.spottedMillis, now)).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall)
                        PlaceActions(s.parkName, s.latitude, s.longitude, onDismiss)
                        OutlinedButton(onClick = {
                            logging = org.nomadpocket.ham.blankQso(s.activator, s.frequencyKHz / 1000, s.mode.uppercase(), "POTA", s.reference,
                                if (org.nomadpocket.ham.Ham.isInitialized) org.nomadpocket.ham.Ham.service.settings.baseCall else "",
                                near?.let { org.nomadpocket.ham.HamGeo.maidenhead(it.first, it.second) } ?: "")
                        }) { Text(tr("Log this contact")) }
                    }
                }
                Text("Parks near you (${parks.size})", style = MaterialTheme.typography.titleMedium)
                parks.take(40).forEach { p ->
                    Column { Text(p.name); Text(p.reference, style = MaterialTheme.typography.labelSmall); PlaceActions(p.name, p.latitude, p.longitude, onDismiss) }
                }
                Text("Data: POTA (pota.app), a free public service. Positions come from the park list; activations are spots posted by operators and the Reverse Beacon Network.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Done")) } })
}
