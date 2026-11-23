// SdrScreen.kt
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.app.sdr

import org.nomadpocket.i18n.tr

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
    val hello by SdrState.hello.collectAsState()
    val active by SdrState.active.collectAsState()
    val dongle by SdrClient.dongle.collectAsState()
    val dongleCount = (hello?.dongles ?: 1).coerceAtLeast(1)
    val mode = active[dongle.coerceAtMost(dongleCount - 1)]
    fun listening(m: String) = m in active.values
    val simulated by SdrState.simulated.collectAsState()
    val aircraft by SdrState.aircraft.collectAsState()
    val alerts by SdrState.alerts.collectAsState()
    val channels by SdrState.channels.collectAsState()
    val ships by SdrState.ships.collectAsState()
    val sondes by SdrState.sondes.collectAsState()
    val spots by SdrState.spots.collectAsState()
    val pictures by SdrState.images.collectAsState()
    val spectrum by SdrState.spectrum.collectAsState()
    val peak by SdrState.peak.collectAsState()
    val js8 by SdrState.js8.collectAsState()
    val direct by RtlSpectrum.running.collectAsState()
    val directError by RtlSpectrum.error.collectAsState()
    val directAdsb by RtlAdsb.running.collectAsState()
    val adsbMessages by RtlAdsb.messages.collectAsState()
    val adsbError by RtlAdsb.error.collectAsState()
    val directAprs by RtlAprs.running.collectAsState()
    val directAis by RtlAis.running.collectAsState()
    val aisReports by RtlAis.reports.collectAsState()
    val aisError by RtlAis.error.collectAsState()
    val aprsFrames by RtlAprs.frames.collectAsState()
    val aprsError by RtlAprs.error.collectAsState()
    val passes by SdrState.passes.collectAsState()
    var viewing by remember { mutableStateOf<SdrImage?>(null) }
    var host by remember { mutableStateOf(SdrClient.savedHost(context)) }
    var port by remember { mutableStateOf(SdrClient.savedPort(context).toString()) }
    var token by remember { mutableStateOf(SdrClient.savedToken(context)) }
    var bluetooth by remember { mutableStateOf(context.getSharedPreferences("sdr", android.content.Context.MODE_PRIVATE).getBoolean("bluetooth", false)) }
    val bleDevices by SdrClient.bleDevices.collectAsState()
    val askBluetooth = org.nomadpocket.mesh.ui.rememberPermissionRequest { granted -> if (granted.values.all { it }) SdrClient.scanBluetooth(context) }
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

    viewing?.let { img -> SdrPictureDialog(img, host, token) { viewing = null } }
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(tr("SDR box"), style = MaterialTheme.typography.titleLarge)
            Text(when (val l = link) { SdrLink.Idle -> "Not connected"; SdrLink.Connecting -> "Connecting…"; SdrLink.Connected -> "Connected"; is SdrLink.Failed -> l.message },
                color = if (connected) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant)
            if (simulated) Text(tr("Simulated box: the aircraft and packets are invented."), color = Color(0xFFFF9500), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !bluetooth, onClick = { bluetooth = false; context.getSharedPreferences("sdr", android.content.Context.MODE_PRIVATE).edit().putBoolean("bluetooth", false).apply() }, label = { Text(tr("Wi-Fi")) })
                FilterChip(selected = bluetooth, onClick = { bluetooth = true; context.getSharedPreferences("sdr", android.content.Context.MODE_PRIVATE).edit().putBoolean("bluetooth", true).apply() }, label = { Text(tr("Bluetooth")) })
            }
            if (bluetooth) {
                if (!connected) OutlinedButton(onClick = {
                    if (org.nomadpocket.mesh.ui.MeshPermissions.granted(context, org.nomadpocket.mesh.ui.MeshPermissions.bluetooth)) SdrClient.scanBluetooth(context) else askBluetooth(org.nomadpocket.mesh.ui.MeshPermissions.bluetooth)
                }) { Text(if (bleDevices.isEmpty()) tr("Look for SDR boxes") else tr("Scan again")) }
                bleDevices.forEach { d -> TextButton(onClick = { SdrClient.connectBluetooth(d, token) }) { Text("${d.name}  (${d.rssi} dBm)") } }
            } else {
            OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Box address (for example sdrbox.local)")) })
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Port")) })
            }
            OutlinedTextField(token, { token = it; SdrClient.saveToken(context, it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Pairing token (printed by the installer)")) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected || link is SdrLink.Connecting) OutlinedButton(onClick = { SdrClient.disconnect() }) { Text(tr("Disconnect")) }
                else if (!bluetooth) Button(enabled = host.isNotBlank(), onClick = { SdrClient.save(context, host, port.toIntOrNull() ?: 8731); SdrClient.connect(host, port.toIntOrNull() ?: 8731, token) }) { Text(tr("Connect")) }
            }
            hello?.takeIf { connected }?.let { Text("Receiver: ${it.dongle.ifEmpty { it.model }} on ${it.model} · heard ${SdrState.decoded}", style = MaterialTheme.typography.bodySmall) }
            Text(tr("An SDR box is a small Raspberry Pi with an RTL-SDR USB dongle that listens for aircraft, APRS and weather radio and sends the results here. It only listens; it never transmits."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text(tr("A dongle plugged into this phone"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(tr("Spectrum, aircraft, ships, APRS and weather radio alerts. Install the free, open-source \"Rtl-sdr driver\" app (F-Droid), plug in the dongle with a USB-OTG adapter, start the driver, then tap Start. Balloons, HF and satellite pictures need an SDR box."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!direct && !directAdsb && !directAprs && !directAis) {
                    OutlinedButton(onClick = { RtlSpectrum.start(144.0, 148.0) }) { Text(tr("Start spectrum")) }
                    OutlinedButton(onClick = { RtlAdsb.start() }) { Text(tr("Start aircraft")) }
                    OutlinedButton(onClick = { RtlAprs.start(162.55) { } }) { Text(tr("Start weather radio")) }
                    OutlinedButton(onClick = { RtlAis.start() }) { Text(tr("Start ships (AIS)")) }
                    OutlinedButton(onClick = { RtlAprs.start { f -> android.os.Handler(android.os.Looper.getMainLooper()).post { if (org.nomadpocket.ham.Ham.isInitialized) org.nomadpocket.ham.Ham.service.receive(f) } } }) { Text(tr("Start APRS")) }
                } else OutlinedButton(onClick = { RtlSpectrum.stop(); RtlAdsb.stop(); RtlAprs.stop(); RtlAis.stop() }) { Text(tr("Stop"), color = MaterialTheme.colorScheme.error) }
            }
            if (directAdsb) Text("${tr("Aircraft messages decoded")}: $adsbMessages", style = MaterialTheme.typography.bodySmall)
            if (directAis) Text("${tr("Ship reports decoded")}: $aisReports. ${tr("They appear under Ships.")}", style = MaterialTheme.typography.bodySmall)
            if (directAprs) Text("${tr("APRS packets decoded")}: $aprsFrames. ${tr("They appear in the Ham tab.")}", style = MaterialTheme.typography.bodySmall)
            (directError ?: adsbError ?: aprsError ?: aisError)?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9500)) }
        }
        if (connected) {
            item {
                Text(tr("Listen to"), style = MaterialTheme.typography.titleMedium)
                if (dongleCount > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0 until dongleCount).forEach { i ->
                        FilterChip(selected = dongle == i, onClick = { SdrClient.selectDongle(i) }, label = { Text("${tr("Dongle")} ${i + 1}" + (active[i]?.let { ": $it" } ?: "")) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("adsb" to "Aircraft", "aprs" to "APRS", "nwr" to "Weather radio", "ais" to "Ships (AIS)", "sonde" to "Weather balloons", "ft8" to "HF stations (FT8)", "weather" to "Weather satellite pictures", "spectrum" to "Spectrum (signal strength)", "js8" to "JS8 messages (listen)")
                        .filter { (id, _) -> hello?.modes?.contains(id) ?: (id in listOf("adsb", "aprs", "nwr")) }.forEach { (id, label) ->
                        FilterChip(selected = mode == id, onClick = { SdrClient.start(id) }, label = { Text(label) })
                    }
                }
                if (mode != null) TextButton(onClick = { SdrClient.stop() }) { Text(tr("Stop listening"), color = MaterialTheme.colorScheme.error) }
                Text(tr("Each dongle can listen to one thing at a time (plug in more to listen to more). Aircraft: 1090 MHz. APRS: 144.390 MHz in North America. Weather radio: 162.400 to 162.550 MHz (US and Canada)."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Aircraft (${planes.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (planes.isEmpty()) Text(if (listening("adsb")) "Listening… aircraft appear within a minute or two." else "Choose Aircraft above.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                if (shipList.isNotEmpty() || listening("ais")) {
                    Text("Ships (${shipList.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    if (shipList.isEmpty()) Text(tr("Listening… ships appear within a few minutes."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    shipList.take(40).forEach { s ->
                        Text(s.name.ifEmpty { "MMSI ${s.mmsi}" }, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(s.kind.ifEmpty { null }, s.speedKnots?.let { "%.1f kt".format(it) }, s.courseDegrees?.let { "heading %.0f°".format(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (sondeList.isNotEmpty() || listening("sonde")) {
                    Text("Weather balloons (${sondeList.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    if (sondeList.isEmpty()) Text(tr("Listening… balloons are launched twice a day around 00:00 and 12:00 UTC."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sondeList.forEach { s ->
                        Text("${s.id} · ${s.model}", fontWeight = FontWeight.SemiBold)
                        Text("%,d m · %s %.0f m/s · %.3f MHz".format(s.altitudeMetres, if (s.climbMS >= 0) "rising" else "falling", Math.abs(s.climbMS), s.mhz), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        org.nomadpocket.app.map.PlaceActions(s.id, s.latitude, s.longitude)
                    }
                }
                if (spotList.isNotEmpty() || listening("ft8") || listening("wspr")) {
                    Text("Heard on HF (${spotList.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    spotList.take(40).forEach { s -> Text("${s.call}  ${s.grid}   ${s.snr} dB · %.3f MHz".format(s.mhz), style = MaterialTheme.typography.bodyMedium) }
                }
                if (spectrum != null || listening("spectrum") || direct) {
                    Text(tr("Spectrum"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    BandMenu { lo, hi -> if (direct) RtlSpectrum.start(lo, hi) else SdrClient.sendSpectrumRange(lo, hi) }
                    spectrum?.let { SdrSpectrumChart(it, peak) } ?: Text(tr("Listening… the first sweep takes a few seconds."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr("White line: now. Orange: strongest since you picked the band. Drag across the chart to read a frequency. Higher is stronger; it shows where signals are, not what they say."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (js8.isNotEmpty() || listening("js8")) {
                    Text("${tr("JS8 messages")} (${js8.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    js8.take(60).forEach { m ->
                        Text((if (m.to.isEmpty()) m.from else "${m.from} → ${m.to}") + "   ${m.snr} dB · %.4f MHz".format(m.mhz), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        if (m.text.isNotEmpty()) Text(m.text, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(tr("Receive only. Sending JS8 needs an amateur licence and an audio link to a radio, which this app does not have. The box needs the JS8 decoder installed."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pictures.isNotEmpty() || passes.isNotEmpty() || listening("weather")) {
                    Text("${tr("Weather satellite pictures")} (${pictures.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    OutlinedButton(enabled = here != null, onClick = { here?.let { SdrClient.sendArea(it.latitude, it.longitude) } }) { Text(tr("Tell the box my area")) }
                    Text(tr("The box needs your area (rounded to about 7 miles, kept only on the box) to know when satellites pass. It also needs an orbit file and a V-dipole antenna."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (bluetooth) Text(tr("Pictures download over Wi-Fi: connect to the box's network to see them."), style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9500))
                    passes.filter { it.losMillis > now }.take(3).forEach { p ->
                        Text("${p.sat}: ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(p.aosMillis))}, peak ${p.elevation}°", style = MaterialTheme.typography.bodySmall)
                    }
                    pictures.forEach { i ->
                        TextButton(onClick = { viewing = i }) { Text("${i.sat}  ·  ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(i.heardAtMillis))}  ·  ${i.kb} KB") }
                    }
                }
                Text(tr("Weather radio"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                channels.sortedBy { it.mhz }.forEach { Text("%.3f MHz   %.0f dB".format(it.mhz, it.snr), style = MaterialTheme.typography.bodySmall) }
                if (alerts.isEmpty()) Text(if (listening("nwr")) "Listening for alerts…" else "No alerts heard.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                alerts.take(10).forEach { Text(it.text, fontWeight = FontWeight.SemiBold) }
            }
        }
        item {
            Text(tr("How to set it up"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text("1. Plug an RTL-SDR dongle and its antenna into a Raspberry Pi Zero 2 W (a USB-OTG adapter is needed).\n" +
                "2. Install the sdrbox software on the Pi (see the sdrbox project) and put the Pi on your phone's Wi-Fi or its own hotspot.\n" +
                "3. Type the Pi's address and the pairing token the installer printed, and tap Connect. Aircraft show on the Map too (Layers > Aircraft).\n" +
                "Tips: an antenna by a window or outside hears far more. Your location is never sent to the box.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BandMenu(onPick: (Double, Double) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(tr("Band")) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("2 m amateur" to (144.0 to 148.0), "Aircraft" to (118.0 to 137.0), "FM broadcast" to (88.0 to 108.0), "Weather radio" to (162.0 to 163.0),
                "Marine VHF" to (156.0 to 162.5), "70 cm amateur" to (420.0 to 450.0)).forEach { (name, r) ->
                DropdownMenuItem(text = { Text(tr(name)) }, onClick = { onPick(r.first, r.second); open = false })
            }
        }
    }
}
