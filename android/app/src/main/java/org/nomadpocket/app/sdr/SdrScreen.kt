// SdrScreen.kt
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.app.sdr

import org.nomadpocket.app.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nomadpocket.app.conditions.ConditionsFormat
import org.nomadpocket.app.map.KnownRadioParser
import org.nomadpocket.app.map.ShapeMath

/** The SDR side of the Radio tab: connect to an SDR box, pick what to listen to, see what it hears. */
@Composable
fun SdrScreen(modifier: Modifier = Modifier, autoConnect: Boolean = false) {
    val context = LocalContext.current
    val link by SdrClient.link.collectAsState()
    val mode by SdrClient.mode.collectAsState()
    val hello by SdrState.hello.collectAsState()
    val simulated by SdrState.simulated.collectAsState()
    val aircraft by SdrState.aircraft.collectAsState()
    val alerts by SdrState.alerts.collectAsState()
    val channels by SdrState.channels.collectAsState()
    val ships by SdrState.ships.collectAsState()
    val sondes by SdrState.sondes.collectAsState()
    val spots by SdrState.spots.collectAsState()
    var host by remember { mutableStateOf(SdrClient.savedHost(context)) }
    var port by remember { mutableStateOf(SdrClient.savedPort(context).toString()) }
    var token by remember { mutableStateOf(SdrClient.savedToken(context)) }
    val here = org.nomadpocket.app.location.rememberLocation()
    val metric = remember { ConditionsFormat.current().metric }
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5000); tick = System.currentTimeMillis() } }
    LaunchedEffect(autoConnect) {
        if (autoConnect && link !is SdrLink.Connected) {
            if (host.isEmpty()) host = listOf(10, 0, 2, 2).joinToString(".")   // the Android emulator's address for the Mac (debug shortcut only)
            SdrClient.connect(host, port.toIntOrNull() ?: 8731, token)
            kotlinx.coroutines.delay(1500); SdrClient.start("adsb")
        }
    }
    val connected = link is SdrLink.Connected
    val planes = remember(aircraft, tick, here) {
        SdrState.visibleAircraft(System.currentTimeMillis(), here?.let { it.latitude to it.longitude })
    }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(tr("SDR box"), style = MaterialTheme.typography.titleLarge)
            Text(when (val l = link) { SdrLink.Idle -> "Not connected"; SdrLink.Connecting -> "Connecting…"; SdrLink.Connected -> "Connected"; is SdrLink.Failed -> l.message },
                color = if (connected) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant)
            if (simulated) Text("Simulated box: the aircraft and packets are invented.", color = Color(0xFFFF9500), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Box address (for example sdrbox.local)") })
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Port") })
            OutlinedTextField(token, { token = it; SdrClient.saveToken(context, it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Pairing token (printed by the installer)") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected || link is SdrLink.Connecting) OutlinedButton(onClick = { SdrClient.disconnect() }) { Text(tr("Disconnect")) }
                else Button(enabled = host.isNotBlank(), onClick = { SdrClient.save(context, host, port.toIntOrNull() ?: 8731); SdrClient.connect(host, port.toIntOrNull() ?: 8731, token) }) { Text(tr("Connect")) }
            }
            hello?.takeIf { connected }?.let { Text("Receiver: ${it.dongle.ifEmpty { it.model }} on ${it.model} · heard ${SdrState.decoded}", style = MaterialTheme.typography.bodySmall) }
            Text("An SDR box is a small Raspberry Pi with an RTL-SDR USB dongle that listens for aircraft, APRS and weather radio and sends the results here. It only listens; it never transmits.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (connected) {
            item {
                Text(tr("Listen to"), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("adsb" to "Aircraft", "aprs" to "APRS", "nwr" to "Weather radio", "ais" to "Ships (AIS)", "sonde" to "Weather balloons", "ft8" to "HF stations (FT8)")
                        .filter { (id, _) -> hello?.modes?.contains(id) ?: (id in listOf("adsb", "aprs", "nwr")) }.forEach { (id, label) ->
                        FilterChip(selected = mode == id, onClick = { SdrClient.start(id) }, label = { Text(label) })
                    }
                }
                if (mode != null) TextButton(onClick = { SdrClient.stop() }) { Text(tr("Stop listening"), color = MaterialTheme.colorScheme.error) }
                Text("One dongle can listen to one thing at a time. Aircraft: 1090 MHz. APRS: 144.390 MHz in North America. Weather radio: 162.400 to 162.550 MHz (US and Canada).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Aircraft (${planes.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (planes.isEmpty()) Text(if (mode == "adsb") "Listening… aircraft appear within a minute or two." else "Choose Aircraft above.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(planes.take(40), key = { it.icao }) { a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.callsign.ifEmpty { a.icao }, fontWeight = FontWeight.SemiBold)
                        Text("%,d ft · %d kt · heading %d°".format(a.altitudeFeet, a.groundSpeedKnots, a.trackDegrees), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    here?.let { Text(ShapeMath.distanceText(KnownRadioParser.distance(it.latitude, it.longitude, a.latitude, a.longitude), metric), style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                val now = System.currentTimeMillis()
                val shipList = SdrState.visibleShips(now); val sondeList = SdrState.visibleSondes(now); val spotList = SdrState.visibleSpots(now)
                if (shipList.isNotEmpty() || mode == "ais") {
                    Text("Ships (${shipList.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    if (shipList.isEmpty()) Text("Listening… ships appear within a few minutes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    shipList.take(40).forEach { s ->
                        Text(s.name.ifEmpty { "MMSI ${s.mmsi}" }, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(s.kind.ifEmpty { null }, s.speedKnots?.let { "%.1f kt".format(it) }, s.courseDegrees?.let { "heading %.0f°".format(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (sondeList.isNotEmpty() || mode == "sonde") {
                    Text("Weather balloons (${sondeList.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    if (sondeList.isEmpty()) Text("Listening… balloons are launched twice a day around 00:00 and 12:00 UTC.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sondeList.forEach { s ->
                        Text("${s.id} · ${s.model}", fontWeight = FontWeight.SemiBold)
                        Text("%,d m · %s %.0f m/s · %.3f MHz".format(s.altitudeMetres, if (s.climbMS >= 0) "rising" else "falling", Math.abs(s.climbMS), s.mhz), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        org.nomadpocket.app.map.PlaceActions(s.id, s.latitude, s.longitude)
                    }
                }
                if (spotList.isNotEmpty() || mode == "ft8" || mode == "wspr") {
                    Text("Heard on HF (${spotList.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    spotList.take(40).forEach { s -> Text("${s.call}  ${s.grid}   ${s.snr} dB · %.3f MHz".format(s.mhz), style = MaterialTheme.typography.bodyMedium) }
                }
                Text(tr("Weather radio"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                channels.sortedBy { it.mhz }.forEach { Text("%.3f MHz   %.0f dB".format(it.mhz, it.snr), style = MaterialTheme.typography.bodySmall) }
                if (alerts.isEmpty()) Text(if (mode == "nwr") "Listening for alerts…" else "No alerts heard.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                alerts.take(10).forEach { Text(it.text, fontWeight = FontWeight.SemiBold) }
            }
        }
        item {
            Text("How to set it up", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text("1. Plug an RTL-SDR dongle and its antenna into a Raspberry Pi Zero 2 W (a USB-OTG adapter is needed).\n" +
                "2. Install the sdrbox software on the Pi (see the sdrbox project) and put the Pi on your phone's Wi-Fi or its own hotspot.\n" +
                "3. Type the Pi's address and the pairing token the installer printed, and tap Connect. Aircraft show on the Map too (Layers > Aircraft).\n" +
                "Tips: an antenna by a window or outside hears far more. Your location is never sent to the box.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
}
