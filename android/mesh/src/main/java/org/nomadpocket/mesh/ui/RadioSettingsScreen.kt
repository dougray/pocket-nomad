// RadioSettingsScreen.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Radio settings for one connected radio, opened from its card: put a
// MeshCore radio on the frequency the radios around you use (or a MeshCore
// preset, or custom values), or give a Meshtastic radio its region and modem
// preset. Nothing is sent until Apply; the screen then shows each step and
// the values the radio reports afterwards. The rules and presets are in
// RadioSettings.kt; the radio commands in MeshService.

package org.nomadpocket.mesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.nomadpocket.mesh.Firmware
import org.nomadpocket.mesh.Mesh
import org.nomadpocket.mesh.MeshCorePresets
import org.nomadpocket.mesh.MeshCoreRadioLimits
import org.nomadpocket.mesh.MeshCoreRadioParams
import org.nomadpocket.mesh.MeshService
import org.nomadpocket.mesh.MeshtasticModemPresets
import org.nomadpocket.mesh.MeshtasticRegions
import org.nomadpocket.mesh.RadioApplyState
import org.nomadpocket.mesh.displayName
import java.util.Locale

/** The Radio settings screen for one radio slot. */
@Composable
fun RadioSettingsScreen(firmware: Firmware, link: MeshService) {
    val connection by link.connectionFlow.collectAsStateWithLifecycle()
    val apply by link.applyFlow.collectAsStateWithLifecycle()
    // A result from an earlier visit is not shown again.
    DisposableEffect(link) { link.clearApply(); onDispose { link.clearApply() } }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(link.lastDeviceName ?: firmware.displayName, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        MeshInfoCard(Icons.Filled.Warning, "Use a legal frequency",
            "Use the frequencies allowed where you are. In the US, 902–928 MHz. Every radio in your group must use the " +
                "same settings, or they can't hear each other.", MeshColors.warn)
        if (!connection.isConnected && !apply.isBusy) {
            MeshInfoCard(Icons.Filled.Info, "Not connected",
                "Connect this radio to read and change its settings.", MeshColors.secondary)
        }
        when (firmware) {
            Firmware.MESHCORE -> MeshCoreSettings(link, apply, connection.isConnected)
            Firmware.MESHTASTIC -> MeshtasticSettings(link, apply, connection.isConnected)
        }
        ApplyStatus(apply)
    }
}

private enum class CoreSource { NEARBY, PRESET, CUSTOM }

@Composable
private fun MeshCoreSettings(link: MeshService, apply: RadioApplyState, connected: Boolean) {
    val radio by link.radioFlow.collectAsStateWithLifecycle()
    val nearby by Mesh.nearbyMeshCore.collectAsStateWithLifecycle()
    val current = radio.meshCoreParams
    val match = nearby.match
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val start = current ?: match?.params ?: MeshCorePresets.USA.params
    var source by remember { mutableStateOf(if (match != null && current?.sameAs(match.params) == true) CoreSource.CUSTOM else CoreSource.NEARBY) }
    var freqText by remember { mutableStateOf(String.format(Locale.ROOT, "%.3f", start.frequencyMHz)) }
    var bw by remember { mutableStateOf(start.bandwidthKHz) }
    var sf by remember { mutableStateOf(start.spreadingFactor) }
    var cr by remember { mutableStateOf(start.codingRate) }
    var txText by remember { mutableStateOf(radio.txPowerDbm?.toString() ?: "") }
    var presetTitle by remember { mutableStateOf(MeshCorePresets.USA.title) }
    var downloading by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }

    fun fill(p: MeshCoreRadioParams) {
        freqText = String.format(Locale.ROOT, "%.3f", p.frequencyMHz); bw = p.bandwidthKHz; sf = p.spreadingFactor; cr = p.codingRate
    }
    // First time the nearby match is known, start from it.
    LaunchedEffect(match) { if (source == CoreSource.NEARBY && match != null) fill(match.params) }

    fun download() {
        downloading = true; downloadError = null
        scope.launch {
            downloadError = Mesh.downloadNearby?.invoke() ?: if (Mesh.downloadNearby == null) "Downloads are not available here." else null
            downloading = false
            Mesh.nearbyMeshCore.value.match?.let { source = CoreSource.NEARBY; fill(it.params) }
        }
    }
    val askLocation = rememberPermissionRequest { download() }

    SectionTitle("Now")
    Text(current?.summary ?: "Waiting for the radio to report its settings.", fontSize = 17.sp)
    radio.txPowerDbm?.let { Text("Transmit power $it dBm" + (radio.maxTxPowerDbm?.let { m -> " (this radio allows up to $m)" } ?: ""), color = MeshColors.secondary) }
    if (current != null && MeshCorePresets.matching(current) == null && current.sameAs(MeshCoreRadioParams.FIRMWARE_DEFAULT)) {
        Text("These are MeshCore's out-of-the-box settings, which most areas do not use.", color = MeshColors.warn)
    }

    SectionTitle("Choose")
    Choice("Match radios near you", source == CoreSource.NEARBY) {
        source = CoreSource.NEARBY; match?.let { fill(it.params) }
    }
    if (source == CoreSource.NEARBY) {
        Column(Modifier.padding(start = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                match != null -> {
                    Text(match.params.summary, fontWeight = FontWeight.SemiBold)
                    Text(match.text + " (from the downloaded community map).", color = MeshColors.secondary)
                }
                nearby.downloaded && nearby.params.isEmpty() ->
                    Text("No MeshCore radios were found near where you downloaded.", color = MeshColors.secondary)
                nearby.downloaded ->
                    Text("The downloaded radios have no settings saved. Download again to get them.", color = MeshColors.secondary)
                else -> Text("Download the radios near you once (needs internet). After that this works offline.", color = MeshColors.secondary)
            }
            if (match == null || downloading) {
                MeshBigButton(if (downloading) "Downloading…" else "Download radios near you", {
                    if (MeshPermissions.hasLocation(context)) download() else askLocation(MeshPermissions.location)
                }, icon = Icons.Filled.Download, filled = false, enabled = !downloading)
            }
            downloadError?.let { Text(it, color = MeshColors.danger) }
        }
    }
    Choice("MeshCore preset", source == CoreSource.PRESET) {
        source = CoreSource.PRESET
        MeshCorePresets.official.firstOrNull { it.title == presetTitle }?.let { fill(it.params) }
    }
    if (source == CoreSource.PRESET) {
        Column(Modifier.padding(start = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Picker(presetTitle, MeshCorePresets.official.map { it.title to "${it.title}: ${it.params.summary}" }) { t ->
                presetTitle = t
                MeshCorePresets.official.firstOrNull { it.title == t }?.let { fill(it.params) }
            }
            Text("Suggested by the MeshCore community. USA: 910.525 MHz, 62.5 kHz, SF7, CR5.", color = MeshColors.secondary, fontSize = 14.sp)
        }
    }
    Choice("Custom", source == CoreSource.CUSTOM) { source = CoreSource.CUSTOM }

    SectionTitle("Settings to send")
    val editable = source == CoreSource.CUSTOM
    OutlinedTextField(freqText, { freqText = it; source = CoreSource.CUSTOM }, label = { Text("Frequency (MHz)") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    LabeledPicker("Bandwidth", MeshCoreRadioParams.formatKHz(bw), MeshCoreRadioLimits.BANDWIDTHS.map { it to MeshCoreRadioParams.formatKHz(it) }) {
        bw = it; source = CoreSource.CUSTOM
    }
    LabeledPicker("Spreading factor", "SF$sf", MeshCoreRadioLimits.SPREADING_FACTORS.map { it to "SF$it" }) { sf = it; source = CoreSource.CUSTOM }
    LabeledPicker("Coding rate", "CR$cr (4/$cr)", MeshCoreRadioLimits.CODING_RATES.map { it to "CR$it (4/$it)" }) { cr = it; source = CoreSource.CUSTOM }
    if (!editable) Text("Change any value to make it custom.", color = MeshColors.secondary, fontSize = 14.sp)
    OutlinedTextField(txText, { txText = it.filter { c -> c.isDigit() || c == '-' }.take(3) }, label = { Text("Transmit power (dBm)") },
        supportingText = { Text("Leave as is unless you know you need less." + (radio.maxTxPowerDbm?.let { " Up to $it dBm." } ?: "")) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

    val freq = MeshCoreRadioLimits.parseMHz(freqText)
    val params = MeshCoreRadioParams(freq ?: Double.NaN, bw, sf, cr)
    val tx = txText.toIntOrNull()
    val txChanged = tx != null && tx != radio.txPowerDbm
    val problems = if (freq == null) listOf("Type the frequency in MHz, like 910.525.")
        else MeshCoreRadioLimits.problems(params, tx.takeIf { txChanged }, radio.maxTxPowerDbm ?: 22)
    problems.forEach { Text(it, color = MeshColors.danger) }
    val unchanged = current != null && freq != null && current.sameAs(params.normalized()) && !txChanged
    if (unchanged) Text("The radio already uses these settings.", color = MeshColors.ok)

    MeshBigButton("Apply", { confirm = true }, enabled = connected && problems.isEmpty() && !unchanged && !apply.isBusy)

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Change this radio's settings?") },
            text = {
                Text("New: ${params.normalized().summary}" + (if (txChanged) ", $tx dBm" else "") +
                    "\n\nIt will only hear radios that use the same settings. Make sure these are legal where you are.")
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; link.applyMeshCore(params, tx.takeIf { txChanged }) }) {
                    Text("Apply", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            containerColor = MeshColors.card,
        )
    }
}

@Composable
private fun MeshtasticSettings(link: MeshService, apply: RadioApplyState, connected: Boolean) {
    val radio by link.radioFlow.collectAsStateWithLifecycle()
    val suggested = remember { MeshtasticRegions.suggested(Locale.getDefault().country) }
    val currentRegion = radio.region?.takeIf { it != MeshtasticRegions.UNSET }
    var region by remember { mutableStateOf(currentRegion ?: suggested?.code) }
    var preset by remember { mutableStateOf(radio.modemPreset?.takeIf { p -> MeshtasticModemPresets.all.any { it.code == p } } ?: MeshtasticModemPresets.LONG_FAST) }
    var usePreset by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }

    SectionTitle("Now")
    Text(radio.settingsSummary ?: "Waiting for the radio to report its settings.", fontSize = 17.sp)
    if (radio.region == MeshtasticRegions.UNSET) {
        MeshInfoCard(Icons.Filled.Error, "No region set", "This radio will not transmit until it has a region. Pick yours below.", MeshColors.danger)
    } else if (radio.txEnabled == false) {
        Text("Transmit is turned off on this radio, so it only listens. Turn it on in the Meshtastic app if you need to send.", color = MeshColors.warn)
    }

    SectionTitle("Region")
    Picker(MeshtasticRegions.of(region)?.label ?: "Pick your region",
        MeshtasticRegions.all.map { it.code to (it.label + if (it.code == suggested?.code) "  · suggested" else "") }) { region = it }
    suggested?.let { Text("Suggested from your phone's country: ${it.name}.", color = MeshColors.secondary, fontSize = 14.sp) }

    SectionTitle("Modem preset")
    Picker(MeshtasticModemPresets.name(preset).let { if (preset == MeshtasticModemPresets.LONG_FAST) "$it (default)" else it },
        MeshtasticModemPresets.all.map { it.code to it.name }) { preset = it }
    Text("Most Meshtastic radios use Long Fast. Everyone in your group needs the same preset.", color = MeshColors.secondary, fontSize = 14.sp)
    if (radio.usePreset == false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(usePreset, { usePreset = it })
            Text("Use the preset instead of this radio's custom modem settings")
        }
    }
    Text("The radio restarts to use new settings. The app reconnects by itself, usually within a minute.",
        color = MeshColors.secondary, fontSize = 14.sp)

    val r = region
    val unchanged = r != null && r == radio.region && preset == (radio.modemPreset ?: 0) && usePreset == (radio.usePreset ?: false)
    if (unchanged) Text("The radio already uses these settings.", color = MeshColors.ok)
    MeshBigButton("Apply", { confirm = true }, enabled = connected && r != null && !unchanged && !apply.isBusy)

    if (confirm && r != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Change this radio's settings?") },
            text = {
                Text("Region: ${MeshtasticRegions.of(r)?.label}\nPreset: ${MeshtasticModemPresets.name(preset)}" +
                    "\n\nThe radio restarts. Make sure this region is right for where you are.")
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; link.applyMeshtastic(r, preset, usePreset) }) { Text("Apply", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            containerColor = MeshColors.card,
        )
    }
}

@Composable
private fun ApplyStatus(state: RadioApplyState) {
    when (state) {
        RadioApplyState.Idle -> {}
        is RadioApplyState.Working -> Busy(state.step)
        RadioApplyState.Restarting -> Busy("Sent. The radio is restarting with the new settings; waiting for it to reconnect…")
        is RadioApplyState.Done -> MeshInfoCard(Icons.Filled.CheckCircle, "Done", state.confirmed, MeshColors.ok)
        is RadioApplyState.Failed -> MeshInfoCard(Icons.Filled.Error, "Not changed", state.reason, MeshColors.danger)
    }
}

@Composable
private fun Busy(text: String) {
    MeshCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), color = MeshColors.accent, strokeWidth = 3.dp)
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = MeshColors.secondary)
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null)
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 18.sp)
    }
}

@Composable
private fun <T> LabeledPicker(label: String, value: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MeshColors.secondary, fontSize = 14.sp)
        Picker(value, options, onSelect)
    }
}

/** A button showing the choice; tapping it lists the options. */
@Composable
private fun <T> Picker(value: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(value, Modifier.weight(1f), fontSize = 17.sp)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (v, text) -> DropdownMenuItem({ Text(text) }, { open = false; onSelect(v) }) }
        }
    }
}
