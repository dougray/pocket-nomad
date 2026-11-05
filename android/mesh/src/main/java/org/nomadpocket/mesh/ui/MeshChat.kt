// MeshChat.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Chat thread for a channel or a direct message, and the block confirmation.
// Port of MeshChatView.swift.

package org.nomadpocket.mesh.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nomadpocket.mesh.Mesh
import org.nomadpocket.mesh.MeshConversation
import org.nomadpocket.mesh.MeshDeliveryState
import org.nomadpocket.mesh.MeshMessage
import org.nomadpocket.mesh.MeshNode
import org.nomadpocket.mesh.MeshService
import org.nomadpocket.mesh.MeshText
import java.util.Date
import java.util.Locale

@Composable
fun MeshChatScreen(conversation: MeshConversation, svc: MeshService = Mesh.service) {
    val messages by svc.messagesFlow.collectAsStateWithLifecycle()
    val connection by svc.connectionFlow.collectAsStateWithLifecycle()
    val radio by svc.radioFlow.collectAsStateWithLifecycle()
    val nodes by svc.nodesFlow.collectAsStateWithLifecycle()
    val thread = remember(messages, radio.myNodeNum) { svc.messages(conversation) }
    var draft by rememberSaveable { mutableStateOf("") }
    var blocking by remember { mutableStateOf<MeshNode?>(null) }
    val isChannel = conversation is MeshConversation.Channel
    val max = svc.maxTextBytes
    val bytes = MeshText.byteCount(draft.trim())
    val tooLong = bytes > max
    val canSend = connection.isConnected && bytes > 0 && !tooLong
    val list = rememberLazyListState()

    LaunchedEffect(thread.size) {
        svc.markRead(conversation)
        if (thread.isNotEmpty()) list.animateScrollToItem(thread.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (thread.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (isChannel) Icons.Filled.Forum else Icons.Filled.PersonSearch, null, tint = MeshColors.secondary, modifier = Modifier.size(44.dp))
                    Text(if (isChannel) "No messages yet. Anything you send here reaches every radio in range on this channel."
                        else "No messages yet. Only this person's radio can read what you send here.",
                        color = MeshColors.secondary, textAlign = TextAlign.Center)
                }
            }
            items(thread, key = { it.id }) { m ->
                val sender = if (m.isOutgoing || m.from == 0L) null else nodes[m.from] ?: MeshNode(num = m.from)
                MeshBubble(m, sender, isChannel, onLongClick = sender?.let { s -> { blocking = s } })
            }
        }
        Column(Modifier.fillMaxWidth().background(MeshColors.card).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!connection.isConnected) Text("Connect a radio to send.", color = MeshColors.warn, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Message") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 19.sp), maxLines = 5,
                    isError = tooLong, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MeshColors.background, unfocusedContainerColor = MeshColors.background))
                FilledIconButton(
                    onClick = { if (canSend && svc.send(draft, conversation)) draft = "" },
                    enabled = canSend, modifier = Modifier.size(MeshUI.minTap),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MeshColors.accent, contentColor = Color.Black),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
            Row {
                Text(if (tooLong) "Too long, trim ${bytes - max} bytes" else "Emoji and accents use extra bytes",
                    Modifier.weight(1f), fontSize = 13.sp,
                    color = if (tooLong) MeshColors.danger else if (bytes > max - 20) MeshColors.warn else MeshColors.secondary)
                Text("$bytes/$max", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (tooLong) MeshColors.danger else if (bytes > max - 20) MeshColors.warn else MeshColors.secondary)
            }
        }
    }
    BlockConfirmation(blocking, svc) { blocking = null }
}

/** Asks before blocking a radio on one network. Blocking deletes its messages on this phone. */
@Composable
fun BlockConfirmation(node: MeshNode?, svc: MeshService = Mesh.service, dismiss: () -> Unit) {
    if (node == null) return
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Block ${node.displayName}?") },
        text = { Text("Its messages are deleted from this phone and new ones are ignored. The other person isn't told. You can unblock it from the Nodes list.") },
        confirmButton = { TextButton(onClick = { svc.block(node.num); dismiss() }) { Text("Block", color = MeshColors.danger) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        containerColor = MeshColors.card,
    )
}

@Composable
fun MeshBubble(message: MeshMessage, sender: MeshNode?, showSender: Boolean, onLongClick: (() -> Unit)?) {
    val out = message.isOutgoing
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom) {
        if (!out && showSender && sender != null) { MeshNodeBadge(sender, 36); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.widthIn(max = 320.dp), horizontalAlignment = if (out) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // MeshCore channel messages carry a name even when the sender isn't a contact.
            val label = message.senderName ?: sender?.displayName
            if (!out && showSender && label != null) Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MeshColors.secondary)
            MeshCard(Modifier.clip(RoundedCornerShape(16.dp)), onClick = {}, onLongClick = onLongClick) {
                SelectionContainer {
                    Text(message.text, fontSize = 19.sp, color = if (out) Color.Black else Color.White,
                        modifier = Modifier.background(if (out) MeshColors.accent else MeshColors.card).padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val small = 12.sp
                Text(DateFormat.getTimeFormat(context).format(Date(message.dateMillis)), fontSize = small, color = MeshColors.secondary)
                message.rxSNR?.let { Text(String.format(Locale.ROOT, "SNR %.1f", it), fontSize = small, color = MeshColors.secondary) }
                message.hopsAway?.takeIf { it > 0 }?.let { Text("$it hop${if (it == 1L) "" else "s"}", fontSize = small, color = MeshColors.secondary) }
                if (out) {
                    val (text, color) = when (val d = message.delivery) {
                        MeshDeliveryState.Sent -> "✓ Sent" to MeshColors.secondary
                        MeshDeliveryState.HeardByMesh -> (if (message.isBroadcast) "Heard by mesh" else "Relayed") to MeshColors.ok
                        MeshDeliveryState.NotRepeated -> "✓ Sent · no one nearby repeated it" to MeshColors.secondary
                        MeshDeliveryState.Delivered -> "✓ Delivered" to MeshColors.ok
                        is MeshDeliveryState.Failed -> "Failed: ${d.reason}" to MeshColors.danger
                        MeshDeliveryState.Received -> "" to MeshColors.secondary
                    }
                    if (text.isNotEmpty()) Text(text, fontSize = small, color = color)
                }
            }
        }
        if (!out) Spacer(Modifier.width(40.dp))
    }
}

