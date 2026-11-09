// MeshRadioCards.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The Mesh tab's radios section: one card per radio slot in use (firmware
// badge, state, battery, disconnect and forget) and "Add a radio" while a slot
// is free. Badge colours match the map: Meshtastic green, MeshCore blue.

package org.nomadpocket.mesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nomadpocket.mesh.Firmware
import org.nomadpocket.mesh.MeshConnectionState
import org.nomadpocket.mesh.MeshRadios
import org.nomadpocket.mesh.MeshService
import org.nomadpocket.mesh.Mesh
import org.nomadpocket.mesh.NearbyRadioParams
import org.nomadpocket.mesh.displayName

/** One colour per mesh system, as on the map. */
object FirmwareColors {
    val meshtastic = Color(0xFF66D978)
    val meshcore = Color(0xFF408CFA)
    fun of(firmware: Firmware) = if (firmware == Firmware.MESHCORE) meshcore else meshtastic
}

/**
 * The radios in use, re-read whenever any slot's connection or remembered radio
 * changes, so screens recompose when a second radio comes or goes.
 */
@Composable
fun rememberRadiosInUse(radios: MeshRadios): List<Pair<Firmware, MeshService>> {
    for (link in radios.links.values) {
        val c by link.connectionFlow.collectAsState()
        val d by link.lastDeviceFlow.collectAsState()
        c.hashCode(); d.hashCode() // read so this recomposes on change
    }
    return radios.inUse
}

@Composable
fun FirmwareBadge(firmware: Firmware, compact: Boolean = false) {
    Box(Modifier.background(FirmwareColors.of(firmware), RoundedCornerShape(6.dp))
        .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = 2.dp)) {
        Text(if (compact) (if (firmware == Firmware.MESHCORE) "MC" else "MT") else firmware.displayName,
            color = Color.Black, fontSize = if (compact) 11.sp else 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** One connected or remembered radio, with its actions. */
@Composable
fun MeshRadioCard(firmware: Firmware, link: MeshService, onDisconnect: () -> Unit, onForget: () -> Unit, onReconnect: () -> Unit,
                  onSettings: () -> Unit = {}) {
    // The displayed link: a drop of a few seconds at the edge of Bluetooth range doesn't flicker the card.
    val connection by link.displayConnectionFlow.collectAsState()
    val nodes by link.nodesFlow.collectAsState()
    val radio by link.radioFlow.collectAsState()
    val nearby by Mesh.nearbyMeshCore.collectAsState()
    val name = link.lastDeviceName ?: firmware.displayName
    val hint = if (firmware == Firmware.MESHCORE) NearbyRadioParams.hint(radio.meshCoreParams, nearby.match) else null
    val noRegion = firmware == Firmware.MESHTASTIC && radio.region == 0L
    val battery = nodes[radio.myNodeNum]?.batteryLevel?.takeIf { radio.myNodeNum != 0L && it in 1..100 }
    val tint = when (connection) {
        is MeshConnectionState.Connected -> MeshColors.ok
        is MeshConnectionState.Failed -> MeshColors.danger
        MeshConnectionState.Idle, is MeshConnectionState.NotNearby, is MeshConnectionState.NotWithMe -> MeshColors.secondary
        else -> MeshColors.warn
    }
    val trying = connection is MeshConnectionState.Reconnecting || connection is MeshConnectionState.Connecting ||
        connection is MeshConnectionState.NotNearby
    MeshCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FirmwareBadge(firmware)
                Spacer(Modifier.width(10.dp))
                Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                battery?.let { Text("$it%", fontSize = 15.sp, color = MeshColors.secondary) }
            }
            Text(radioStateWord(connection), color = tint, fontSize = 15.sp)
            radio.settingsSummary?.let { Text(it, fontSize = 15.sp, color = MeshColors.secondary) }
            if (noRegion) Text("No region set: this radio won't transmit. Open Radio settings to pick yours.", fontSize = 15.sp, color = MeshColors.danger)
            hint?.let { Text(it, fontSize = 15.sp, color = MeshColors.warn) }
            // Left at home today: stop looking for it until Connect or the next launch (auto-reconnect stays on).
            if (trying && link.lastDeviceID != null) {
                TextButton(onClick = { link.notWithMeToday() }) { Text("Not with me today", color = MeshColors.accent) }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (connection) {
                    MeshConnectionState.Idle, is MeshConnectionState.Failed ->
                        TextButton(onClick = onReconnect) { Text("Reconnect", color = MeshColors.accent) }
                    is MeshConnectionState.NotWithMe ->
                        TextButton(onClick = onReconnect) { Text("Connect", color = MeshColors.accent) }
                    else -> TextButton(onClick = onDisconnect) { Text("Disconnect", color = MeshColors.danger) }
                }
                TextButton(onClick = onSettings) { Text("Radio settings…", color = MeshColors.accent) }
                TextButton(onClick = onForget) { Text("Forget", color = MeshColors.secondary) }
            }
        }
    }
}

private fun radioStateWord(state: MeshConnectionState): String = when (state) {
    MeshConnectionState.Idle -> "Not connected"
    MeshConnectionState.Scanning -> "Looking for radios"
    is MeshConnectionState.Connecting -> "Connecting"
    is MeshConnectionState.Syncing -> "Syncing"
    is MeshConnectionState.Connected -> "Connected"
    is MeshConnectionState.Reconnecting -> "Waiting for the radio"
    is MeshConnectionState.NotNearby -> "Not nearby · reconnects when it's back"
    is MeshConnectionState.NotWithMe -> "Not with me today · Connect"
    is MeshConnectionState.Failed -> state.reason
}
