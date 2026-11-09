// MeshComponents.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Theme and small shared composables: status card, help cards, big buttons,
// node badge, permissions. Port of MeshComponents.swift.

package org.nomadpocket.mesh.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.WifiTetheringOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nomadpocket.mesh.Mesh
import org.nomadpocket.mesh.MeshBluetoothAvailability
import org.nomadpocket.mesh.MeshConnectionState
import org.nomadpocket.mesh.MeshNode
import org.nomadpocket.mesh.MeshRadioInfo
import org.nomadpocket.mesh.displayName

object MeshColors {
    val accent = Color(0xFFFC9712)
    val danger = Color(0xFFE5484D)
    val ok = Color(0xFF46C46E)
    val warn = Color(0xFFFFD43B)
    val card = Color(0xFF1D1D21)
    val background = Color(0xFF101013)
    val secondary = Color(0xFFA0A0A8)
}

object MeshUI {
    val minTap = 60.dp
    val corner = 16.dp
}

private val meshScheme = darkColorScheme(
    primary = MeshColors.accent,
    onPrimary = Color.Black,
    secondary = MeshColors.accent,
    onSecondary = Color.Black,
    background = MeshColors.background,
    onBackground = Color.White,
    surface = MeshColors.background,
    onSurface = Color.White,
    surfaceVariant = MeshColors.card,
    onSurfaceVariant = MeshColors.secondary,
    surfaceContainer = MeshColors.card,
    error = MeshColors.danger,
)

/** Dark, amber, big text: the mesh screens look the same whatever the host theme is. */
@Composable
fun MeshTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = meshScheme, content = content)

/** Large, full-width button for gloves. */
@Composable
fun MeshBigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
                  tint: Color = MeshColors.accent, filled: Boolean = true, enabled: Boolean = true) {
    val content: @Composable () -> Unit = {
        if (icon != null) { Icon(icon, null); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
    val m = modifier.fillMaxWidth().heightIn(min = MeshUI.minTap)
    if (filled) {
        Button(onClick, m, enabled = enabled, shape = RoundedCornerShape(MeshUI.corner),
            colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = Color.Black)) { content() }
    } else {
        OutlinedButton(onClick, m, enabled = enabled, shape = RoundedCornerShape(MeshUI.corner),
            border = BorderStroke(2.dp, if (enabled) tint else tint.copy(alpha = 0.4f)),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MeshColors.card, contentColor = tint)) { content() }
    }
}

/** A card with an icon, title and body text. */
@Composable
fun MeshInfoCard(icon: ImageVector, title: String, message: String, tint: Color = MeshColors.accent,
                 actions: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MeshUI.corner)).background(MeshColors.card)
            .border(1.5.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(MeshUI.corner)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint)
            Spacer(Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        Text(message, fontSize = 16.sp)
        actions?.invoke()
    }
}

/** Shows the connection state in words, with colour and an icon. */
@Composable
fun MeshStatusCard(state: MeshConnectionState, radio: MeshRadioInfo, onTap: (() -> Unit)?) {
    val tint = when (state) {
        is MeshConnectionState.Connected -> MeshColors.ok
        is MeshConnectionState.Failed -> MeshColors.danger
        MeshConnectionState.Idle, is MeshConnectionState.NotNearby, is MeshConnectionState.NotWithMe -> MeshColors.secondary
        else -> MeshColors.warn
    }
    val icon = when (state) {
        is MeshConnectionState.Connected -> Icons.Filled.SettingsInputAntenna
        is MeshConnectionState.Failed -> Icons.Filled.Warning
        MeshConnectionState.Idle -> Icons.Filled.WifiTetheringOff
        else -> Icons.Filled.WifiTethering
    }
    val title = statusTitle(state)
    val subtitle = statusSubtitle(state, radio)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(MeshUI.corner)).background(MeshColors.card)
            .border(2.dp, tint, RoundedCornerShape(MeshUI.corner))
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $subtitle" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 15.sp, color = MeshColors.secondary)
        }
        if (onTap != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MeshColors.secondary)
    }
}

fun statusTitle(state: MeshConnectionState): String = when (state) {
    MeshConnectionState.Idle -> "No radio connected"
    MeshConnectionState.Scanning -> "Looking for radios"
    is MeshConnectionState.Connecting -> "Connecting to ${state.name}"
    is MeshConnectionState.Syncing -> "Syncing with ${state.name}"
    is MeshConnectionState.Connected -> "Connected: ${state.name}"
    is MeshConnectionState.Reconnecting -> "Waiting for ${state.name}"
    is MeshConnectionState.NotNearby -> "${state.name} isn't nearby"
    is MeshConnectionState.NotWithMe -> "${state.name} isn't with you today"
    is MeshConnectionState.Failed -> "Connection problem"
}

fun statusSubtitle(state: MeshConnectionState, radio: MeshRadioInfo): String = when (state) {
    MeshConnectionState.Idle -> "Tap to find your Meshtastic or MeshCore radio."
    MeshConnectionState.Scanning -> "Keep the radio close and switched on."
    is MeshConnectionState.Connecting -> "If Android asks for a PIN, read it off the radio's screen."
    is MeshConnectionState.Syncing -> "Downloading the node list and settings."
    is MeshConnectionState.Connected -> listOfNotNull(
        radio.firmware?.displayName, radio.regionName,
        radio.primaryChannelName.ifEmpty { "Primary channel" },
    ).joinToString(" · ")
    is MeshConnectionState.Reconnecting -> "Will reconnect by itself when the radio is in range."
    is MeshConnectionState.NotNearby -> "Reconnects by itself when it's back."
    is MeshConnectionState.NotWithMe -> "Not looking for it until you tap Connect or open the app again."
    is MeshConnectionState.Failed -> state.reason
}

/** Short-name badge for a node, in a stable colour per node. */
@Composable
fun MeshNodeBadge(node: MeshNode, size: Int = 48) {
    val hue = (node.num % 360).toFloat()
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color.hsv(hue, 0.55f, 0.95f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(node.badge, color = Color.Black, fontWeight = FontWeight.Black, fontSize = (size * 0.3).sp, maxLines = 1)
    }
}

/** What to do when Bluetooth cannot be used. */
@Composable
fun MeshBluetoothHelp(availability: MeshBluetoothAvailability) {
    val context = LocalContext.current
    val request = rememberPermissionRequest { Mesh.permissionsChanged() }
    when (availability) {
        MeshBluetoothAvailability.POWERED_OFF -> MeshInfoCard(Icons.Filled.BluetoothDisabled, "Bluetooth is off",
            "Swipe down from the top of the screen and tap Bluetooth. Or open Settings, tap Connected devices, and turn Bluetooth on.",
            MeshColors.warn) {
            MeshBigButton("Open Bluetooth settings", { context.startSafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                tint = MeshColors.warn, filled = false)
        }
        MeshBluetoothAvailability.UNAUTHORIZED -> MeshInfoCard(Icons.Filled.PanTool, "Bluetooth permission is off",
            "Pocket Nomad needs the Nearby devices permission to talk to your radio. It is never used to find your location.",
            MeshColors.danger) {
            MeshBigButton("Allow Bluetooth", { request(MeshPermissions.bluetooth) }, tint = MeshColors.danger)
            MeshBigButton("Open app settings", { context.openAppSettings() }, tint = MeshColors.danger, filled = false)
        }
        MeshBluetoothAvailability.UNSUPPORTED -> MeshInfoCard(Icons.Filled.Warning, "Bluetooth LE not available",
            "This device cannot talk to mesh radios. Mesh messaging needs a phone or tablet with Bluetooth LE.", MeshColors.danger)
        MeshBluetoothAvailability.RESETTING -> MeshInfoCard(Icons.Filled.Refresh, "Bluetooth is restarting",
            "Wait a few seconds. If this does not clear, turn Bluetooth off and on again.", MeshColors.warn)
        MeshBluetoothAvailability.UNKNOWN, MeshBluetoothAvailability.POWERED_ON -> {}
    }
}

/** Runtime permissions the mesh screens ask for. */
object MeshPermissions {
    /** Android 12+: Nearby devices (never for location). Older: location, which Android needs to scan. */
    val bluetooth: Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val location = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    /** Drive mode: GPS, the ongoing notification and (for the service type) Bluetooth. */
    val drive: Array<String> = location +
        (if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()) +
        (if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray())

    fun granted(context: Context, perms: Array<String>): Boolean =
        perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun hasLocation(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

/** Returns a function that asks for permissions and then calls `onResult` (granted map). */
@Composable
fun rememberPermissionRequest(onResult: (Map<String, Boolean>) -> Unit): (Array<String>) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Mesh.permissionsChanged()
        onResult(it)
    }
    return { perms -> launcher.launch(perms) }
}

internal fun Context.startSafely(intent: Intent) {
    try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

internal fun Context.openAppSettings() =
    startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

/** "just now", "5 minutes ago". */
fun meshRelative(millis: Long): String {
    val now = System.currentTimeMillis()
    if (kotlin.math.abs(now - millis) < 60_000) return "just now"
    return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** A rounded card row used for tiles and lists. */
@Composable
fun MeshCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null,
             content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MeshUI.corner)).background(MeshColors.card)
            .then(if (onClick != null || onLongClick != null) Modifier.meshClickable(onClick ?: {}, onLongClick) else Modifier)
    ) { content() }
}

@Composable
fun UnreadBadge(n: Int) {
    if (n <= 0) return
    Text("$n", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp,
        modifier = Modifier.clip(CircleShape).background(MeshColors.accent).padding(horizontal = 10.dp, vertical = 3.dp)
            .semantics { contentDescription = "$n unread" })
}
