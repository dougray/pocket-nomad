// MeshNodes.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Radios the connected node has heard, most recent first, and the blocked list.
// Port of MeshNodeListView.swift. Long-press a radio to block it.

package org.nomadpocket.mesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nomadpocket.mesh.Mesh
import org.nomadpocket.mesh.MeshNode
import org.nomadpocket.mesh.MeshSOS
import org.nomadpocket.mesh.MeshService
import java.util.Locale

@Composable
fun MeshNodeListScreen(onMessage: (Long) -> Unit) {
    val svc = Mesh.service
    val nodes by svc.nodesFlow.collectAsStateWithLifecycle()
    val blocked by svc.blockedFlow.collectAsStateWithLifecycle()
    val radio by svc.radioFlow.collectAsStateWithLifecycle()
    val connection by svc.connectionFlow.collectAsStateWithLifecycle()
    val sorted = remember(nodes, blocked, radio.myNodeNum) { MeshService.sortedNodes(nodes, radio.myNodeNum, blocked) }
    val blockedNodes = remember(nodes, blocked) { svc.blockedNodes }
    var blocking by remember { mutableStateOf<MeshNode?>(null) }

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (sorted.isEmpty()) item {
            MeshInfoCard(Icons.Filled.Groups, "No other radios yet",
                if (connection.isConnected) "Your radio has not heard anyone. Radios announce themselves every few hours, so leave it on, get higher up, or ask a friend to send a message."
                else "Connect your radio to see who is around.")
        }
        items(sorted, key = { it.num }) { node ->
            MeshCard(onClick = { onMessage(node.num) }, onLongClick = { blocking = node }) { MeshNodeRow(node) }
        }
        if (sorted.isNotEmpty()) item {
            Text("Touch and hold a radio to block it.", fontSize = 13.sp, color = MeshColors.secondary)
        }
        if (blockedNodes.isNotEmpty()) {
            item { Text("Blocked", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(top = 12.dp)) }
            items(blockedNodes, key = { "b${it.num}" }) { node ->
                MeshCard {
                    Row(Modifier.padding(horizontal = 14.dp).heightIn(min = MeshUI.minTap), verticalAlignment = Alignment.CenterVertically) {
                        Text(node.displayName, color = MeshColors.secondary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { svc.unblock(node.num) }) { Text("Unblock") }
                    }
                }
            }
        }
    }
    BlockConfirmation(blocking) { blocking = null }
}

@Composable
fun MeshNodeRow(node: MeshNode) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
        MeshNodeBadge(node, 52)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(node.displayName, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(node.hexID, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = MeshColors.secondary)
            val bits = buildList {
                add(node.lastHeardMillis?.let { "Heard ${meshRelative(it)}" } ?: "Not heard")
                node.snr?.let { add(String.format(Locale.ROOT, "%.1f dB", it)) }
                node.hopsAway?.let { add(if (it == 0L) "Direct" else "$it hop${if (it == 1L) "" else "s"}") }
                node.batteryLevel?.let { add(if (it > 100) "Plugged in" else "Battery $it%") }
            }
            Text(bits.joinToString(" · "), fontSize = 15.sp, color = MeshColors.secondary)
            val lat = node.latitude
            val lon = node.longitude
            if (lat != null && lon != null) {
                SelectionContainer {
                    Text("${MeshSOS.format(lat)}, ${MeshSOS.format(lon)}", fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = MeshColors.secondary)
                }
            }
        }
        Icon(Icons.AutoMirrored.Filled.Chat, "Message", tint = MeshColors.accent, modifier = Modifier.padding(10.dp))
    }
}
