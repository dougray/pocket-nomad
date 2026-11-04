// MeshInfo.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Plain-language explainer: what a mesh radio is and how to get one going.
// Port of MeshInfoView.swift.

package org.nomadpocket.mesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MeshInfoScreen() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Section("What is it?", Icons.Filled.SettingsInputAntenna,
            "A mesh radio is a small, cheap LoRa radio that runs free mesh firmware: Meshtastic or MeshCore. Your phone talks to it over Bluetooth, and it sends your text over the air to other radios. Radios pass messages along, so texts can hop across several radios to reach people far away. No cell towers, Wi-Fi or internet are involved, and messages are encrypted. Pocket Nomad works with both kinds, but a Meshtastic radio only talks to other Meshtastic radios and a MeshCore radio only to MeshCore ones, so use the same kind as the people you want to reach.")
        Section("Getting a radio", Icons.Filled.ShoppingCart,
            "Look for a LoRa board for the 915 MHz band if you are in the US. Common ones are made by Heltec, RAK Wireless (WisBlock), LilyGO (T-Beam, T-Echo) and Seeed (SenseCAP T1000-E). Most cost about \$20 to \$80. Get one with a battery or battery holder, and an antenna that is always attached. Never power a radio on without its antenna, which can damage it. Everyone you want to reach needs a radio too.")
        Section("Set it up first", Icons.Filled.Tune,
            "Before using it here, set the radio up once. Meshtastic: install the official Meshtastic app, connect, and set the LoRa region to US (915 MHz); a radio with no region will not transmit. MeshCore: flash the Companion (Bluetooth) firmware with the MeshCore web flasher and pick your area's radio settings in the MeshCore app. Keep the default public channel to reach people nearby, or make a private one and share it with your group. Then come back here and tap Find a radio.")
        Section("How far does it reach?", Icons.Filled.Straighten,
            "Expect about 1 to 3 miles between handheld radios in towns or woods, and 5 to 10 miles or more with a clear line of sight, such as from a hilltop. Hills, buildings and bodies block the signal: hold the radio high, away from your body, or put it on a pack strap. Each extra radio in between extends the range.")
        Section("What to expect", Icons.Filled.Schedule,
            "Messages are short (about 200 letters) and can take seconds to a minute to arrive. \"Heard by mesh\" means another radio repeated your message. \"Delivered\" means the person's radio confirmed it. Keep the app open or in the background to keep receiving messages.")
        Section("SOS", Icons.Filled.Sos,
            "The SOS button sends a short help message, with your GPS position if you allow it, to every radio on the primary channel. It does not reach emergency services unless someone on the mesh passes it on.")
    }
}

@Composable
private fun Section(title: String, icon: ImageVector, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MeshColors.accent)
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MeshColors.accent)
        }
        Text(text, fontSize = 17.sp)
    }
}
