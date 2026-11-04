// MeshDevices.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Scan for and connect to nearby Meshtastic and MeshCore radios (firmware is
// detected from the advertised service). Port of MeshDevicesSheet.swift, plus
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val svc = Mesh.service
    val context = LocalContext.current
    val connection by svc.connectionFlow.collectAsStateWithLifecycle()
    val radio by svc.radioFlow.collectAsStateWithLifecycle()
    val availability by svc.availabilityFlow.collectAsStateWithLifecycle()
    val discovered by svc.discoveredFlow.collectAsStateWithLifecycle()
    val scanning by svc.isScanningFlow.collectAsStateWithLifecycle()
    val hint by svc.pairingHintFlow.collectAsStateWithLifecycle()
    val lastDevice by svc.lastDeviceFlow.collectAsStateWithLifecycle()
    val permitted = MeshPermissions.granted(context, MeshPermissions.bluetooth)
    val request = rememberPermissionRequest { result -> if (result.values.all { it }) svc.startScan() }

    DisposableEffect(Unit) {
        if (!svc.connection.isLinkUp) {
            if (MeshPermissions.granted(context, MeshPermissions.bluetooth)) svc.startScan() else request(MeshPermissions.bluetooth)
        }
        onDispose { svc.stopScan() }
    }
    // Close once a new connection completes (not when opened while already connected).
    val openedConnected = remember { mutableStateOf(svc.connection.isConnected) }
    LaunchedEffect(connection) {
        if (connection.isConnected && !openedConnected.value) onConnected()
        if (!connection.isConnected) openedConnected.value = false
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

        val name = when (val c = connection) {
            is MeshConnectionState.Connected -> c.name
            is MeshConnectionState.Syncing -> c.name
            is MeshConnectionState.Connecting -> c.name
            is MeshConnectionState.Reconnecting -> c.name
            else -> null
        }
        if (name != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MeshStatusCard(connection, radio, null)
                if (radio.firmwareVersion.isNotEmpty() && connection.isConnected) {
                    Text("Firmware ${radio.firmwareVersion}", fontSize = 13.sp, color = MeshColors.secondary)
                }
                MeshBigButton("Disconnect $name", { svc.disconnect() }, icon = Icons.Filled.Close, tint = MeshColors.danger, filled = false)
                MeshBigButton("Forget and pick another", { svc.forgetDevice(); svc.startScan() }, icon = Icons.Filled.Delete,
                    tint = MeshColors.secondary, filled = false)
            }
        }
        if (!connection.isConnected) {
            MeshInfoCard(Icons.Filled.Lock, "About the PIN",
                "The first time you connect, Android asks for a 6-digit PIN. Radios with a screen show it there. Radios without a screen usually use 123456 unless someone changed it.")
        }
        hint?.let { MeshInfoCard(Icons.Filled.NoEncryption, "Pairing problem", it, MeshColors.danger) }

        if (!connection.isLinkUp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Nearby radios", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    if (scanning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                }
                if (discovered.isEmpty()) {
                    Text(if (availability == MeshBluetoothAvailability.POWERED_ON)
                        "Searching. Make sure the radio is on, Bluetooth is enabled on it, and no other phone is connected to it."
                    else "Waiting for Bluetooth.", color = MeshColors.secondary)
                }
                for (d in discovered) {
                    MeshCard(onClick = { svc.connect(d) }) { DeviceRow(d, d.id == lastDevice?.first) }
                }
                if (!scanning) {
                    MeshBigButton("Scan again", {
                        if (MeshPermissions.granted(context, MeshPermissions.bluetooth)) svc.startScan() else request(MeshPermissions.bluetooth)
                    }, icon = Icons.Filled.Refresh, filled = false)
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: MeshDiscoveredDevice, remembered: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.SettingsInputAntenna, null, tint = MeshColors.accent, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text("${d.firmware.displayName} · " + if (d.rssi == 0) "Already linked to this phone" else "${signalWord(d.rssi)} · ${d.rssi} dBm",
                fontSize = 15.sp, color = MeshColors.secondary)
        }
        Button(onClick = {}, enabled = false, shape = CircleShape,
            colors = ButtonDefaults.buttonColors(disabledContainerColor = MeshColors.accent, disabledContentColor = Color.Black)) {
            Text(if (remembered) "Reconnect" else "Connect", fontWeight = FontWeight.Bold)
        }
    }
}

private fun signalWord(rssi: Int): String = when {
    rssi >= -60 -> "Strong"
    rssi >= -75 -> "Good"
    rssi >= -88 -> "Weak"
    else -> "Very weak"
}
