// CoverageScreen.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Coverage scanner controls: drive mode and its ping test, recording,
// retention, private zones and the opt-in, privacy-filtered export. Private by
// default. Port of CoverageSettingsView.swift.

@file:OptIn(ExperimentalMaterial3Api::class)

package org.nomadpocket.mesh

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WrongLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nomadpocket.mesh.ui.MeshBigButton
import org.nomadpocket.mesh.ui.MeshCard
import org.nomadpocket.mesh.ui.MeshColors
import org.nomadpocket.mesh.ui.MeshPermissions
import org.nomadpocket.mesh.ui.MeshTheme
import org.nomadpocket.mesh.ui.MeshUI
import org.nomadpocket.mesh.ui.FirmwareBadge
import org.nomadpocket.mesh.ui.rememberRadiosInUse
import org.nomadpocket.mesh.ui.rememberPermissionRequest
import java.io.File

/** Coverage + drive mode + ping test + private zones + export. Usable on its own (e.g. from the map). */
@Composable
fun CoverageScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    if (!Mesh.isInitialized) Mesh.init(context)
    MeshTheme {
        MeshScaffold("Coverage map", onBack = null, actions = {
            TextButton(onClick = onDone) { Text("Done", fontWeight = FontWeight.Bold) }
        }) { CoverageContent() }
    }
}

@Composable
private fun CoverageContent() {
    val context = LocalContext.current
    val store = Mesh.coverage
    val drive = Mesh.drive
    val radios = rememberRadiosInUse(Mesh.radios)
    val location = Mesh.location
    val samples by store.samplesFlow.collectAsStateWithLifecycle()
    val zones by store.zonesFlow.collectAsStateWithLifecycle()
    val recording by store.recordingFlow.collectAsStateWithLifecycle()
    val sharing by store.sharingFlow.collectAsStateWithLifecycle()
    val retention by store.retentionFlow.collectAsStateWithLifecycle()
    val pings by store.pingsFlow.collectAsStateWithLifecycle()
    val fix by location.fixFlow.collectAsStateWithLifecycle()
    val driveState by drive.stateFlow.collectAsStateWithLifecycle()
    val pinging by drive.pingingFlow.collectAsStateWithLifecycle()
    val targets by drive.targetsFlow.collectAsStateWithLifecycle()
    val keepScreenOn by drive.keepScreenOnFlow.collectAsStateWithLifecycle()
    val meshCore = radios.any { it.first == Firmware.MESHCORE }
    var confirmDelete by remember { mutableStateOf(false) }
    var addingZone by remember { mutableStateOf(false) }
    var permissionNote by remember { mutableStateOf<String?>(null) }

    // GPS while this screen is open, for "add a private zone where I am".
    DisposableEffect(Unit) {
        location.begin()
        onDispose { location.end() }
    }
    val askForRecording = rememberPermissionRequest {
        if (MeshPermissions.hasLocation(context)) store.recording = true
        else permissionNote = "Recording needs location permission. Turn it on in Settings > Apps > Pocket Nomad > Permissions."
    }
    val askForDrive = rememberPermissionRequest {
        if (MeshPermissions.hasLocation(context)) drive.start()
        else permissionNote = "Drive mode needs location permission. Turn it on in Settings > Apps > Pocket Nomad > Permissions."
    }
    val askForLocation = rememberPermissionRequest {}
    val hasGPS = fix?.let { System.currentTimeMillis() - it.timeMillis < 120_000 } == true

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        permissionNote?.let { Text(it, color = MeshColors.warn) }

        // Drive mode
        FormSection("Drive mode",
            footer = "Keeps recording while the screen is off or you're in another app, and turns itself off after 4 hours. " +
                "The ping test sends a tiny test message every 300 m (at most every 45 s) and marks the spot green if the radio you chose answered, " +
                "yellow if another radio only repeated it, or red if nothing heard it. " +
                (if (meshCore) "On MeshCore the ping is a trace to the repeater heard most recently (or one you pick), which repeaters answer on their own; a person's radio gets a short direct message instead. " else "") +
                (if (radios.size > 1) "With two radios, each pings on its own network, a few seconds apart, and the map shows which network each result is from. " else "") +
                "Uses battery and GPS: keep the phone on a charger.") {
            SwitchRow("Drive mode", driveState.active) { on ->
                if (!on) drive.stop()
                else if (MeshPermissions.granted(context, MeshPermissions.drive)) drive.start()
                else askForDrive(MeshPermissions.drive)
            }
            SwitchRow("Ping test", pinging) { drive.pinging = it }
            if (pinging) {
                for ((fw, link) in radios) {
                    val nodes by link.nodesFlow.collectAsStateWithLifecycle()
                    val radio by link.radioFlow.collectAsStateWithLifecycle()
                    val blocked by link.blockedFlow.collectAsStateWithLifecycle()
                    val choices = buildList {
                        add(null to if (fw == Firmware.MESHCORE) "Nearest repeater" else "Any radio that repeats it")
                        MeshService.sortedNodes(nodes, radio.myNodeNum, blocked).forEach { add(it.num to it.displayName) }
                    }
                    val target = targets[fw]
                    Picker(if (radios.size > 1) "Ping (${fw.displayName})" else "Ping",
                        choices.firstOrNull { it.first == target }?.second ?: choices.first().second, choices) { drive.setTarget(fw, it) }
                }
            }
            SwitchRow("Keep screen on", keepScreenOn) { drive.keepScreenOn = it }
            if (driveState.active) {
                Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val note = driveState.pingNote
                    if (note != null) Text(note, color = MeshColors.secondary, fontSize = 14.sp)
                    else if (pinging) Text(if (driveState.waiting) "Waiting for an answer…" else "${driveState.sent} pings sent", fontSize = 14.sp)
                    if (driveState.sent > 0) {
                        val perRadio = if (radios.size > 1) driveState.radios.entries.filter { it.value.sent > 0 } else emptyList()
                        if (perRadio.isEmpty()) {
                            PingCounts(driveState.reached, driveState.relayed, driveState.unanswered)
                        } else {
                            for ((fw, r) in perRadio) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FirmwareBadge(fw, compact = true)
                                    PingCounts(r.reached, r.relayed, r.unanswered)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Coverage scanner
        FormSection("Coverage scanner",
            footer = "While Pocket Nomad is open (or in drive mode) and connected to a radio, it notes where your phone was each time the radio hears another node, and how strong the signal was. With a Meshtastic and a MeshCore radio connected, it records from both at once and notes which network heard what. It stays on this phone.") {
            SwitchRow("Record coverage", recording) { on ->
                if (!on) store.recording = false
                else if (MeshPermissions.hasLocation(context)) store.recording = true
                else askForRecording(MeshPermissions.location)
            }
            ValueRow("Recordings", "${samples.size}")
            if (samples.any { it.firmware == Firmware.MESHCORE } && samples.any { it.firmware == Firmware.MESHTASTIC }) {
                ValueRow("  Meshtastic · MeshCore",
                    "${samples.count { it.firmware == Firmware.MESHTASTIC }} · ${samples.count { it.firmware == Firmware.MESHCORE }}")
            }
            if (pings.isNotEmpty()) ValueRow("Drive-test pings", "${pings.size}")
            Text("Keep for", fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (days in RETENTION_CHOICES) {
                    FilterChip(selected = retention == days, onClick = { store.retentionDays = days }, label = { Text("$days days") })
                }
            }
            TextButton(onClick = { confirmDelete = true }, enabled = samples.isNotEmpty() || pings.isNotEmpty()) {
                Text("Delete all coverage data", color = if (samples.isNotEmpty() || pings.isNotEmpty()) MeshColors.danger else MeshColors.secondary)
            }
        }

        // Private zones
        FormSection("Private zones",
            footer = if (!hasGPS) "Waiting for GPS. Private zones are added at your current location."
            else "Nothing in or near these places is ever shared, including whole map hexagons that touch them. Add your home first, then anywhere else you'd rather keep private.") {
            for (z in zones) {
                Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(z.name, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text("${z.radius.toInt()} m", color = MeshColors.secondary)
                    IconButton(onClick = { store.removeZone(z.id) }) { Icon(Icons.Filled.Delete, "Remove ${z.name}", tint = MeshColors.danger) }
                }
            }
            if (!MeshPermissions.hasLocation(context)) {
                MeshBigButton("Allow location", { askForLocation(MeshPermissions.location) }, filled = false)
            }
            MeshBigButton("Add a private zone where I am", { addingZone = true }, icon = Icons.Filled.AddCircle,
                filled = false, enabled = hasGPS)
        }

        // Sharing
        FormSection("Sharing (off by default)",
            footer = if (zones.isEmpty()) "Add a private zone first. Sharing never happens on its own: you export a file and choose where it goes, such as the community coverage map."
            else "Sharing never happens on its own: you export a file and choose where it goes. Preview it on the map's coverage layer.") {
            SwitchRow("Share coverage", sharing, enabled = zones.isNotEmpty()) { store.sharing = it }
            if (sharing) {
                val cells = remember(samples, pings, zones) { store.sharedCells }
                val hidden = samples.count { s -> zones.any { it.contains(s.latitude, s.longitude) } }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What a file would contain", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Bullet(Icons.Filled.Hexagon, "${cells.size} hexagons of about 350 m, each with only its best signal and a count")
                    Bullet(Icons.Filled.WrongLocation, "$hidden recordings in private zones left out, plus hexagons that touch them")
                    Bullet(Icons.Filled.VisibilityOff, "No node names or IDs, no exact positions, dates only to the month")
                }
                MeshBigButton("Export coverage file", { shareExport(context) }, icon = Icons.Filled.Share, enabled = cells.isNotEmpty())
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all ${samples.size} coverage recordings?") },
            text = { Text("Drive-test pings are deleted too. This can't be undone.") },
            confirmButton = { TextButton(onClick = { store.deleteAll(); confirmDelete = false }) { Text("Delete all", color = MeshColors.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = MeshColors.card)
    }
    if (addingZone) {
        ZoneEditor(defaultName = if (zones.isEmpty()) "Home" else "Private place ${zones.size + 1}",
            onCancel = { addingZone = false },
            onAdd = { name, radius ->
                location.fix?.let { store.addZone(name.ifBlank { "Private place" }, it.latitude, it.longitude, radius) }
                addingZone = false
            })
    }
}

private val RETENTION_CHOICES = listOf(7, 30, 90, 365)

/** Writes the export into the shareable cache folder and opens the share sheet. */
private fun shareExport(context: android.content.Context) {
    val file = try { Mesh.coverage.exportFile(File(context.cacheDir, "coverage-export")) } catch (_: Exception) { return }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.mesh.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/geo+json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share coverage file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun ZoneEditor(defaultName: String, onCancel: () -> Unit, onAdd: (String, Double) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var radius by remember { mutableDoubleStateOf(500.0) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Private zone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Radius")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (r in listOf(300.0, 500.0, 1000.0, 2000.0)) {
                        FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text("${r.toInt()} m") })
                    }
                }
                Text("Centred on where you are now.", fontSize = 13.sp, color = MeshColors.secondary)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, radius) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = MeshColors.card,
    )
}

@Composable
private fun FormSection(title: String, footer: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MeshColors.secondary)
        MeshCard { Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { content() } }
        footer?.let { Text(it, fontSize = 13.sp, color = MeshColors.secondary) }
    }
}

@Composable
private fun PingCounts(reached: Int, relayed: Int, unanswered: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$reached answered", color = MeshColors.ok, fontSize = 14.sp)
        Text("$relayed repeated", color = MeshColors.accent, fontSize = 14.sp)
        Text("$unanswered nothing", color = MeshColors.danger, fontSize = 14.sp)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = MeshUI.minTap - 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 17.sp, modifier = Modifier.weight(1f), color = if (enabled) Color.White else MeshColors.secondary)
        Switch(checked, onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = MeshColors.accent, checkedThumbColor = Color.Black))
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 17.sp, color = MeshColors.secondary)
    }
}

@Composable
private fun <T> Picker(label: String, current: String, choices: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = MeshUI.minTap - 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }, enabled = choices.isNotEmpty()) {
                Text(if (choices.isEmpty()) "No radios heard yet" else current, maxLines = 1)
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((value, text) in choices) {
                    DropdownMenuItem(text = { Text(text) }, onClick = { onPick(value); open = false })
                }
            }
        }
    }
}

@Composable
private fun Bullet(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MeshColors.accent, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp)
    }
}
