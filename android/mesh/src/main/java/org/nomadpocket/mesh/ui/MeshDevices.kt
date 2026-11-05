// MeshDevices.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Scan for and connect to nearby Meshtastic and MeshCore radios (firmware is
// detected from the advertised service). One radio of each kind can be
// connected at once; picking a second of the same kind asks to replace it. Port of MeshDevicesSheet.swift, plus
// Android's runtime Bluetooth permission and, on Android 11 and older, the
// location switch it needs to scan.

package org.nomadpocket.mesh.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.NoEncryption
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nomadpocket.mesh.Mesh
import org.nomadpocket.mesh.MeshBluetoothAvailability
import org.nomadpocket.mesh.MeshConnectionState
import org.nomadpocket.mesh.MeshDiscoveredDevice
import org.nomadpocket.mesh.displayName

@Composable
fun MeshDevicesScreen(onConnected: () -> Unit) {
    val radios = Mesh.radios
    val context = LocalContext.current
    val inUse = rememberRadiosInUse(radios)
    val availability by radios.availabilityFlow.collectAsStateWithLifecycle()
    val discovered by radios.discoveredFlow.collectAsStateWithLifecycle()
    val scanning by radios.isScanningFlow.collectAsStateWithLifecycle()
    val permitted = MeshPermissions.granted(context, MeshPermissions.bluetooth)
    val request = rememberPermissionRequest { result -> if (result.values.all { it }) radios.startScan() }
    var confirm by remember { mutableStateOf<Pair<MeshDiscoveredDevice, String>?>(null) }
    val connectedIDs = inUse.mapNotNull { it.second.lastDeviceID }.toSet()
    // Scan while a slot is free (or to replace one), close once a newly picked radio connects.
    val free = radios.canAdd

    DisposableEffect(Unit) {
        if (MeshPermissions.granted(context, MeshPermissions.bluetooth)) radios.startScan() else request(MeshPermissions.bluetooth)
        onDispose { radios.stopScan() }
    }
    val waitingFor = remember { mutableStateOf<String?>(null) }
    for ((_, link) in inUse) {
        val connection by link.connectionFlow.collectAsStateWithLifecycle()
        LaunchedEffect(connection) {
            if (connection.isConnected && link.lastDeviceID != null && link.lastDeviceID == waitingFor.value) onConnected()
        }
    }
    fun pick(d: MeshDiscoveredDevice) {
        val replaced = radios.replacing(d)
        if (replaced != null) { confirm = d to replaced; return }
        waitingFor.value = d.id
        radios.connect(d)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MeshBluetoothHelp(availability)
        if (!permitted && availability != MeshBluetoothAvailability.UNAUTHORIZED) {
            MeshInfoCard(Icons.Filled.Lock, "Allow Bluetooth",
                if (Build.VERSION.SDK_INT >= 31) "Android asks for the Nearby devices permission so Pocket Nomad can find your radio. It is never used to find your location."
                else "On this version of Android, finding Bluetooth radios needs the location permission. Pocket Nomad doesn't use it to track you.") {
                MeshBigButton("Allow", { request(MeshPermissions.bluetooth) })
            }
        }
        if (Build.VERSION.SDK_INT < 31 && Mesh.location.isLocationOff) {
            MeshInfoCard(Icons.Filled.LocationOff, "Turn on Location to scan",
                "Android 11 and older only show Bluetooth radios while Location is on. Turn it on in quick settings, then scan again.", MeshColors.warn) {
                MeshBigButton("Open location settings", { context.startSafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    tint = MeshColors.warn, filled = false)
            }
        }

        if (inUse.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your radios", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                for ((fw, link) in inUse) {
                    val radio by link.radioFlow.collectAsStateWithLifecycle()
                    val hint by link.pairingHintFlow.collectAsStateWithLifecycle()
                    MeshRadioCard(fw, link, onDisconnect = { link.disconnect() }, onForget = { link.forgetDevice() },
                        onReconnect = { link.reconnectLast() })
                    if (radio.firmwareVersion.isNotEmpty() && link.connection.isConnected) {
                        Text("Firmware ${radio.firmwareVersion}", fontSize = 13.sp, color = MeshColors.secondary)
                    }
                    hint?.let { MeshInfoCard(Icons.Filled.NoEncryption, "Pairing problem (${fw.displayName})", it, MeshColors.danger) }
                }
                Text(if (free) "You can connect one Meshtastic and one MeshCore radio at the same time."
                    else "Both slots are in use (one Meshtastic, one MeshCore). Picking another radio replaces the one of the same kind.",
                    fontSize = 14.sp, color = MeshColors.secondary)
            }
        }
        if (inUse.none { it.second.connection.isConnected }) {
            MeshInfoCard(Icons.Filled.Lock, "About the PIN",
                "The first time you connect, Android asks for a 6-digit PIN. Radios with a screen show it there. Radios without a screen usually use 123456 unless someone changed it.")
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (inUse.isEmpty()) "Nearby radios" else "Add a radio", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
            }
            val list = discovered.filterNot { it.id in connectedIDs }
            if (list.isEmpty()) {
                Text(if (availability == MeshBluetoothAvailability.POWERED_ON)
                    "Searching. Make sure the radio is on, Bluetooth is enabled on it, and no other phone is connected to it."
                else "Waiting for Bluetooth.", color = MeshColors.secondary)
            }
            for (d in list) {
                MeshCard(onClick = { pick(d) }) { DeviceRow(d, radios.replacing(d) != null) }
            }
            if (!scanning) {
                MeshBigButton("Scan again", {
                    if (MeshPermissions.granted(context, MeshPermissions.bluetooth)) radios.startScan() else request(MeshPermissions.bluetooth)
                }, icon = Icons.Filled.Refresh, filled = false)
            }
        }
    }

    confirm?.let { (d, name) ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Replace $name?") },
            text = { Text("Pocket Nomad connects one ${d.firmware.displayName} radio at a time. Connecting ${d.name} disconnects $name and forgets it; its messages stay on this phone.") },
            confirmButton = { TextButton(onClick = { waitingFor.value = d.id; radios.connect(d); confirm = null }) { Text("Replace", color = MeshColors.danger) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            containerColor = MeshColors.card)
    }
}

@Composable
private fun DeviceRow(d: MeshDiscoveredDevice, replaces: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.SettingsInputAntenna, null, tint = MeshColors.accent, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                FirmwareBadge(d.firmware, compact = true)
            }
            Text("${d.firmware.displayName} · " + if (d.rssi == 0) "Already linked to this phone" else "${signalWord(d.rssi)} · ${d.rssi} dBm",
                fontSize = 15.sp, color = MeshColors.secondary)
        }
        Button(onClick = {}, enabled = false, shape = CircleShape,
            colors = ButtonDefaults.buttonColors(disabledContainerColor = MeshColors.accent, disabledContentColor = Color.Black)) {
            Text(if (replaces) "Replace" else "Connect", fontWeight = FontWeight.Bold)
        }
    }
}

private fun signalWord(rssi: Int): String = when {
    rssi >= -60 -> "Strong"
    rssi >= -75 -> "Good"
    rssi >= -88 -> "Weak"
    else -> "Very weak"
}
