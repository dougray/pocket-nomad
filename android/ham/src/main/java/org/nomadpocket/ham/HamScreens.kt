// HamScreens.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// The Ham screens: home, TNC picker, APRS (stations, messages, bulletins),
// Winlink mail, packet terminal, monitor, settings and reference.
// Port of the iOS Ham views. A small route stack, like MeshScreen.

@file:OptIn(ExperimentalMaterial3Api::class)

package org.nomadpocket.ham

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nomadpocket.mesh.ui.MeshCard
import org.nomadpocket.mesh.ui.MeshColors
import org.nomadpocket.mesh.ui.MeshPermissions
import org.nomadpocket.mesh.ui.MeshTheme
import org.nomadpocket.mesh.ui.rememberPermissionRequest
import java.text.DateFormat
import java.util.Date

sealed class HamRoute {
    object Tnc : HamRoute()
    object Monitor : HamRoute()
    object Stations : HamRoute()
    data class Station(val id: String) : HamRoute()
    object Messages : HamRoute()
    data class Thread(val peer: String) : HamRoute()
    object Bulletins : HamRoute()
    object Mail : HamRoute()
    data class Folder(val folder: WinlinkFolder) : HamRoute()
    data class Message(val mid: String, val folder: WinlinkFolder) : HamRoute()
    data class Compose(val replyTo: String? = null) : HamRoute()
    object Winlink : HamRoute()
    object Terminal : HamRoute()
    object Settings : HamRoute()
    object Reference : HamRoute()
}

private fun time(t: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
private fun dateTime(t: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

@Composable
fun HamScreen(modifier: Modifier = Modifier, demo: Boolean = false) {
    val context = LocalContext.current
    if (!Ham.isInitialized) Ham.init(context)
    val ham = Ham.service
    val stack = remember { mutableStateListOf<HamRoute>() }
    val open: (HamRoute) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isNotEmpty(), onBack = back)
    LaunchedEffect(Unit) {
        if (demo) ham.loadDemo()
        if (MeshPermissions.granted(context, MeshPermissions.bluetooth)) ham.reconnectLast()
    }
    MeshTheme {
        when (val r = stack.lastOrNull()) {
            null -> HamHome(open, modifier)
            HamRoute.Tnc -> TncPicker(back)
            HamRoute.Monitor -> MonitorScreen(back)
            HamRoute.Stations -> StationsScreen(back, open)
            is HamRoute.Station -> StationScreen(r.id, back, open)
            HamRoute.Messages -> ConversationsScreen(back, open)
            is HamRoute.Thread -> ThreadScreen(r.peer, back)
            HamRoute.Bulletins -> BulletinsScreen(back)
            HamRoute.Mail -> MailScreen(back, open)
            is HamRoute.Folder -> FolderScreen(r.folder, back, open)
            is HamRoute.Message -> MessageScreen(r.mid, r.folder, back, open)
            is HamRoute.Compose -> ComposeScreen(r.replyTo, back)
            HamRoute.Winlink -> WinlinkScreen(back)
            HamRoute.Terminal -> TerminalScreen(back)
            HamRoute.Settings -> SettingsScreen(back)
            HamRoute.Reference -> ReferenceScreen(back)
        }
    }
}

@Composable
private fun HamScaffold(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier,
                        actions: @Composable () -> Unit = {}, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Scaffold(
        modifier = modifier,
        containerColor = MeshColors.background,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, fontWeight = FontWeight.Bold) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MeshColors.background),
            )
        },
    ) { p -> Column(Modifier.padding(p).fillMaxSize()) { content() } }
}

@Composable
private fun Section(title: String? = null, footer: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        title?.let { Text(it.uppercase(), color = MeshColors.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) }
        Column(Modifier.fillMaxWidth().background(MeshColors.card, RoundedCornerShape(16.dp)).padding(vertical = 4.dp)) { content() }
        footer?.let { Text(it, color = MeshColors.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
    }
}

@Composable
private fun Row(title: String, detail: String? = null, badge: Int = 0, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = if (enabled) Color.White else MeshColors.secondary, modifier = Modifier.weight(1f))
        detail?.let { Text(it, color = MeshColors.secondary, fontSize = 14.sp) }
        if (badge > 0) {
            Box(Modifier.padding(start = 8.dp).background(MeshColors.accent, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("$badge", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (onClick != null) Text("  ›", color = MeshColors.secondary)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, caps: Boolean = false, password: Boolean = false, singleLine: Boolean = true) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
}

// MARK: Home

@Composable
private fun HamHome(open: (HamRoute) -> Unit, modifier: Modifier) {
    val ham = Ham.service
    val s = ham.settings
    val context = LocalContext.current
    val request = rememberPermissionRequest { if (it.values.all { g -> g }) open(HamRoute.Tnc) }
    HamScaffold("Ham", null, modifier) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            MeshCard(Modifier.padding(16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(when (val t = ham.tnc) {
                            is HamService.TncState.Idle -> "No TNC connected"
                            is HamService.TncState.Connecting -> "Connecting to ${t.name}…"
                            is HamService.TncState.Connected -> t.name
                            is HamService.TncState.Failed -> "TNC disconnected"
                        }, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(when (val t = ham.tnc) {
                            is HamService.TncState.Idle -> "Pair a Bluetooth KISS TNC or radio"
                            is HamService.TncState.Connecting -> "Turn on the radio's KISS/TNC mode"
                            is HamService.TncState.Connected -> "${ham.packetsHeard} packets heard"
                            is HamService.TncState.Failed -> t.reason
                        }, color = MeshColors.secondary, fontSize = 14.sp)
                    }
                    Button(
                        onClick = {
                            if (ham.tncConnected) ham.disconnect()
                            else if (MeshPermissions.granted(context, MeshPermissions.bluetooth)) open(HamRoute.Tnc)
                            else request(MeshPermissions.bluetooth)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (ham.tncConnected) Color.Gray else MeshColors.accent, contentColor = Color.Black),
                    ) { Text(if (ham.tncConnected) "Disconnect" else "Connect") }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (s.canTransmit) Icons.Filled.Verified else Icons.Filled.Hearing, null,
                    tint = if (s.canTransmit) MeshColors.ok else MeshColors.secondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (s.canTransmit) "Licensed station ${s.baseCall}: sending is on." else "Listen only. Add your callsign in Station settings to send.",
                    color = if (s.canTransmit) MeshColors.ok else MeshColors.secondary, fontSize = 13.sp)
            }
            ham.notice?.let { n ->
                Section {
                    Text(n, modifier = Modifier.padding(16.dp))
                    TextButton(onClick = { ham.notice = null }, modifier = Modifier.padding(start = 8.dp)) { Text("Dismiss") }
                }
            }
            Section("APRS") {
                Row("Messages", badge = ham.unreadTotal) { open(HamRoute.Messages) }
                Row("Stations heard", ham.stations.size.takeIf { it > 0 }?.toString()) { open(HamRoute.Stations) }
                Row("Bulletins", ham.bulletins.size.takeIf { it > 0 }?.toString()) { open(HamRoute.Bulletins) }
                if (s.canTransmit) Row("Send my position", ham.lastBeacon?.let { "last " + time(it) }, enabled = ham.tncConnected) { ham.beacon() }
            }
            Section("Winlink email", "Email over radio through a Winlink RMS packet gateway. No internet needed on your end.") {
                Row("Mail", if (ham.outbox.isEmpty()) null else "${ham.outbox.size} to send", ham.unreadMail) { open(HamRoute.Mail) }
                Row("Connect to a gateway", when (ham.winlink) {
                    is HamService.WinlinkState.Connecting -> "connecting…"; is HamService.WinlinkState.Exchanging -> "exchanging…"
                    is HamService.WinlinkState.Done -> "done"; is HamService.WinlinkState.Failed -> "failed"; else -> null
                }) { open(HamRoute.Winlink) }
            }
            Section("BBS and nodes", "Connect to a packet BBS, node or another station and type to it.") {
                Row("Packet terminal", ham.terminalPeer) { open(HamRoute.Terminal) }
            }
            Section {
                Row("Packet monitor", if (ham.packetsHeard > 0) "${ham.packetsHeard} heard" else null) { open(HamRoute.Monitor) }
                Row("Frequencies and reference") { open(HamRoute.Reference) }
                Row("Station settings") { open(HamRoute.Settings) }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun TncPicker(back: () -> Unit) {
    val ham = Ham.service
    DisposableEffect(Unit) { ham.startScan(); onDispose { ham.stopScan() } }
    HamScaffold("Connect a TNC", back) {
        LazyColumn {
            item {
                Text("Bluetooth LE KISS TNCs (Mobilinkd TNC3/TNC4; BTech UV-Pro, Vero VR-N76, Radioddity GA-5WB with KISS TNC turned on) appear as they're found. Classic-Bluetooth TNCs such as the Kenwood TH-D74/D75 show here once paired in Android's Bluetooth settings.",
                    color = MeshColors.secondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show all Bluetooth devices")
                        Text("If your radio isn't listed: tries any serial service it offers; the monitor lists what it found.", color = MeshColors.secondary, fontSize = 12.sp)
                    }
                    Switch(ham.showAllDevices, { ham.showAllDevices = it; ham.startScan() })
                }
            }
            if (ham.devices.isEmpty()) item { Text("Looking for TNCs…", modifier = Modifier.padding(16.dp)) }
            items(ham.devices, key = { it.address }) { d ->
                Row(d.name, if (d.classic) "classic (paired)" else if (d.rssi != 0) "${d.rssi} dBm" else null) { ham.connect(d); back() }
            }
        }
    }
}

// MARK: Monitor and APRS

@Composable
private fun MonitorScreen(back: () -> Unit) {
    val ham = Ham.service
    var filter by rememberSaveable { mutableStateOf(0) }
    HamScaffold("Monitor", back, actions = { TextButton(onClick = { ham.clearMonitor() }) { Text("Clear") } }) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            listOf("All", "APRS", "Mine").forEachIndexed { i, l ->
                SegmentedButton(selected = filter == i, onClick = { filter = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
            }
        }
        val rows = ham.monitor.reversed().filter { when (filter) { 1 -> it.kind != null; 2 -> it.outgoing; else -> true } }
        LazyColumn(Modifier.fillMaxSize()) {
            if (rows.isEmpty()) item {
                Text(if (ham.tncConnected) "Listening. Packets show here as the radio hears them." else "Connect a TNC to start listening.",
                    color = MeshColors.secondary, modifier = Modifier.padding(16.dp))
            }
            items(rows, key = { it.id }) { e ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(time(e.time) + (if (e.outgoing) "  SENT" else "") + (e.kind?.let { "  " + it.name.lowercase() } ?: ""),
                        color = if (e.outgoing) MeshColors.accent else MeshColors.secondary, fontSize = 11.sp)
                    Text(e.line, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun stationSubtitle(s: HamStation) =
    listOfNotNull(s.weather, s.micEStatus, s.comment.ifEmpty { null }, s.status.ifEmpty { null }).joinToString(" · ")

private fun distance(s: HamStation, me: Pair<Double, Double>?): String? {
    if (me == null || s.latitude == null || s.longitude == null) return null
    val mi = HamGeo.distanceKm(me.first, me.second, s.latitude, s.longitude) * 0.621371
    val dir = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((HamGeo.bearing(me.first, me.second, s.latitude, s.longitude) + 22.5) / 45).toInt() % 8]
    return if (mi < 10) "%.1f mi %s".format(mi, dir) else "%.0f mi %s".format(mi, dir)
}

@Composable
private fun StationsScreen(back: () -> Unit, open: (HamRoute) -> Unit) {
    val ham = Ham.service
    var search by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { ham.refreshPosition() }
    HamScaffold("Stations", back, actions = { TextButton(onClick = { ham.forgetStations() }) { Text("Forget all") } }) {
        Field(search, { search = it }, "Callsign", caps = true)
        LazyColumn {
            val list = ham.stations.filter { search.isEmpty() || it.id.contains(search, true) }
            if (list.isEmpty()) item { Text("APRS stations, objects and weather reports appear here as they're heard.", color = MeshColors.secondary, modifier = Modifier.padding(16.dp)) }
            items(list, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().clickable { open(HamRoute.Station(s.id)) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(s.id, fontWeight = FontWeight.Bold)
                            if (s.emergency) Text("  EMERGENCY", color = MeshColors.danger, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Text(stationSubtitle(s), color = MeshColors.secondary, fontSize = 14.sp, maxLines = 2)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        distance(s, ham.myPosition)?.let { Text(it, fontSize = 14.sp) }
                        Text(time(s.lastHeard), color = MeshColors.secondary, fontSize = 11.sp)
                    }
                }
                HorizontalDivider(color = MeshColors.card)
            }
        }
    }
}

@Composable
private fun StationScreen(id: String, back: () -> Unit, open: (HamRoute) -> Unit) {
    val ham = Ham.service
    val s = ham.stations.firstOrNull { it.id == id }
    HamScaffold(id, back) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (s == null) { Text("Not heard yet.", modifier = Modifier.padding(16.dp)); return@Column }
            Section("Details") {
                @Composable fun kv(k: String, v: String) = Row(k, v)
                if (s.latitude != null && s.longitude != null) {
                    kv("Position", "%.5f, %.5f".format(s.latitude, s.longitude))
                    kv("Grid", HamGeo.maidenhead(s.latitude, s.longitude))
                }
                distance(s, ham.myPosition)?.let { kv("From you", it) }
                s.speedKnots?.takeIf { it > 0 }?.let { kv("Speed", "%.0f mph".format(it * 1.15078)) }
                s.courseDegrees?.let { kv("Course", "$it°") }
                s.altitudeFeet?.let { kv("Altitude", "%.0f ft".format(it)) }
                s.weather?.let { kv("Weather", it) }
                s.micEStatus?.let { kv("Status", it) }
                if (s.comment.isNotEmpty()) kv("Comment", s.comment)
                if (s.status.isNotEmpty()) kv("Status text", s.status)
                kv("Heard via", if (s.path.isEmpty()) "direct" else s.path.joinToString(", "))
                kv("Packets", s.packets.toString())
                kv("First heard", dateTime(s.firstHeard))
            }
            if (!s.isObject) Section { Row("Message ${s.source}") { open(HamRoute.Thread(s.source)) } }
        }
    }
}

@Composable
private fun ConversationsScreen(back: () -> Unit, open: (HamRoute) -> Unit) {
    val ham = Ham.service
    var newPeer by remember { mutableStateOf<String?>(null) }
    HamScaffold("APRS messages", back, actions = {
        if (ham.settings.canTransmit) IconButton(onClick = { newPeer = "" }) { Icon(Icons.Filled.Edit, "New message") }
    }) {
        LazyColumn {
            if (ham.conversations.isEmpty()) item {
                Text("APRS messages to and from your callsign appear here. Anyone in range (or through a digipeater or iGate) can reach you.",
                    color = MeshColors.secondary, modifier = Modifier.padding(16.dp))
            }
            items(ham.conversations, key = { it.first }) { (peer, last) ->
                Row(peer, null, ham.unread[peer] ?: 0) { open(HamRoute.Thread(peer)) }
                Text(last.text, color = MeshColors.secondary, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            }
        }
    }
    newPeer?.let { p ->
        AlertDialog(onDismissRequest = { newPeer = null }, title = { Text("New message") },
            text = { OutlinedTextField(p, { newPeer = it.uppercase() }, label = { Text("Callsign, e.g. KF0XYZ-7") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { newPeer = null; if (p.isNotBlank()) open(HamRoute.Thread(p.trim())) }) { Text("Write") } },
            dismissButton = { TextButton(onClick = { newPeer = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ThreadScreen(peer: String, back: () -> Unit) {
    val ham = Ham.service
    var draft by rememberSaveable { mutableStateOf("") }
    val msgs = ham.chats.filter { it.peer == peer }
    val list = rememberLazyListState()
    LaunchedEffect(peer) { ham.markPeerRead(peer) }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    HamScaffold(peer, back) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(msgs, key = { it.id }) { c ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = if (c.outgoing) Alignment.End else Alignment.Start) {
                    Text(c.text, color = if (c.outgoing) Color.Black else Color.White,
                        modifier = Modifier.background(if (c.outgoing) MeshColors.accent else MeshColors.card, RoundedCornerShape(14.dp)).padding(10.dp))
                    Text(time(c.time) + when (c.status) {
                        AprsChat.Status.SENDING -> " · sending (try ${c.tries} of 5)"; AprsChat.Status.ACKED -> " · delivered"
                        AprsChat.Status.FAILED -> " · no acknowledgement"; AprsChat.Status.REJECTED -> " · rejected"; else -> ""
                    }, color = if (c.status == AprsChat.Status.FAILED) MeshColors.danger else MeshColors.secondary, fontSize = 11.sp)
                }
            }
        }
        if (ham.settings.canTransmit) {
            Row(Modifier.fillMaxWidth().imePadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, placeholder = { Text("Message (67 characters per packet)") }, modifier = Modifier.weight(1f))
                IconButton(onClick = { ham.sendMessage(peer, draft); draft = "" }, enabled = draft.isNotBlank() && ham.tncConnected) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MeshColors.accent)
                }
            }
        } else Text("Listen only: add your callsign in Station settings to reply.", color = MeshColors.secondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun BulletinsScreen(back: () -> Unit) {
    val ham = Ham.service
    HamScaffold("Bulletins", back) {
        LazyColumn {
            if (ham.bulletins.isEmpty()) item {
                Text("Bulletins (BLN) and announcements heard on APRS: nets, weather alerts, emergency information.",
                    color = MeshColors.secondary, modifier = Modifier.padding(16.dp))
            }
            items(ham.bulletins, key = { it.id }) { b ->
                Column(Modifier.padding(16.dp)) {
                    Text("${b.source}  ${b.addressee}  ${time(b.time)}", color = MeshColors.secondary, fontSize = 12.sp)
                    Text(b.text)
                }
            }
        }
    }
}

// MARK: Winlink

@Composable
private fun MailScreen(back: () -> Unit, open: (HamRoute) -> Unit) {
    val ham = Ham.service
    HamScaffold("Winlink mail", back, actions = {
        if (ham.settings.callsignValid) IconButton(onClick = { open(HamRoute.Compose()) }) { Icon(Icons.Filled.Edit, "New message") }
    }) {
        Section(footer = "Messages wait in the Outbox until you connect. Your address is ${if (ham.settings.callsignValid) ham.settings.baseCall else "YOURCALL"}@winlink.org.") {
            for (f in WinlinkFolder.entries) Row(f.title, ham.messages(f).size.toString()) { open(HamRoute.Folder(f)) }
            Row("Connect to a gateway to send and receive") { open(HamRoute.Winlink) }
        }
    }
}

@Composable
private fun FolderScreen(folder: WinlinkFolder, back: () -> Unit, open: (HamRoute) -> Unit) {
    val ham = Ham.service
    HamScaffold(folder.title, back) {
        val msgs = ham.messages(folder)
        LazyColumn {
            if (msgs.isEmpty()) item { Text("Nothing here.", color = MeshColors.secondary, modifier = Modifier.padding(16.dp)) }
            items(msgs, key = { it.mid }) { m ->
                Column(Modifier.fillMaxWidth().clickable { open(HamRoute.Message(m.mid, folder)) }.padding(16.dp)) {
                    Row {
                        if (folder == WinlinkFolder.INBOX && m.mid !in ham.readMids) Box(Modifier.padding(top = 6.dp, end = 6.dp).size(8.dp).background(MeshColors.accent, CircleShape))
                        Text(if (folder == WinlinkFolder.INBOX) WinlinkMessage.displayAddress(m.from) else m.to.joinToString(", ") { WinlinkMessage.displayAddress(it) },
                            fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(dateTime(m.dateMillis), color = MeshColors.secondary, fontSize = 12.sp)
                    }
                    Text(m.subject, maxLines = 1)
                    Text(m.body, color = MeshColors.secondary, fontSize = 14.sp, maxLines = 2)
                }
                HorizontalDivider(color = MeshColors.card)
            }
        }
    }
}

@Composable
private fun MessageScreen(mid: String, folder: WinlinkFolder, back: () -> Unit, open: (HamRoute) -> Unit) {
    val ham = Ham.service
    val m = ham.messages(folder).firstOrNull { it.mid == mid }
    LaunchedEffect(mid) { if (folder == WinlinkFolder.INBOX) ham.markRead(mid) }
    HamScaffold(folder.title, back, actions = {
        if (m != null && folder == WinlinkFolder.INBOX && ham.settings.callsignValid) TextButton(onClick = { open(HamRoute.Compose(mid)) }) { Text("Reply") }
        if (m != null) TextButton(onClick = { ham.delete(m, folder); back() }) { Text("Delete") }
    }) {
        if (m == null) return@HamScaffold
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(m.subject, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("From: " + WinlinkMessage.displayAddress(m.from), color = MeshColors.secondary)
            Text("To: " + m.to.joinToString(", ") { WinlinkMessage.displayAddress(it) }, color = MeshColors.secondary)
            if (m.cc.isNotEmpty()) Text("Cc: " + m.cc.joinToString(", ") { WinlinkMessage.displayAddress(it) }, color = MeshColors.secondary)
            Text(dateTime(m.dateMillis), color = MeshColors.secondary)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(m.body)
            for (a in m.attachments) Text("📎 ${a.name} (${a.data.size} bytes)", color = MeshColors.accent, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ComposeScreen(replyTo: String?, back: () -> Unit) {
    val ham = Ham.service
    val r = replyTo?.let { id -> ham.inbox.firstOrNull { it.mid == id } }
    var to by rememberSaveable { mutableStateOf(r?.let { WinlinkMessage.displayAddress(it.from) } ?: "") }
    var cc by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf(r?.let { if (it.subject.lowercase().startsWith("re:")) it.subject else "Re: " + it.subject } ?: "") }
    var body by rememberSaveable { mutableStateOf(r?.let { "\n\n--- ${WinlinkMessage.displayAddress(it.from)} wrote:\n" + it.body } ?: "") }
    fun split(s: String) = s.split(',', ';', ' ').filter { it.isNotBlank() }
    HamScaffold(if (r == null) "New message" else "Reply", back, actions = {
        TextButton(enabled = to.isNotBlank() && subject.isNotBlank(), onClick = { if (ham.compose(split(to), split(cc), subject, body)) back() }) { Text("Outbox") }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding()) {
            Field(to, { to = it }, "To: callsign or email")
            Field(cc, { cc = it }, "Cc (optional)")
            Field(subject, { subject = it }, "Subject")
            OutlinedTextField(body, { body = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(12.dp))
            Text("Separate addresses with commas. Keep it short: packet is slow. No encryption or business use on amateur radio.",
                color = MeshColors.secondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun WinlinkScreen(back: () -> Unit) {
    val ham = Ham.service
    val s = ham.settings
    var call by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    HamScaffold("Winlink gateway", back) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Section("Session", "${ham.outbox.size} message(s) in the Outbox." + if (s.hasWinlinkPassword) "" else " No Winlink password set: gateways that ask for it will refuse the login.") {
                Column(Modifier.padding(16.dp)) {
                    when (val w = ham.winlink) {
                        is HamService.WinlinkState.Idle -> Text("Choose a gateway below.", color = MeshColors.secondary)
                        is HamService.WinlinkState.Connecting -> Text("Connecting to ${w.gw}…")
                        is HamService.WinlinkState.Exchanging -> {
                            Text("Exchanging mail with ${w.gw}")
                            ham.winlinkProgress?.let { p -> if (p.third > 0) { Text(p.first, fontSize = 12.sp); LinearProgressIndicator(progress = { p.second.toFloat() / p.third }, Modifier.fillMaxWidth()) } }
                        }
                        is HamService.WinlinkState.Done -> Text(w.summary, color = MeshColors.ok)
                        is HamService.WinlinkState.Failed -> Text(w.reason, color = MeshColors.danger)
                    }
                    if (ham.winlink.busy) OutlinedButton(onClick = { ham.abortWinlink() }) { Text("Stop") }
                }
            }
            Section("Gateways", "Find packet RMS gateways near you at winlink.org/RMSChannels (save the list before you need it). Tune the radio to the gateway's frequency first.") {
                for (g in s.gateways) {
                    Row(g.call, listOf(if (g.path.isEmpty()) "direct" else "via " + g.path, g.note).filter { it.isNotEmpty() }.joinToString(" · "),
                        enabled = !ham.winlink.busy && ham.tncConnected && s.canTransmit) { ham.connectWinlink(g) }
                }
                if (s.gateways.isNotEmpty()) TextButton(onClick = { s.gateways = emptyList(); s.save() }, modifier = Modifier.padding(start = 8.dp)) { Text("Remove all gateways") }
            }
            Section("Add a gateway") {
                Field(call, { call = it.uppercase() }, "Gateway callsign, e.g. KF0RMS-10", caps = true)
                Field(path, { path = it.uppercase() }, "Path (optional)", caps = true)
                Field(note, { note = it }, "Note, e.g. 145.010 MHz")
                TextButton(enabled = Ax25Address.parse(call) != null && Ax25Path.parse(path) != null, onClick = {
                    s.gateways = s.gateways + HamSavedStation(call = call, path = path, note = note); s.save(); call = ""; path = ""; note = ""
                }, modifier = Modifier.padding(start = 8.dp)) { Text("Save gateway") }
            }
            if (ham.winlinkLog.isNotEmpty()) Section("Session log") {
                Text(ham.winlinkLog.takeLast(60).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

// MARK: Terminal

@Composable
private fun TerminalScreen(back: () -> Unit) {
    val ham = Ham.service
    val s = ham.settings
    var call by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("") }
    var line by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(ham.terminalText.length) { scroll.animateScrollTo(scroll.maxValue) }
    HamScaffold(ham.terminalPeer ?: "Terminal", back, actions = {
        if (ham.terminalState == Ax25Link.State.CONNECTED || ham.terminalState == Ax25Link.State.CONNECTING) TextButton(onClick = { ham.disconnectTerminal() }) { Text("Disconnect") }
        TextButton(onClick = { ham.clearTerminal() }) { Text("Clear") }
    }) {
        if (ham.terminalState == Ax25Link.State.DISCONNECTED) {
            if (!s.canTransmit) Text("Listen only: connecting transmits, so it needs your callsign and licence in Station settings.", color = MeshColors.secondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            Row {
                Box(Modifier.weight(1f)) { Field(call, { call = it.uppercase() }, "Callsign, e.g. KF0BBS-1", caps = true) }
                Box(Modifier.weight(1f)) { Field(path, { path = it.uppercase() }, "via (optional)", caps = true) }
            }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = Ax25Address.parse(call) != null && ham.tncConnected && s.canTransmit, onClick = { ham.connectTerminal(call, path) },
                    colors = ButtonDefaults.buttonColors(containerColor = MeshColors.accent, contentColor = Color.Black)) { Text("Connect") }
                if (Ax25Address.parse(call) != null) TextButton(onClick = { s.nodes = s.nodes + HamSavedStation(call = call, path = path); s.save() }) { Text("Save") }
            }
            if (s.nodes.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                for (n in s.nodes) OutlinedButton(onClick = { call = n.call; path = n.path }, modifier = Modifier.padding(end = 6.dp)) {
                    Text(if (n.path.isEmpty()) n.call else "${n.call} via ${n.path}")
                }
            }
        }
        Text(ham.terminalText.ifEmpty { "Not connected." }, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0A0A0C)).verticalScroll(scroll).padding(10.dp))
        if (ham.terminalState == Ax25Link.State.CONNECTED) {
            Row(Modifier.imePadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(line, { line = it }, singleLine = true, placeholder = { Text("Command") }, modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { ham.sendTerminal(line); line = "" }))
                TextButton(onClick = { ham.sendTerminal(line); line = "" }) { Text("Send") }
            }
        }
    }
}

// MARK: Settings and reference

@Composable
private fun SettingsScreen(back: () -> Unit) {
    val ham = Ham.service
    val s = ham.settings
    var password by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { s.save() } }
    @Composable fun toggle(label: String, value: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) =
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f)); Switch(value, set, enabled = enabled)
        }
    @Composable fun stepper(label: String, value: Int, range: IntRange, step: Int, show: (Int) -> String, set: (Int) -> Unit) =
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$label ${show(value)}", Modifier.weight(1f))
            TextButton(onClick = { set((value - step).coerceIn(range)) }) { Text("−") }
            TextButton(onClick = { set((value + step).coerceIn(range)) }) { Text("+") }
        }
    HamScaffold("Station settings", back) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding()) {
            Section("Licence", "Listen only (no callsign) still decodes everything the radio hears: APRS stations, messages, bulletins, weather and the packet monitor. It never transmits.") {
                Text("Sending on amateur radio bands needs an amateur radio licence. In the US that's an FCC licence, and the callsign is how you prove it. Winlink also needs a free account created with that callsign. Pocket Nomad doesn't check your licence: you are responsible for what your station sends.",
                    color = MeshColors.warn, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                Field(s.callsign, { s.callsign = it.uppercase(); if (!s.callsignValid) s.licensed = false }, "Callsign, e.g. KF0ABC", caps = true)
                if (s.callsign.isNotEmpty() && !s.callsignValid) Text("That doesn't look like an amateur callsign.", color = MeshColors.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
                toggle("I hold an amateur radio licence for this callsign", s.licensed, s.callsignValid) { s.licensed = it }
                Row("Mode", if (s.canTransmit) "Licensed: sending on" else "Listen only")
            }
            Section("Winlink", "The password is encrypted on this phone and only used to answer the gateway's login challenge; it is never sent in the clear.") {
                Field(password, { password = it }, if (s.hasWinlinkPassword) "Password saved (type to change)" else "Winlink password", password = true)
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(enabled = password.isNotEmpty(), onClick = { s.winlinkPassword = password; password = "" }) { Text("Save password") }
                    if (s.hasWinlinkPassword) TextButton(onClick = { s.winlinkPassword = null }) { Text("Remove", color = MeshColors.danger) }
                }
            }
            Section("APRS", "Path WIDE1-1,WIDE2-1 suits most areas; WIDE2-1 in busy cities; ARISS reaches the space station on 145.825 MHz. Position beacons share your location with everyone listening, and APRS-IS puts them on public websites.") {
                stepper("APRS SSID", s.aprsSsid, 0..15, 1, { "-$it" + mapOf(0 to " home", 7 to " handheld", 9 to " car", 5 to " phone", 13 to " weather")[it].orEmpty() }) { s.aprsSsid = it }
                val symbols = listOf("/[" to "Person", "/>" to "Car", "/b" to "Bicycle", "/-" to "House", "/;" to "Campsite", "/k" to "Truck", "/s" to "Boat", "/+" to "Red Cross", "\\!" to "Emergency")
                val si = symbols.indexOfFirst { it.first == s.symbol }.coerceAtLeast(0)
                stepper("Symbol", si, symbols.indices, 1, { symbols[it].second }) { s.symbol = symbols[it].first }
                Field(s.aprsPath, { s.aprsPath = it.uppercase() }, "Path", caps = true)
                Field(s.beaconComment, { s.beaconComment = it }, "Beacon comment")
                val intervals = listOf(0, 10, 20, 30, 60)
                stepper("Send position", intervals.indexOf(s.beaconMinutes).coerceAtLeast(0), intervals.indices, 1,
                    { if (intervals[it] == 0) "when I tap" else "every ${intervals[it]} min" }) { s.beaconMinutes = intervals[it] }
                toggle("Acknowledge messages to me", s.autoAck) { s.autoAck = it }
            }
            Section(footer = "Lets another station connect to your callsign and chat in the packet terminal.") {
                toggle("Accept incoming connections", s.acceptIncoming) { s.acceptIncoming = it }
            }
            Section("TNC and link (advanced)") {
                stepper("TX delay", s.txDelay, 5..100, 5, { "${it * 10} ms" }) { s.txDelay = it }
                stepper("Persistence", s.persistence, 0..255, 16, { "$it" }) { s.persistence = it }
                stepper("Slot time", s.slotTime, 1..50, 1, { "${it * 10} ms" }) { s.slotTime = it }
                stepper("Window", s.window, 1..7, 1, { "$it frames" }) { s.window = it }
                stepper("Packet length", s.paclen, 32..256, 32, { "$it bytes" }) { s.paclen = it }
            }
            Section(footer = "Stations, APRS messages, bulletins and Winlink mail are stored only on this phone and are not backed up.") {
                Row("Delete all ham data on this phone") { confirmDelete = true }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete all stations, messages and mail?") },
        confirmButton = { TextButton(onClick = { ham.deleteAllData(); confirmDelete = false }) { Text("Delete", color = MeshColors.danger) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun ReferenceScreen(back: () -> Unit) {
    val freqs = listOf(
        "144.390 MHz" to "APRS, North America", "144.800 MHz" to "APRS, Europe and Africa", "145.175 MHz" to "APRS, Australia",
        "145.825 MHz" to "Space station (ISS) packet digipeater, path ARISS", "145.010–145.090 MHz" to "Common 1200-baud packet, BBS and Winlink (US)",
        "146.520 MHz" to "2 m FM national simplex calling (US)", "446.000 MHz" to "70 cm FM national simplex calling (US)",
        "52.525 MHz" to "6 m FM simplex calling (US)", "14.300 MHz USB" to "Maritime Mobile Service Net (HF)", "14.325 MHz USB" to "Hurricane Watch Net (HF, when active)",
    )
    val q = listOf("QTH" to "Location", "QSL" to "I confirm / received", "QRZ" to "Who is calling me?", "QSY" to "Change frequency",
        "QRM" to "Interference from other stations", "QRN" to "Static / natural noise", "QRT" to "Stopping / closing down",
        "QRX" to "Wait / stand by", "QSO" to "A contact", "QRP" to "Low power")
    HamScaffold("Reference", back) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Section("Frequencies", "Local repeaters, nets and Winlink gateways vary: write yours down before an emergency. Band plans differ by country.") {
                for ((f, d) in freqs) Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Text(f, fontWeight = FontWeight.Bold); Text(d, color = MeshColors.secondary, fontSize = 14.sp) }
            }
            Section("NOAA Weather Radio", "Receive only; most handhelds hear this band. The Conditions screen (Survive tab) lists the nearest transmitters once you've updated it.") {
                for ((f, ch) in listOf("162.400" to "WX2", "162.425" to "WX4", "162.450" to "WX5", "162.475" to "WX3", "162.500" to "WX6", "162.525" to "WX7", "162.550" to "WX1"))
                    Row("$f MHz", "Channel $ch")
            }
            Section("Getting on the air (US)") {
                Text("The entry-level Technician licence covers every amateur band above 50 MHz, which includes 2 m and 70 cm where APRS and packet live. The exam is 35 multiple-choice questions, no Morse code, run by volunteer examiners; the FCC charges a small fee.\n\nIdentify with your callsign at least every 10 minutes during a contact and at the end. No encryption, no business traffic, no music.",
                    modifier = Modifier.padding(16.dp))
            }
            Section("Q codes") { for ((k, v) in q) Row(k, v) }
            Section("Prosigns and shorthand") {
                Row("CQ", "Calling anyone"); Row("DE", "From"); Row("73", "Best regards"); Row("K / over", "Your turn")
                Row("SK / clear", "End of contact"); Row("Mayday", "Life at risk: others stand by")
            }
            Section("Phonetic alphabet") {
                Text("Alfa · Bravo · Charlie · Delta · Echo · Foxtrot · Golf · Hotel · India · Juliett · Kilo · Lima · Mike · November · Oscar · Papa · Quebec · Romeo · Sierra · Tango · Uniform · Victor · Whiskey · X-ray · Yankee · Zulu",
                    modifier = Modifier.padding(16.dp))
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}
