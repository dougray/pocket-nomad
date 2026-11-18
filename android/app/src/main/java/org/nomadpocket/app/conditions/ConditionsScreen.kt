// ConditionsScreen.kt
// Pocket Nomad - Conditions module
// SPDX-License-Identifier: Apache-2.0
//
// The Conditions screen (alerts, forecast, earthquakes, space weather, weather
// radio) and the card that opens it from the top of the Survive tab.

@file:OptIn(ExperimentalMaterial3Api::class)

package org.nomadpocket.app.conditions

import org.nomadpocket.app.i18n.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.nomadpocket.app.ui.cardColor
import org.nomadpocket.mesh.ui.MeshPermissions
import org.nomadpocket.mesh.ui.rememberPermissionRequest
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

private val warn = Color(0xFFFFD43B)
private val danger = Color(0xFFE5484D)
private val ok = Color(0xFF46C46E)
private val accent = Color(0xFFFC9712)

private fun codeLabel(code: Int) = ConditionsParsers.describe(code)

@Composable
fun ConditionsCard(onClick: () -> Unit) {
    val s by ConditionsStore.state.collectAsState()
    val fmt = remember { ConditionsFormat.current() }
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Conditions"), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            s.oldestUpdate?.let { Text(ConditionsParsers.age(it, now), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (!s.hasAnything) {
            Text(tr("Weather alerts, forecast, earthquakes and space weather. Update once, read it with no signal."), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val active = ConditionsStore.activeAlerts(s)
            if (active.isNotEmpty()) {
                Text(active[0].event + if (active.size > 1) " (+${active.size - 1})" else "", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = if (active[0].rank <= 1) danger else warn)
            } else if (s.alerts != null) Text("No active weather alerts", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.forecast?.value?.let { f ->
                val next = ConditionsParsers.upcoming(f, now, 24)
                if (next.isNotEmpty()) {
                    Text("Next 24 h: ${fmt.temperature(next.minOf { it.temperatureC })} to ${fmt.temperature(next.maxOf { it.temperatureC })}, rain ${fmt.rain(next.sumOf { it.precipitationMm })}, gusts ${fmt.speed(next.maxOf { it.gustKmh })}", fontSize = 14.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ConditionsStore.nearbyQuakes(s).firstOrNull { !it.significant }?.let {
                    Text("M${"%.1f".format(it.magnitude)} quake ${ConditionsParsers.age(it.timeMillis, now)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                s.space?.value?.let { Text("Kp ${"%.0f".format(it.kpNow)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun ConditionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    ConditionsStore.init(context)
    BackHandler(onBack = onBack)
    val s by ConditionsStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    val fmt = remember { ConditionsFormat.current() }
    var menu by remember { mutableStateOf(false) }
    val askLocation = rememberPermissionRequest { scope.launch { ConditionsStore.refresh(force = true) } }
    LaunchedEffect(Unit) { ConditionsStore.refreshIfStale() }
    val now = System.currentTimeMillis()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Conditions"), fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (ConditionsStore.refreshOnOpen) "✓ Update when I open the app" else "Update when I open the app") },
                        onClick = { ConditionsStore.refreshOnOpen = !ConditionsStore.refreshOnOpen; menu = false })
                    DropdownMenuItem(text = { Text("Earthquake radius: ${fmt.radius(ConditionsStore.quakeRadiusKm)}") },
                        onClick = { val all = listOf(100, 250, 500, 1000, 2500); ConditionsStore.quakeRadiusKm = all[(all.indexOf(ConditionsStore.quakeRadiusKm) + 1) % all.size] })
                    DropdownMenuItem(text = { Text("Smallest earthquake: M${"%.1f".format(ConditionsStore.quakeMinMagnitude)}") },
                        onClick = { val all = listOf(2.5, 3.0, 4.0, 5.0); ConditionsStore.quakeMinMagnitude = all[(all.indexOf(ConditionsStore.quakeMinMagnitude).coerceAtLeast(0) + 1) % all.size] })
                    DropdownMenuItem(text = { Text("Delete saved conditions", color = danger) }, onClick = { ConditionsStore.deleteAll(); menu = false })
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Update
            Box2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (s.updating) "Updating…" else (s.oldestUpdate?.let { "Updated ${ConditionsParsers.age(it, now)}" } ?: "Not updated yet"), fontWeight = FontWeight.Bold)
                        Text("Needs a connection. After that it reads with no signal.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { scope.launch { ConditionsStore.refresh(force = true) } }, enabled = !s.updating) {
                        if (s.updating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(tr("Update"))
                    }
                }
                if (s.needsLocation) {
                    TextButton(onClick = { askLocation(MeshPermissions.location) }) { Text("Allow location for alerts and a forecast where you are") }
                }
                s.errors.forEach { (k, v) -> Text("$k: $v", fontSize = 12.sp, color = warn) }
            }

            val received by org.nomadpocket.app.sdr.SdrState.alerts.collectAsState()
            val receivedSim by org.nomadpocket.app.sdr.SdrState.simulated.collectAsState()
            if (received.isNotEmpty()) {
                Header(if (receivedSim) "Weather radio (simulated)" else "Weather radio (received)")
                Box2 {
                    received.take(5).forEach { a -> Text(a.text, fontWeight = FontWeight.SemiBold); Muted("%.3f MHz".format(a.mhz)) }
                    Muted("Heard directly by your SDR box. Check the official source when you can.")
                }
            }
            Header("Weather alerts")
            Box2 {
                val active = ConditionsStore.activeAlerts(s)
                when {
                    s.alertsUnavailableHere -> Muted("The National Weather Service covers the US and its territories. Alerts aren't available for this location yet.")
                    s.alerts == null -> Muted("Update to see active watches and warnings.")
                    else -> {
                        if (active.isEmpty()) Text("No active alerts for your area", color = ok, fontWeight = FontWeight.SemiBold)
                        active.forEach { AlertRow(it, false) }
                        val ended = ConditionsStore.expiredAlerts(s)
                        if (ended.isNotEmpty()) {
                            Muted("Ended (${ended.size})")
                            ended.forEach { AlertRow(it, true) }
                        }
                        Muted("As of ${ConditionsParsers.age(s.alerts!!.fetchedAtMillis, now)}. Alerts can change: update when you can.")
                    }
                }
            }

            Header("Forecast")
            Box2 {
                val snap = s.forecast
                if (snap == null) Muted("Update to see wind and rain for the next seven days.") else {
                    val next = ConditionsParsers.upcoming(snap.value, now, 24)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        next.forEach { h ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(h.timeMillis)).replace(":00", ""), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(fmt.temperature(h.temperatureC), fontWeight = FontWeight.SemiBold)
                                Text(h.precipitationChance?.let { "$it%" } ?: "", fontSize = 11.sp, color = Color(0xFF5AA9FF))
                                Text("${ConditionsParsers.compass(h.windDirection)} ${if (fmt.metric) h.windKmh.toInt() else (h.windKmh * 0.621371).toInt()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider()
                    ConditionsParsers.days(snap.value, TimeZone.getDefault()).forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(java.text.SimpleDateFormat("EEEE", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(d.dayStartMillis)), Modifier.width(100.dp))
                            Text(codeLabel(d.code), Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${fmt.temperature(d.highC)} / ${fmt.temperature(d.lowC)}", fontSize = 14.sp)
                                Text((if (d.rainMm >= 0.1) "${fmt.rain(d.rainMm)} · " else "") + "gusts ${fmt.speed(d.maxGustKmh)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Muted("For ${"%.1f, %.1f".format(snap.value.latitude, snap.value.longitude)} (rounded), ${ConditionsParsers.age(snap.fetchedAtMillis, now)}. Forecast by Open-Meteo.")
                }
            }

            Header("Earthquakes")
            Box2 {
                val snap = s.quakes
                if (snap == null) Muted("Update to see recent earthquakes near you.") else {
                    val list = ConditionsStore.nearbyQuakes(s)
                    if (list.isEmpty()) Muted("None of M${"%.1f".format(ConditionsStore.quakeMinMagnitude)} or more within ${fmt.radius(ConditionsStore.quakeRadiusKm)} in the past week.")
                    list.take(25).forEach { q ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("%.1f".format(q.magnitude), Modifier.width(52.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                                color = if (q.magnitude >= 5) danger else if (q.magnitude >= 4) warn else Color.Unspecified)
                            Column {
                                Text(q.place, fontSize = 14.sp)
                                val parts = ArrayList<String>()
                                parts.add(ConditionsParsers.age(q.timeMillis, now))
                                s.center?.let { parts.add(fmt.distance(ConditionsParsers.distanceKm(it.first, it.second, q.latitude, q.longitude)) + " away") }
                                parts.add("depth ${fmt.distance(q.depthKm)}")
                                if (q.tsunami) parts.add("tsunami message")
                                if (q.significant) parts.add("significant")
                                Text(parts.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Muted("Past 7 days, plus significant quakes anywhere in the past month. ${ConditionsParsers.age(snap.fetchedAtMillis, now)}. Shown on the map under Layers.")
                }
            }

            Header("Space weather")
            Box2 {
                val snap = s.space
                if (snap == null) Muted("Update to see how solar activity affects HF radio and GPS.") else {
                    val w = snap.value
                    Row(Modifier.fillMaxWidth().height(68.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                        w.kp.takeLast(24).forEach { p ->
                            val c = if (p.kp >= 5) danger else if (p.kp >= 4) warn else accent
                            Column(Modifier.weight(1f).height((p.kp * 7).coerceAtLeast(4.0).dp).background(c.copy(alpha = if (p.predicted) 0.45f else 1f), RoundedCornerShape(2.dp))) {}
                        }
                    }
                    Muted("Kp ${"%.1f".format(w.kpNow)} now (0 calm, 9 severe). Lighter bars are forecast.")
                    Text(ConditionsParsers.hfNote(w), fontSize = 14.sp)
                    Text(ConditionsParsers.gpsNote(w), fontSize = 14.sp)
                    ConditionsParsers.auroraNote(w)?.let { Text(it, fontSize = 14.sp) }
                    Muted("NOAA scales now: radio R${w.radio}, radiation S${w.radiation}, geomagnetic G${w.geomagnetic}. ${ConditionsParsers.age(snap.fetchedAtMillis, now)}.")
                }
            }

            Header("NOAA Weather Radio")
            Box2 {
                val near = ConditionsStore.nearestRadio(3, s)
                if (near.isEmpty()) Muted(if (s.radio == null) "Update once to save the list of transmitters." else "Update with location on to find the nearest transmitters.")
                near.forEach { (st, km) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${st.frequency} MHz", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("${st.name}, ${st.state} · ${st.callsign}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(fmt.distance(km), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Muted("Tune a radio that receives the weather band (most handhelds do) to one of the seven channels, 162.400 to 162.550 MHz. Strong signals reach about 40 miles; use a better antenna or higher ground for more.")
            }
            Muted("Sources: USGS (earthquakes), US National Weather Service (alerts), Open-Meteo (forecast, CC BY 4.0), NOAA Space Weather Prediction Center and NOAA Weather Radio. All free, no accounts. Alerts and the forecast are asked for your position rounded to about 7 miles; the rest download whole regions and are filtered on this phone.")
            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Box2(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun Header(text: String) = Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Muted(text: String) = Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun AlertRow(a: WeatherAlert, expired: Boolean) {
    var open by remember { mutableStateOf(false) }
    val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    Column(Modifier.fillMaxWidth().clickable { open = !open }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(a.event, fontWeight = FontWeight.Bold, color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else if (a.rank <= 1) danger else warn)
        Muted(a.expiresMillis?.let { (if (expired) "Ended " else "Until ") + df.format(Date(it)) } ?: a.headline)
        if (open) {
            Muted(a.areas)
            Text(a.detail, fontSize = 14.sp)
            a.instruction?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Muted(a.sender)
        }
    }
}
