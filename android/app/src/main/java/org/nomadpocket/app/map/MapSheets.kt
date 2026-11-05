package org.nomadpocket.app.map

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.nomadpocket.app.location.rememberLocation
import org.nomadpocket.core.CoordinateFormat
import org.nomadpocket.app.ui.Brand
import org.nomadpocket.app.ui.Footnote
import org.nomadpocket.app.ui.LabeledRow
import org.nomadpocket.mesh.Firmware
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            content()
        }
    }
}

@Composable
private fun SheetTitle(title: String, onDone: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (onDone != null) TextButton(onClick = onDone) { Text("Done") }
    }
}

@Composable
private fun Header(text: String) {
    Text(text.uppercase(), Modifier.padding(top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun PinEditorSheet(point: LatLng, onDismiss: () -> Unit, onSave: (String, MapPin.Kind) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(MapPin.Kind.RALLY) }
    Sheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Text("Drop a pin", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(onClick = { onSave(name.trim(), kind) }) { Text("Save", fontWeight = FontWeight.Bold) }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        Header("Type")
        MapPin.Kind.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(k.color, CircleShape)) })
                }
            }
        }
        Text(CoordinateFormat.decimal(point.latitude, point.longitude), Modifier.padding(top = 12.dp),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PlacesSheet(
    pins: List<MapPin>,
    regions: List<OfflineRegions.Region>,
    onDismiss: () -> Unit,
    onPin: (MapPin) -> Unit,
    onDelete: (MapPin) -> Unit,
    onRegion: (OfflineRegions.Region) -> Unit,
    onWorld: () -> Unit,
) {
    Sheet(onDismiss) {
        SheetTitle("Places", onDismiss)
        Header("Saved places")
        if (pins.isEmpty()) {
            Text("Press and hold on the map to drop a pin for water, shelter or a rally point.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pins.forEach { pin ->
            Row(Modifier.fillMaxWidth().clickable { onPin(pin) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(pin.kind.color, CircleShape))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(pin.name)
                    Text(CoordinateFormat.decimal(pin.latitude, pin.longitude), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onDelete(pin) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${pin.name}") }
            }
        }
        Header("Map regions")
        regions.forEach { r ->
            Row(Modifier.fillMaxWidth().clickable { onRegion(r) }.padding(vertical = 10.dp)) {
                Text(r.name, Modifier.weight(1f))
                Text(TileMath.formatBytes(r.bytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("World", Modifier.fillMaxWidth().clickable(onClick = onWorld).padding(vertical = 10.dp), color = MaterialTheme.colorScheme.primary)
    }
}

/** Download (and delete) known radios from the community maps. A port of KnownRadiosSheet.swift. */
@Composable
fun KnownRadiosSheet(mapCenter: LatLng?, onDismiss: () -> Unit) {
    val store by KnownRadioStore.state.collectAsState()
    val location = rememberLocation()
    val scope = rememberCoroutineScope()
    var radiusKm by remember { mutableIntStateOf(200) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Where to keep radios around: the live GPS fix, else the middle of the map.
    val center: Pair<Double, Double>? = location?.let { it.latitude to it.longitude } ?: mapCenter?.let { it.latitude to it.longitude }
    val usingGPS = location != null
    val nf = NumberFormat.getIntegerInstance()

    Sheet(onDismiss) {
        SheetTitle("Known radios", onDismiss)
        Header("On this phone")
        val updated = store.updated
        if (updated != null) {
            LabeledRow("Meshtastic", nf.format(store.meshtasticCount))
            LabeledRow("MeshCore", nf.format(store.meshcoreCount))
            LabeledRow("Repeaters and routers", nf.format(store.radios.count { it.role == KnownRadio.Role.REPEATER }))
            LabeledRow("Downloaded", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(updated)))
        } else {
            Text("None downloaded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Footnote("Shown on the map under Layers › Known radios. They stay on this phone and work offline. Positions are what each radio last reported, often rounded for privacy, and may be out of date.",
            Modifier.padding(top = 6.dp))

        Header("Download")
        Text("Area", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KnownRadioStore.radiusChoices.forEach { km ->
                FilterChip(selected = radiusKm == km, onClick = { radiusKm = km }, label = { Text("Within $km km") })
            }
        }
        Button(
            onClick = {
                val c = center ?: return@Button
                scope.launch { KnownRadioStore.download(c.first, c.second, radiusKm, null) }
            },
            enabled = center != null && !store.downloading,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            if (store.downloading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Downloading…")
            } else {
                Icon(Icons.Filled.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(if (store.updated == null) "Download known radios" else "Update known radios")
            }
        }
        val error = store.lastError
        if (error != null) {
            Text(error, color = Brand.danger, style = MaterialTheme.typography.bodySmall)
        } else if (updated != null && !store.downloading && System.currentTimeMillis() - updated.toEpochMilli() < 120_000) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.CheckCircle, null, tint = Brand.green, modifier = Modifier.size(16.dp))
                Text("Got ${store.radios.size} radios. They're on the map now.", color = Brand.green, style = MaterialTheme.typography.bodySmall)
            }
        }
        Footnote(
            if (center == null) "Waiting for GPS. Radios are kept around where you are."
            else (if (usingGPS) "Keeps radios around where you are. " else "No GPS fix yet, so this keeps radios around the middle of the map: move the map to your area first. ") +
                "Gets the public lists from meshmap.net (Meshtastic radios heard by the official MQTT server) and map.meshcore.io (MeshCore radios that uploaded their adverts), about 5 to 20 MB, and keeps only radios near you. Needs internet once. Your location isn't sent. Radios with ⛔, 🛑 or 🚫 in their name are left out.",
            Modifier.padding(top = 6.dp),
        )
        if (store.updated != null) {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 12.dp)) {
                Text("Delete known radios", color = Brand.danger)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${store.radios.size} known radios from this phone?") },
            confirmButton = { TextButton(onClick = { KnownRadioStore.deleteAll(); confirmDelete = false }) { Text("Delete", color = Brand.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Save the area on screen for offline use, with a size estimate, and delete saved areas. */
@Composable
fun OfflineMapsSheet(visibleBounds: () -> LatLngBounds?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val regions by OfflineRegions.regions.collectAsState()
    val error by OfflineRegions.error.collectAsState()
    var name by remember { mutableStateOf("Map region ${regions.size + 1}") }
    var confirmDelete by remember { mutableStateOf<OfflineRegions.Region?>(null) }
    val bounds = remember { visibleBounds() }
    val tiles = bounds?.let {
        TileMath.tileCount(it.latitudeSouth, it.longitudeWest, it.latitudeNorth, it.longitudeEast, 0, OfflineRegions.MAX_ZOOM)
    }

    Sheet(onDismiss) {
        SheetTitle("Offline maps", onDismiss)
        Header("Save this area")
        if (bounds == null || tiles == null) {
            Text("Wait for the map to load, then try again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val tooBig = tiles > OfflineRegions.MAX_TILES
            LabeledRow("Area", String.format(java.util.Locale.US, "%.2f°, %.2f° to %.2f°, %.2f°",
                max(bounds.latitudeSouth, -90.0), max(bounds.longitudeWest, -180.0), min(bounds.latitudeNorth, 90.0), min(bounds.longitudeEast, 180.0)))
            LabeledRow("Detail", "Up to street level (zoom ${OfflineRegions.MAX_ZOOM})")
            LabeledRow("Size", "About ${TileMath.formatBytes(TileMath.estimateBytes(tiles))} (${NumberFormat.getIntegerInstance().format(tiles)} tiles)")
            if (tooBig) {
                Text("This area is too big. Zoom in, then try again.", color = Brand.danger, style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Name") }, singleLine = true)
                Button(onClick = { OfflineRegions.download(context, name.ifBlank { "Map region" }, bounds) }, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(Icons.Filled.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download this area")
                }
            }
            Footnote("Saves the map you see now, from the whole world down to street level, from OpenFreeMap (OpenStreetMap data). Needs internet once. Size is an estimate.",
                Modifier.padding(top = 6.dp))
        }
        error?.let { Text(it, Modifier.padding(top = 6.dp), color = Brand.danger, style = MaterialTheme.typography.bodySmall) }

        Header("On this phone")
        if (regions.isEmpty()) Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        regions.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name)
                        Text(
                            if (r.complete) TileMath.formatBytes(r.bytes)
                            else "Downloading… ${r.percent}% (${TileMath.formatBytes(r.bytes)})",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { confirmDelete = r }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${r.name}") }
                }
                if (!r.complete) LinearProgressIndicator(progress = { r.percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider()
        }
    }
    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${r.name} from this phone?") },
            confirmButton = { TextButton(onClick = { OfflineRegions.delete(r.id); confirmDelete = null }) { Text("Delete", color = Brand.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** What a tapped pin, mesh radio or known radio is. */
@Composable
fun TapDetailSheet(tap: MapTap, onDismiss: () -> Unit) {
    Sheet(onDismiss) {
        when (tap) {
            is MapTap.Pin -> {
                val p = tap.pin
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(14.dp).background(p.kind.color, CircleShape))
                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text("${p.kind.label} · ${CoordinateFormat.decimal(p.latitude, p.longitude)}", Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is MapTap.Node -> {
                val n = tap.node
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(14.dp).background(RadioColors.color(n.firmware), CircleShape))
                    Text(n.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                val heard = n.lastHeardMillis?.let { "heard " + DateUtils.getRelativeTimeSpanString(it).toString().lowercase() } ?: "not heard yet"
                Text("${if (n.firmware == Firmware.MESHCORE) "MeshCore" else "Meshtastic"} · $heard", Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(CoordinateFormat.decimal(n.latitude, n.longitude), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is MapTap.Radio -> KnownRadioDetail(tap.radio)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun KnownRadioDetail(radio: KnownRadio) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(14.dp).background(RadioColors.color(radio.firmware), CircleShape))
            Text(radio.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
        }
        LabeledRow("System", if (radio.firmware == Firmware.MESHCORE) "MeshCore" else "Meshtastic")
        LabeledRow("Type", radio.roleLabel)
        radio.lastSeen?.let { LabeledRow("Last reported", DateUtils.getRelativeTimeSpanString(it.toEpochMilli()).toString()) }
        LabeledRow("Position", CoordinateFormat.decimal(radio.latitude, radio.longitude))
        Footnote("From ${if (radio.firmware == Firmware.MESHCORE) "map.meshcore.io" else "meshmap.net"}. Your radio may not hear it.")
    }
}
