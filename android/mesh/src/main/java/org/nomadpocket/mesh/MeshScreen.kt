// MeshScreen.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Root of the Mesh tab: radios, chat tiles, nodes, coverage, SOS. Port of
// MeshView.swift. Sub-screens are a small back stack inside the tab.
//
// With two radios (one Meshtastic, one MeshCore) each network keeps its own
// channels and direct messages; tiles and threads are labelled with their
// network, and a conversation always sends on the radio it belongs to.

@file:OptIn(ExperimentalMaterial3Api::class)

package org.nomadpocket.mesh

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.nomadpocket.mesh.ui.MeshBigButton
import org.nomadpocket.mesh.ui.MeshBluetoothHelp
import org.nomadpocket.mesh.ui.MeshCard
import org.nomadpocket.mesh.ui.MeshChatScreen
import org.nomadpocket.mesh.ui.MeshColors
import org.nomadpocket.mesh.ui.MeshDevicesScreen
import org.nomadpocket.mesh.ui.MeshInfoCard
import org.nomadpocket.mesh.ui.MeshInfoScreen
import org.nomadpocket.mesh.ui.MeshNodeBadge
import org.nomadpocket.mesh.ui.MeshNodeListScreen
import org.nomadpocket.mesh.ui.MeshPermissions
import org.nomadpocket.mesh.ui.MeshStatusCard
import org.nomadpocket.mesh.ui.MeshTheme
import org.nomadpocket.mesh.ui.MeshUI
import org.nomadpocket.mesh.ui.UnreadBadge
import org.nomadpocket.mesh.ui.FirmwareBadge
import org.nomadpocket.mesh.ui.FirmwareColors
import org.nomadpocket.mesh.ui.MeshRadioCard
import org.nomadpocket.mesh.ui.RadioSettingsScreen
import org.nomadpocket.mesh.ui.rememberRadiosInUse
import org.nomadpocket.mesh.ui.rememberPermissionRequest

internal sealed class MeshRoute {
    data class Chat(val conversation: MeshConversation, val firmware: Firmware) : MeshRoute()
    object Nodes : MeshRoute()
    object Info : MeshRoute()
    object Devices : MeshRoute()
    object Coverage : MeshRoute()
    data class RadioSettings(val firmware: Firmware) : MeshRoute()
}

/** The whole Mesh tab. Calls Mesh.init itself if the app has not yet. */
@Composable
fun MeshScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!Mesh.isInitialized) Mesh.init(context)
    val stack = remember { mutableStateListOf<MeshRoute>() }
    val open: (MeshRoute) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isNotEmpty(), onBack = back)

    MeshTheme {
        when (val route = stack.lastOrNull()) {
            MeshRoute.Coverage -> CoverageScreen(onDone = back)
            null -> MeshScaffold("Mesh", null, modifier, actions = {
                IconButton(onClick = { open(MeshRoute.Devices) }) { Icon(Icons.Filled.SettingsInputAntenna, "Radios") }
            }) { MeshHome(open) }
            is MeshRoute.Chat -> MeshScaffold(chatTitle(route.conversation, route.firmware), back, modifier) {
                MeshChatScreen(route.conversation, Mesh.radios.link(route.firmware))
            }
            MeshRoute.Nodes -> MeshScaffold("Nodes", back, modifier) { MeshNodeListScreen { fw, num -> open(MeshRoute.Chat(MeshConversation.Direct(num), fw)) } }
            is MeshRoute.RadioSettings -> MeshScaffold("Radio settings", back, modifier) {
                RadioSettingsScreen(route.firmware, Mesh.radios.link(route.firmware))
            }
            MeshRoute.Info -> MeshScaffold("What is a mesh radio?", back, modifier) { MeshInfoScreen() }
            MeshRoute.Devices -> MeshScaffold("Radios", back, modifier, actions = {
                TextButton(onClick = back) { Text("Done", fontWeight = FontWeight.Bold) }
            }) { MeshDevicesScreen(onConnected = back) }
        }
    }
}

@Composable
private fun chatTitle(c: MeshConversation, firmware: Firmware): String {
    val svc = Mesh.radios.link(firmware)
    val radio by svc.radioFlow.collectAsStateWithLifecycle()
    val nodes by svc.nodesFlow.collectAsStateWithLifecycle()
    val title = when (c) {
        is MeshConversation.Channel -> radio.channelNames[c.index.toInt()].orEmpty()
            .ifEmpty { if (c.index == 0L) "Primary channel" else "Channel ${c.index}" }
        is MeshConversation.Direct -> (nodes[c.node] ?: MeshNode(num = c.node)).displayName
    }
    // Say which network when two radios are in use.
    return if (rememberRadiosInUse(Mesh.radios).size > 1) "$title · ${firmware.displayName}" else title
}

@Composable
internal fun MeshScaffold(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier,
                          actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Scaffold(
        modifier = modifier,
        containerColor = MeshColors.background,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MeshColors.background),
            )
        },
    ) { padding -> Column(Modifier.padding(padding).fillMaxSize()) { content() } }
}

@Composable
private fun MeshHome(open: (MeshRoute) -> Unit) {
    val radios = Mesh.radios
    val context = LocalContext.current
    val inUse = rememberRadiosInUse(radios)
    val two = inUse.size > 1
    val availability by radios.availabilityFlow.collectAsStateWithLifecycle()
    val drive by Mesh.drive.stateFlow.collectAsStateWithLifecycle()
    val recording by Mesh.coverage.recordingFlow.collectAsStateWithLifecycle()
    val samples by Mesh.coverage.samplesFlow.collectAsStateWithLifecycle()
    // Read every link's data so the screen recomposes when any of it changes.
    for (link in radios.links.values) {
        val messages by link.messagesFlow.collectAsStateWithLifecycle()
        val nodes by link.nodesFlow.collectAsStateWithLifecycle()
        val blocked by link.blockedFlow.collectAsStateWithLifecycle()
        val lastRead by link.lastReadFlow.collectAsStateWithLifecycle()
        val radio by link.radioFlow.collectAsStateWithLifecycle()
        remember(messages, nodes, blocked, lastRead, radio) { 0 }
    }

    val scope = rememberCoroutineScope()
    var askSOS by remember { mutableStateOf(false) }
    var sendingSOS by remember { mutableStateOf(false) }
    var sosResult by remember { mutableStateOf<String?>(null) }

    fun sendSOS(withLocation: Boolean) {
        sendingSOS = true
        scope.launch {
            val results = radios.sendSOS(withLocation)
            sendingSOS = false
            sosResult = sosSummary(results, withLocation)
        }
    }
    val askLocation = rememberPermissionRequest { sendSOS(true) } // sends either way; without a fix if refused

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (inUse.isEmpty()) {
            MeshStatusCard(MeshConnectionState.Idle, MeshRadioInfo()) { open(MeshRoute.Devices) }
        }
        MeshBluetoothHelp(availability)
        for ((fw, link) in inUse) {
            val label = if (two) "${fw.displayName}: " else ""
            val pairingHint by link.pairingHintFlow.collectAsStateWithLifecycle()
            val notice by link.noticeFlow.collectAsStateWithLifecycle()
            pairingHint?.let { MeshInfoCard(Icons.Filled.Lock, "${label}Pairing", it, MeshColors.warn) }
            notice?.let {
                MeshInfoCard(Icons.Filled.Info, if (two) "From the ${fw.displayName} radio" else "From the radio", it, MeshColors.warn) {
                    MeshBigButton("Dismiss", { link.notice = null }, tint = MeshColors.warn, filled = false)
                }
            }
        }
        if (inUse.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Radios", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = MeshColors.secondary)
                for ((fw, link) in inUse) {
                    MeshRadioCard(fw, link, onDisconnect = { link.disconnect() }, onForget = { link.forgetDevice() },
                        onReconnect = { link.reconnectLast() }, onSettings = { open(MeshRoute.RadioSettings(fw)) })
                }
                if (radios.canAdd) MeshBigButton("Add a radio", { open(MeshRoute.Devices) }, icon = Icons.Filled.Add, filled = false)
            }
        } else {
            MeshBigButton("Find a radio", { open(MeshRoute.Devices) }, icon = Icons.Filled.Search)
        }

        // One channel tile per network: the networks are never bridged.
        val tiles = inUse.ifEmpty { listOf(Firmware.MESHTASTIC to radios.link(Firmware.MESHTASTIC)) }
        for ((fw, link) in tiles) {
            val channel = MeshConversation.Channel(0)
            val name = link.radio.primaryChannelName
            TileRow(Icons.Filled.Forum,
                if (two) "${fw.displayName} · ${name.ifEmpty { "Channel chat" }}"
                else if (name.isEmpty()) "Channel chat" else "Channel: $name",
                link.messages(channel).lastOrNull()?.text ?: "Talk to everyone on the primary channel",
                link.unreadCount(channel), firmware = if (two) fw else null) { open(MeshRoute.Chat(channel, fw)) }
        }
        TileRow(Icons.Filled.Groups, "Nodes (${radios.sortedNodes.size})",
            if (two) "Radios either of your radios has heard" else "Radios your radio has heard", 0) { open(MeshRoute.Nodes) }
        TileRow(Icons.Filled.Hexagon, "Coverage map",
            when {
                drive.active -> "Drive mode on · ${drive.sent} pings, ${samples.size} points"
                recording -> "Recording · ${samples.size} points, private"
                two -> "Map where both radios hear others"
                else -> "Map where your radio hears others"
            }, 0) { open(MeshRoute.Coverage) }

        val threads = tiles.flatMap { (fw, link) -> link.directThreads.map { Triple(fw, link, it) } }
            .sortedByDescending { it.third.second.dateMillis }
        if (threads.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Direct messages", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = MeshColors.secondary)
                for ((fw, link, thread) in threads) {
                    val (node, last) = thread
                    MeshCard(onClick = { open(MeshRoute.Chat(MeshConversation.Direct(node.num), fw)) }) {
                        Row(Modifier.padding(12.dp).heightIn(min = MeshUI.minTap - 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            MeshNodeBadge(node, 44)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(node.displayName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                    if (two) FirmwareBadge(fw, compact = true)
                                }
                                Text(last.text, color = MeshColors.secondary, maxLines = 1, fontSize = 15.sp)
                            }
                            UnreadBadge(link.unreadCount(MeshConversation.Direct(node.num)))
                        }
                    }
                }
            }
        }

        val canSend = inUse.any { it.second.connection.isConnected }
        Button(
            onClick = { askSOS = true },
            enabled = canSend && !sendingSOS,
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).alpha(if (canSend) 1f else 0.4f),
            shape = RoundedCornerShape(MeshUI.corner),
            colors = ButtonDefaults.buttonColors(containerColor = MeshColors.danger, contentColor = Color.White,
                disabledContainerColor = MeshColors.danger, disabledContentColor = Color.White),
        ) {
            if (sendingSOS) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
            else Icon(Icons.Filled.Sos, null, Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Text(if (sendingSOS) "Getting location..." else "SOS", fontSize = 30.sp, fontWeight = FontWeight.Black)
        }
        if (!canSend) Text("Connect a radio to send an SOS.", color = MeshColors.secondary, fontSize = 14.sp)

        MeshBigButton("What is a mesh radio?", { open(MeshRoute.Info) }, icon = Icons.AutoMirrored.Filled.HelpOutline, filled = false)
    }

    if (askSOS) {
        val connectedCount = radios.connected.size
        AlertDialog(
            onDismissRequest = { askSOS = false },
            title = { Text("Send SOS to everyone on the mesh?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text((if (connectedCount > 1) "This message goes out on both your radios (Meshtastic and MeshCore), to every radio in range on each primary channel:"
                        else "This message goes to every radio in range on the primary channel:") +
                        "\n\n\"${radios.sosPreview}\"\n\nWith location, your GPS position is added (decimal degrees).")
                    MeshBigButton("Send SOS with my location", {
                        askSOS = false
                        if (MeshPermissions.hasLocation(context)) sendSOS(true) else askLocation(MeshPermissions.location)
                    }, tint = MeshColors.danger)
                    MeshBigButton("Send SOS without location", { askSOS = false; sendSOS(false) }, tint = MeshColors.danger, filled = false)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askSOS = false }) { Text("Cancel") } },
            containerColor = MeshColors.card,
        )
    }
    sosResult?.let {
        AlertDialog(onDismissRequest = { sosResult = null }, title = { Text("SOS") }, text = { Text(it) },
            confirmButton = { TextButton(onClick = { sosResult = null }) { Text("OK") } }, containerColor = MeshColors.card)
    }
}

/** What the SOS dialog says afterwards: the text sent, and how each radio did when there are two. */
internal fun sosSummary(results: List<MeshSOSResult>, withLocation: Boolean): String {
    val sent = results.firstNotNullOfOrNull { it.text }
        ?: return "Could not send. Check that the radio is connected, then try again."
    var msg = "Sent to the mesh:\n$sent"
    if (results.size > 1) {
        msg += "\n\n" + results.joinToString("\n") { r ->
            "${r.firmware.displayName} (${r.radioName}): " + if (r.text != null) "sent" else "NOT sent, check that radio"
        }
    }
    if (withLocation && !sent.contains("Location:")) msg += "\n\nNo GPS fix was available, so it went without coordinates."
    return msg
}

@Composable
private fun TileRow(icon: ImageVector, title: String, subtitle: String, badge: Int, firmware: Firmware? = null, onClick: () -> Unit) {
    MeshCard(onClick = onClick) {
        Row(Modifier.padding(16.dp).heightIn(min = 40.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = firmware?.let { FirmwareColors.of(it) } ?: MeshColors.accent, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(subtitle, fontSize = 15.sp, color = MeshColors.secondary, maxLines = 1)
            }
            UnreadBadge(badge)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MeshColors.secondary)
        }
    }
}
