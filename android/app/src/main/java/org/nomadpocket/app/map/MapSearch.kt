// MapSearch.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Search by coordinates, favorites, quick spots and the Go to panel. Port of MapSearchViews.swift.

package org.nomadpocket.app.map

import org.nomadpocket.app.i18n.tr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import kotlin.math.max
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.nomadpocket.app.conditions.ConditionsFormat

/** Star and Go to for any place with a position. */
@Composable
fun PlaceActions(name: String, latitude: Double, longitude: Double, onGoTo: () -> Unit = {}) {
    val store = FavoritesStore.get(LocalContext.current)
    var starred by remember(latitude, longitude) { mutableStateOf(store.isFavorite(latitude, longitude)) }
    var routing by remember { mutableStateOf(false) }
    var pathing by remember { mutableStateOf(false) }
    val here = org.nomadpocket.app.location.rememberLocation()
    if (pathing) RadioPathDialog(name, latitude, longitude, here?.let { ShapePoint(it.latitude, it.longitude) }) { pathing = false }
    if (routing) DirectionsDialog(name, latitude, longitude, here?.let { ShapePoint(it.latitude, it.longitude) }) { routing = false }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { starred = store.toggle(name, latitude, longitude) }) {
            Icon(if (starred) Icons.Filled.Star else Icons.Filled.StarBorder, null)
            Spacer(Modifier.width(6.dp)); Text(if (starred) "Favorited" else "Favorite")
        }
        OutlinedButton(onClick = { NavigationState.start(name, latitude, longitude); onGoTo() }) {
            Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(6.dp)); Text(tr("Go to"))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { routing = true }) { Icon(Icons.Filled.Directions, null); Spacer(Modifier.width(6.dp)); Text(tr("Route")) }
        OutlinedButton(onClick = { pathing = true }) { Icon(Icons.Filled.ShowChart, null); Spacer(Modifier.width(6.dp)); Text(tr("Radio path")) }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSearchSheet(
    pins: List<MapPin>, shapes: List<MapShape>, here: Pair<Double, Double>?, centre: Pair<Double, Double>?,
    onFly: (Double, Double) -> Unit, onDropPin: (Double, Double) -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { FavoritesStore.get(context) }
    val prefs = remember { context.getSharedPreferences("map", Context.MODE_PRIVATE) }
    var favorites by remember { mutableStateOf(store.favorites) }
    var text by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(runCatching { PositionFormat.valueOf(prefs.getString("coordFormat", null) ?: "") }.getOrDefault(PositionFormat.DECIMAL)) }
    var formatMenu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<ParsedCoordinate?>(null) }
    var routeTo by remember { mutableStateOf<ParsedCoordinate?>(null) }
    var nameText by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val q = text.trim()
    val parsed = remember(q) { if (q.isEmpty()) null else Coordinates.parse(q) }

    fun go(name: String, lat: Double, lon: Double) { NavigationState.start(name, lat, lon); onDismiss() }
    fun fly(lat: Double, lon: Double) { onFly(lat, lon); onDismiss() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Search"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { formatMenu = true }) { Text(format.label) }
                    DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                        PositionFormat.entries.forEach { f ->
                            DropdownMenuItem(text = { Text(f.label) }, onClick = { format = f; prefs.edit().putString("coordFormat", f.name).apply(); formatMenu = false })
                        }
                    }
                }
            }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Coordinates, grid, or a saved place") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { IconButton(onClick = { clipboard.getText()?.let { text = it.text } }) { Icon(Icons.Filled.ContentPaste, "Paste") } })
            Text("Try 39.7392, -104.9903 or 39°44'21\"N 104°59'25\"W or 13S 500831 4398811 or DM79mr.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            parsed?.let { p ->
                Text("Found a position (${p.format.label})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                PositionFormat.entries.forEach { f ->
                    val s = Coordinates.format(p.latitude, p.longitude, f)
                    Row(Modifier.fillMaxWidth().clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(s)) }.padding(vertical = 3.dp)) {
                        Text(f.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(s, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { fly(p.latitude, p.longitude) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Show") }
                    OutlinedButton(onClick = { go("Position", p.latitude, p.longitude) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(tr("Go to")) }
                    OutlinedButton(onClick = { routeTo = p }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(tr("Route")) }
                    OutlinedButton(onClick = { onDropPin(p.latitude, p.longitude); onDismiss() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Pin") }
                    OutlinedButton(onClick = { nameText = ""; naming = p }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("★") }
                }
            }

            if (q.isEmpty()) {
                Text(tr("Quick spots"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                listOf(FavoriteKind.HOME, FavoriteKind.CAMP, FavoriteKind.RALLY, FavoriteKind.CAR).forEach { kind ->
                    val spot = favorites.firstOrNull { it.kind == kind }
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(kind.label, fontWeight = FontWeight.Medium)
                            Text(spot?.let { Coordinates.format(it.latitude, it.longitude, format) } ?: "Not set", fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options for ${kind.label}") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (spot != null) {
                                    DropdownMenuItem(text = { Text("Go to ${kind.label}") }, onClick = { menu = false; go(spot.name, spot.latitude, spot.longitude) })
                                    DropdownMenuItem(text = { Text(tr("Show on map")) }, onClick = { menu = false; fly(spot.latitude, spot.longitude) })
                                }
                                DropdownMenuItem(text = { Text(if (spot == null) "Set to where I am" else "Move to where I am") }, enabled = here != null,
                                    onClick = { menu = false; here?.let { store.add(kind.label, kind, it.first, it.second); favorites = store.favorites } })
                                DropdownMenuItem(text = { Text("Set to the map centre") }, enabled = centre != null,
                                    onClick = { menu = false; centre?.let { store.add(kind.label, kind, it.first, it.second); favorites = store.favorites } })
                                if (spot != null) DropdownMenuItem(text = { Text("Clear") }, onClick = { menu = false; store.remove(spot.id); favorites = store.favorites })
                            }
                        }
                    }
                }
            }

            val list = store.search(q).filter { !(it.kind.quick && q.isEmpty()) }
            if (list.isNotEmpty()) {
                Text(tr("Favorites"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                list.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { fly(f.latitude, f.longitude) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(f.name)
                            Text(Coordinates.format(f.latitude, f.longitude, format), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { go(f.name, f.latitude, f.longitude) }) { Icon(Icons.Filled.Navigation, "Go to ${f.name}") }
                        IconButton(onClick = { store.remove(f.id); favorites = store.favorites }) { Icon(Icons.Filled.Delete, "Remove ${f.name}") }
                    }
                }
            }
            if (q.isNotEmpty()) {
                val matches = pins.filter { it.name.contains(q, true) || it.kind.label.contains(q, true) }
                if (matches.isNotEmpty()) Text("Places", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                matches.forEach { pin ->
                    Row(Modifier.fillMaxWidth().clickable { fly(pin.latitude, pin.longitude) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(pin.name, Modifier.weight(1f))
                        IconButton(onClick = { go(pin.name, pin.latitude, pin.longitude) }) { Icon(Icons.Filled.Navigation, "Go to ${pin.name}") }
                        IconButton(onClick = { store.toggle(pin.name, pin.latitude, pin.longitude); favorites = store.favorites }) { Icon(Icons.Filled.StarBorder, "Favorite ${pin.name}") }
                    }
                }
                val drawn = shapes.filter { it.name.contains(q, true) }
                if (drawn.isNotEmpty()) Text("Drawings", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                drawn.forEach { s ->
                    Text("Go along ${s.name}", Modifier.fillMaxWidth().clickable { NavigationState.start(s); onDismiss() }.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
                }
                if (parsed == null && matches.isEmpty() && drawn.isEmpty() && store.search(q).isEmpty())
                    Text("That doesn't look like a position, and no saved place matches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    routeTo?.let { p -> DirectionsDialog("Position", p.latitude, p.longitude, here?.let { ShapePoint(it.first, it.second) }) { routeTo = null } }
    naming?.let { p ->
        AlertDialog(onDismissRequest = { naming = null }, title = { Text("Save favorite") },
            text = { OutlinedTextField(nameText, { nameText = it }, singleLine = true, placeholder = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { store.add(nameText, latitude = p.latitude, longitude = p.longitude); favorites = store.favorites; naming = null }) { Text(tr("Save")) } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(tr("Cancel")) } })
    }
}

/** The phone's compass heading in degrees from TRUE north (the magnetic reading plus the local declination), or null with no sensor. */
@Composable
fun rememberHeading(active: Boolean, location: android.location.Location? = null): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    val declination = remember(location?.latitude?.let { Math.round(it * 5) }, location?.longitude?.let { Math.round(it * 5) }) {
        location?.let { android.hardware.GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination } ?: 0f
    }
    val declinationNow by rememberUpdatedState(declination)
    DisposableEffect(active) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val r = FloatArray(9); private val o = FloatArray(3)
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, e.values)
                SensorManager.getOrientation(r, o)
                val deg = ((Math.toDegrees(o[0].toDouble()) + declinationNow + 720) % 360).toFloat()
                if (heading == null || kotlin.math.abs(deg - heading!!) > 2f) heading = deg
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (active && sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return heading
}

/** The compass-navigation panel over the map. */
@Composable
fun GoToPanel(target: GoToTarget, arrived: Boolean, here: ShapePoint?, heading: Float?) {
    val metric = remember { ConditionsFormat.current().metric }
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(target.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (target.isRoute) Text("Waypoint ${target.index + 1} of ${target.waypoints.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (target.isRoute && !target.isLast) TextButton(onClick = { NavigationState.skip() }) { Text(tr("Skip")) }
                TextButton(onClick = { NavigationState.stop() }) { Text(tr("Stop"), color = MaterialTheme.colorScheme.error) }
            }
            when {
                arrived -> Text("You've arrived", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                here == null -> Text("Waiting for GPS…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val (distance, bearing) = GoToMath.leg(here, target.current)
                    val total = GoToMath.remaining(target, here)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Filled.Navigation, "Direction to ${target.name}", Modifier.size(52.dp).rotate((heading?.let { GoToMath.arrowAngle(bearing, it.toDouble()) } ?: bearing).toFloat()),
                            tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(ShapeMath.distanceText(distance, metric), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Heading ${ShapeMath.bearingText(bearing)} · ${GoToMath.etaText(GoToMath.eta(total))} on foot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (target.isRoute) Text("${ShapeMath.distanceText(total, metric)} left on the route", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (heading == null) Text("No compass: the arrow points from north", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Download elevation tiles for hills on the map and for radio line of sight. */
@Composable
fun TerrainDialog(store: TerrainStore, state: TerrainStore.State, centre: Pair<Double, Double>?, onDismiss: () -> Unit) {
    var radius by remember { mutableStateOf(50.0) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("Elevation")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Elevation data lets the map shade hills (Layers > Look) and lets Pocket Nomad check a radio path for hills in the way. Download an area once and it works with no signal.", style = MaterialTheme.typography.bodySmall)
                if (centre != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(25.0, 50.0, 100.0).forEach { r -> FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text("${r.toInt()} km") }) }
                    }
                    Text("About ${store.estimateMB(centre.first, centre.second, radius).toInt()} MB around the map centre", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { scope.launch { store.download(centre.first, centre.second, radius) } }, enabled = !state.downloading) {
                        Text(if (state.downloading) "Downloading…" else "Download")
                    }
                    if (state.downloading) LinearProgressIndicator(progress = { state.progress }, Modifier.fillMaxWidth())
                }
                if (state.message.isNotEmpty()) Text(state.message, style = MaterialTheme.typography.bodySmall)
                Text(if (state.tileCount > 0) "${state.tileCount} tiles on this phone." else "Nothing saved. Hills load live when you have signal.")
                if (state.tileCount > 0) TextButton(onClick = { store.deleteAll() }) { Text("Delete saved elevation", color = MaterialTheme.colorScheme.error) }
                Text("Elevation: Mapzen terrain tiles from AWS Open Data (SRTM, USGS, and others).", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Done")) } })
}

/** Plan a route to a place on the roads saved on this phone. */
@Composable
fun DirectionsDialog(name: String, latitude: Double, longitude: Double, here: ShapePoint?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { RoadStore.get(context) }
    val state by store.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("map", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(runCatching { TravelMode.valueOf(prefs.getString("travelMode", "") ?: "") }.getOrDefault(TravelMode.FOOT)) }
    var route by remember { mutableStateOf<Route?>(null) }
    var note by remember { mutableStateOf("") }
    var planning by remember { mutableStateOf(false) }
    var radius by remember { mutableStateOf(10.0) }
    var avoidHighways by remember { mutableStateOf(prefs.getBoolean("avoidHighways", false)) }
    var avoidUnpaved by remember { mutableStateOf(prefs.getBoolean("avoidUnpaved", false)) }
    val routePrefs = RoutePrefs(avoidHighways && mode == TravelMode.CAR, avoidUnpaved)
    val metric = remember { ConditionsFormat.current().metric }
    val scope = rememberCoroutineScope()

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Directions to $name") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TravelMode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m; prefs.edit().putString("travelMode", m.name).apply(); route = null; note = "" }, label = { Text(m.label) })
                    }
                }
                if (mode != TravelMode.FOOT) {
                    if (mode == TravelMode.CAR) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Avoid highways"), Modifier.weight(1f))
                        Switch(avoidHighways, { avoidHighways = it; route = null; prefs.edit().putBoolean("avoidHighways", it).apply() })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Avoid dirt and gravel"), Modifier.weight(1f))
                        Switch(avoidUnpaved, { avoidUnpaved = it; route = null; prefs.edit().putBoolean("avoidUnpaved", it).apply() })
                    }
                }
                if (state.cellCount == 0) {
                    Text("Directions work with no signal once the roads around you are saved. Download them while you have signal.", style = MaterialTheme.typography.bodySmall)
                    if (here != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(10.0, 25.0).forEach { r -> FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text("${r.toInt()} km") }) }
                        }
                        Text("Up to about ${store.estimateMB(here.latitude, here.longitude, radius).toInt()} MB in a city, much less in the country. The free public service can be slow, so a big area may take several minutes.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { scope.launch { store.download(here.latitude, here.longitude, radius) } }, enabled = !state.downloading) {
                            Text(if (state.downloading) "Downloading…" else "Download roads around me")
                        }
                        if (state.downloading) LinearProgressIndicator(progress = { state.progress }, Modifier.fillMaxWidth())
                        if (state.message.isNotEmpty()) Text(state.message, style = MaterialTheme.typography.bodySmall)
                    } else Text("Waiting for GPS…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(enabled = !planning && here != null, onClick = {
                        planning = true; note = ""
                        scope.launch {
                            route = store.plan(mode, here!!, ShapePoint(latitude, longitude), routePrefs)
                            if (route == null) note = "No route found. Either you or the place is outside the saved roads, or they aren't connected for ${mode.label.lowercase()}ing."
                            planning = false
                        }
                    }) { Text(if (planning) "Planning…" else "Plan route from where I am") }
                    if (here == null) Text("Waiting for GPS…", style = MaterialTheme.typography.bodySmall)
                    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
                }
                route?.let { r ->
                    val mins = Math.round(r.seconds / 60).toInt()
                    Text("${ShapeMath.distanceText(r.meters, metric)} · ${if (mins < 60) "${maxOf(1, mins)} min" else "${mins / 60} h ${mins % 60} min"}", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        NavigationState.start(name, r, ShapePoint(latitude, longitude), mode, routePrefs) { m, p, a, b -> store.plan(m, a, b, p) }
                        onDismiss()
                    }) { Text(tr("Start")) }
                    r.steps.forEach { s ->
                        Row { Text(s.text, Modifier.weight(1f)); if (s.meters > 0) Text(ShapeMath.distanceText(s.meters, metric), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (state.cellCount > 0) {
                    Text("${state.cellCount} squares (each about 5 km) saved on this phone.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { store.deleteAll(); route = null }) { Text("Delete saved roads", color = MaterialTheme.colorScheme.error) }
                }
                Text("Roads and paths: OpenStreetMap contributors (ODbL), downloaded through the Overpass service. Routes are estimates: check conditions, closures and private land yourself.",
                    style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Done")) } })
}

/** Terrain profile and Fresnel clearance between you and a place (a radio, a repeater, a pin). */
@Composable
fun RadioPathDialog(name: String, latitude: Double, longitude: Double, here: ShapePoint?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val terrain = remember { TerrainStore.get(context) }
    val tstate by terrain.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("map", Context.MODE_PRIVATE) }
    var bandId by remember { mutableStateOf(prefs.getString("pathBand", "lora915") ?: "lora915") }
    var hA by remember { mutableStateOf(prefs.getFloat("pathHeightA", 2f).toDouble()) }
    var hB by remember { mutableStateOf(prefs.getFloat("pathHeightB", 10f).toDouble()) }
    var showTerrain by remember { mutableStateOf(false) }
    var fromChoice by remember { mutableStateOf(prefs.getString("pathFrom", "me") ?: "me") }
    val favs = remember { FavoritesStore.get(context).sorted }
    val metric = remember { ConditionsFormat.current().metric }
    val start: ShapePoint? = when {
        fromChoice == "centre" -> MapMemory.camera?.target?.let { ShapePoint(it.latitude, it.longitude) }
        favs.any { it.id == fromChoice } -> favs.first { it.id == fromChoice }.let { ShapePoint(it.latitude, it.longitude) }
        else -> here
    }
    val band = RadioBand.ALL.firstOrNull { it.id == bandId } ?: RadioBand.ALL[0]
    val result = remember(tstate.tileCount, start, bandId, hA, hB) {
        if (tstate.tileCount > 0 && start != null) LineOfSight.analyze(start, ShapePoint(latitude, longitude), hA, hB, band.mhz) { la, lo -> terrain.elevation(la, lo) } else null
    }
    if (showTerrain) TerrainDialog(terrain, tstate, here?.let { it.latitude to it.longitude }) { showTerrain = false }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Radio path to $name") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Path from", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("me" to "Where I am", "centre" to "Middle of the map").plus(favs.map { it.id to it.name }).forEach { (id, label) ->
                        FilterChip(selected = fromChoice == id, onClick = { fromChoice = id; prefs.edit().putString("pathFrom", id).apply() }, label = { Text(label) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RadioBand.ALL.forEach { b -> FilterChip(selected = bandId == b.id, onClick = { bandId = b.id; prefs.edit().putString("pathBand", b.id).apply() }, label = { Text(b.name) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("My antenna: ${ShapeMath.heightText(hA, metric)}", Modifier.weight(1f))
                    IconButton(onClick = { hA = max(0.0, hA - 1); prefs.edit().putFloat("pathHeightA", hA.toFloat()).apply() }) { Text("−") }
                    IconButton(onClick = { hA = minOf(50.0, hA + 1); prefs.edit().putFloat("pathHeightA", hA.toFloat()).apply() }) { Text("+") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Their antenna: ${ShapeMath.heightText(hB, metric)}", Modifier.weight(1f))
                    IconButton(onClick = { hB = max(0.0, hB - 1); prefs.edit().putFloat("pathHeightB", hB.toFloat()).apply() }) { Text("−") }
                    IconButton(onClick = { hB = minOf(100.0, hB + 1); prefs.edit().putFloat("pathHeightB", hB.toFloat()).apply() }) { Text("+") }
                }
                when {
                    tstate.tileCount == 0 -> {
                        Text("A path check needs elevation data for the area. Download it once and it works with no signal.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { showTerrain = true }) { Text("Download elevation…") }
                    }
                    start == null -> Text("Waiting for GPS…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result != null -> {
                        val r = result
                        Text("${r.verdict.label} · ${ShapeMath.distanceText(r.distance, metric)}", style = MaterialTheme.typography.titleMedium,
                            color = when (r.verdict) { PathVerdict.CLEAR -> androidx.compose.ui.graphics.Color(0xFF34C759); PathVerdict.MARGINAL -> androidx.compose.ui.graphics.Color(0xFFFF9500)
                                PathVerdict.BLOCKED -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                        if (r.points.size > 1) PathChart(r.points, Modifier.fillMaxWidth().height(160.dp))
                        val pct = (maxOf(0.0, minOf(1.0, r.worstFresnelRatio)) * 100).toInt()
                        when (r.verdict) {
                            PathVerdict.BLOCKED -> Text("The terrain rises ${ShapeMath.heightText(-r.worstClearance, metric)} into the path about ${ShapeMath.distanceText(r.worstAt, metric)} from you. Try a taller antenna or a higher spot.", style = MaterialTheme.typography.bodySmall)
                            PathVerdict.MARGINAL -> Text("The line is clear, but only $pct% of the first Fresnel zone is open (about 60% is wanted). Expect a weaker link.", style = MaterialTheme.typography.bodySmall)
                            PathVerdict.CLEAR -> Text("The first Fresnel zone is $pct% clear at its tightest point.", style = MaterialTheme.typography.bodySmall)
                            else -> {}
                        }
                        if (r.verdict != PathVerdict.UNKNOWN && start != null) {
                            Button(onClick = {
                                RadioPathOverlay.show(PathOverlay(start, ShapePoint(latitude, longitude), r.verdict, "${r.verdict.label}, ${ShapeMath.distanceText(r.distance, metric)}"))
                                NavigationState.requestMap()
                                onDismiss()
                            }) { Text(tr("Show on map")) }
                        }
                        if (r.missing > 0) Text("Elevation is missing for part of this path (outside the saved area), so the result is a guess.", style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFFFF9500))
                    }
                }
                Text("Terrain only. Trees, buildings and weather are not in the data, so Clear means possible, not guaranteed. Elevation: Mapzen terrain tiles (AWS Open Data).", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Done")) } })
}

@Composable
private fun PathChart(points: List<PathPoint>, modifier: Modifier) {
    val brown = androidx.compose.ui.graphics.Color(0x8C8B5A2B); val blue = androidx.compose.ui.graphics.Color(0xFF0A84FF); val orange = androidx.compose.ui.graphics.Color(0xCCFF9500)
    androidx.compose.foundation.Canvas(modifier) {
        val last = points.last(); if (last.distance <= 0) return@Canvas
        val lows = points.map { it.ground + it.bulge }
        val lo = (lows.minOrNull() ?: 0.0) - 5; val hi = maxOf(points.maxOf { it.line }, lows.maxOrNull() ?: 0.0) + 10
        fun x(d: Double) = (d / last.distance * size.width).toFloat()
        fun y(h: Double) = (size.height - (h - lo) / maxOf(1.0, hi - lo) * size.height).toFloat()
        val land = androidx.compose.ui.graphics.Path().apply {
            moveTo(x(points.first().distance), size.height)
            points.forEach { lineTo(x(it.distance), y(it.ground + it.bulge)) }
            lineTo(x(last.distance), size.height); close()
        }
        drawPath(land, brown)
        val fres = androidx.compose.ui.graphics.Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.distance), y(p.line - p.fresnel)) else lineTo(x(p.distance), y(p.line - p.fresnel)) } }
        drawPath(fres, orange, style = androidx.compose.ui.graphics.drawscope.Stroke(2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        drawLine(blue, androidx.compose.ui.geometry.Offset(x(points.first().distance), y(points.first().line)), androidx.compose.ui.geometry.Offset(x(last.distance), y(last.line)), 5f)
        listOf(points.first(), last).forEach { drawCircle(blue, 9f, androidx.compose.ui.geometry.Offset(x(it.distance), y(it.line))) }
    }
}
