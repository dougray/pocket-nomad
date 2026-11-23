package org.nomadpocket.app.map

import org.nomadpocket.i18n.tr

import android.content.Context
import android.graphics.RectF
import android.view.Gravity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import org.nomadpocket.app.DemoData
import org.nomadpocket.app.location.LocationService
import org.nomadpocket.app.location.rememberLocation
import org.nomadpocket.app.ui.Footnote
import org.nomadpocket.mesh.CoverageData
import org.nomadpocket.mesh.CoverageShare
import org.nomadpocket.mesh.Firmware
import org.nomadpocket.mesh.MapNode
import org.nomadpocket.mesh.Mesh
import org.nomadpocket.mesh.RadiosHeard
import org.nomadpocket.mesh.UnidentifiedRepeater
import org.nomadpocket.mesh.rawValue
import kotlin.math.max

/** Camera and first-fix state that outlive the map view when the user switches tabs. */
internal object MapMemory {
    var camera: CameraPosition? = null
    var snappedToUser = false
}

/** What a tap on the map found. */
sealed interface MapTap {
    data class Pin(val pin: MapPin) : MapTap
    data class Node(val node: MapNode) : MapTap
    data class Radio(val radio: KnownRadio) : MapTap
}

private const val SRC_KNOWN = "known-radios"
private const val SRC_NODES = "mesh-nodes"
private const val SRC_PINS = "pins"
private const val SRC_CELLS = "coverage-cells"
private const val SRC_APRS = "aprs-stations"
private const val SRC_QUAKES = "quakes"
private const val SRC_FAVORITES = "favorites"
private const val SRC_GOTO = "goto-line"
private const val SRC_AIRCRAFT = "sdr-aircraft"
private const val SRC_PATH = "radio-path"
private const val SRC_POTA = "pota"
private const val SRC_NVIS = "nvis-ring"
private const val SRC_ROUTE = "route"
private val LABEL_FONT = arrayOf("Noto Sans Regular")

@Composable
fun MapScreen(onCoverageSettings: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("map", Context.MODE_PRIVATE) }
    var showNodes by remember { mutableStateOf(prefs.getBoolean("showNodes", true)) }
    var showKnown by remember { mutableStateOf(prefs.getBoolean("showKnown", true)) }
    var showAprs by remember { mutableStateOf(prefs.getBoolean("showAprs", true)) }
    var showQuakes by remember { mutableStateOf(prefs.getBoolean("showQuakes", true)) }
    var showShapes by remember { mutableStateOf(prefs.getBoolean("showShapes", true)) }
    var showFavorites by remember { mutableStateOf(prefs.getBoolean("showFavorites", true)) }
    var showSearch by remember { mutableStateOf(false) }
    var showNvis by remember { mutableStateOf(prefs.getBoolean("showNvis", false)) }
    var showPota by remember { mutableStateOf(prefs.getBoolean("showPota", true)) }
    var showPotaList by remember { mutableStateOf(false) }
    val potaStore = remember { PotaStore.get(context) }
    val potaState by potaStore.state.collectAsState()
    val pathOverlay by RadioPathOverlay.state.collectAsState()
    var showAircraft by remember { mutableStateOf(prefs.getBoolean("showAircraft", true)) }
    val sdrAircraft by org.nomadpocket.app.sdr.SdrState.aircraft.collectAsState()
    val sdrShips by org.nomadpocket.app.sdr.SdrState.ships.collectAsState()
    val sdrSondes by org.nomadpocket.app.sdr.SdrState.sondes.collectAsState()
    val sdrSpots by org.nomadpocket.app.sdr.SdrState.spots.collectAsState()
    var look by remember { mutableStateOf(runCatching { MapLook.valueOf(prefs.getString("look", "") ?: "") }.getOrDefault(MapLook.STREETS)) }
    var showTerrain by remember { mutableStateOf(false) }
    val terrainStore = remember { TerrainStore.get(context) }
    val terrainState by terrainStore.state.collectAsState()
    /** Recenter: tap once to follow, again to turn with your heading, again to stop. */
    var followMode by remember { mutableStateOf(CameraMode.NONE) }
    val favoriteStore = remember { FavoritesStore.get(context) }
    val navTarget by NavigationState.target.collectAsState()
    val navArrived by NavigationState.arrived.collectAsState()
    val shapeStore = remember { ShapeStore(java.io.File(context.filesDir, "shapes.json")) }
    var shapes by remember { mutableStateOf(shapeStore.shapes) }
    val draft = remember { androidx.compose.runtime.mutableStateListOf<LatLng>() }
    var draftClosed by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf(false) }
    var showShapesList by remember { mutableStateOf(false) }
    var namingShape by remember { mutableStateOf(false) }
    val metric = remember { org.nomadpocket.app.conditions.ConditionsFormat.current().metric }
    measuringRef.value = measuring
    draftAddRef.value = { draft.add(it) }
    var showMeshKnown by remember { mutableStateOf(prefs.getBoolean("showMeshKnown", false)) }
    var coverageMode by remember { mutableStateOf(CoverageLayer.load(prefs)) }
    var coverageNetwork by remember { mutableStateOf(CoverageNetwork.from(prefs.getString("coverageNetwork", null))) }
    var pins by remember { mutableStateOf(MapPin.load(context)) }
    fun savePins(new: List<MapPin>) {
        pins = new
        MapPin.save(context, new)
    }

    val location = rememberLocation()
    val heading = rememberHeading(navTarget != null, location)
    val permitted by LocationService.permitted.collectAsState()
    val heard by Mesh.repository.heard.collectAsState()
    val demoNodes by DemoData.nodesFlow.collectAsState()
    val meshCoverage by Mesh.repository.coverage.collectAsState()
    val demoCoverage by DemoData.coverageFlow.collectAsState()
    val known by KnownRadioStore.state.collectAsState()
    val own by Mesh.repository.own.collectAsState()
    val regions by OfflineRegions.regions.collectAsState()

    // A node is its network plus number: the same number can turn up on both networks.
    // "Heard directly" (on by default) and "Known on the mesh" (relayed adverts, off by default).
    val directNodes = if (showNodes) (heard.direct + demoNodes).distinctBy { it.firmware to it.num } else emptyList()
    val meshKnownNodes = if (showMeshKnown) heard.known.filterNot { k -> directNodes.any { it.firmware == k.firmware && it.num == k.num } } else emptyList()
    val nodes = directNodes + meshKnownNodes
    val allCoverage: CoverageData = demoCoverage ?: meshCoverage
    val coverage = coverageNetwork.filter(allCoverage)
    // The private layer ("Where I drove") is hexagons: one dot per recording or ping would draw the exact trip.
    val myCells = remember(allCoverage, coverageNetwork, coverageMode) {
        if (coverageMode == CoverageLayer.RECORDED) CoverageShare.mapCells(allCoverage, coverageNetwork.firmware) else emptyList()
    }
    // The user's own radios are never drawn, even when a community map lists them.
    val shownKnown = if (showKnown) remember(known.radios, own) { KnownRadioParser.withoutOwn(known.radios, own) } else emptyList()
    val cells = rememberSharedCells(coverageMode == CoverageLayer.SHARED, coverageNetwork)

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var pendingPin by remember { mutableStateOf<LatLng?>(null) }
    var showPlaces by remember { mutableStateOf(false) }
    var showKnownSheet by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }
    var showStreets by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var tapped by remember { mutableStateOf<MapTap?>(null) }

    LaunchedEffect(Unit) { OfflineRegions.refresh(context) }

    val mapView = rememberMapView()
    val topInset = WindowInsets.statusBars.getTop(LocalDensity.current) + with(LocalDensity.current) { 8.dp.roundToPx() }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isCompassEnabled = true
            // Attribution and compass go top-left, clear of the buttons and the legends.
            m.uiSettings.attributionGravity = Gravity.TOP or Gravity.START
            m.uiSettings.setAttributionMargins(topInset / 4, topInset + topInset / 8, 0, 0)
            m.uiSettings.compassGravity = Gravity.TOP or Gravity.START
            m.uiSettings.setCompassMargins(topInset / 4, topInset * 2, 0, 0)
            val demo = DemoData.camera
            if (demo != null && MapMemory.camera == null) {
                MapMemory.snappedToUser = true
                MapMemory.camera = CameraPosition.Builder().target(LatLng(demo.first, demo.second)).zoom(demo.third).build()
            }
            m.cameraPosition = MapMemory.camera ?: CameraPosition.Builder().target(LatLng(39.0, -98.0)).zoom(2.5).build()
            m.addOnCameraIdleListener { MapMemory.camera = m.cameraPosition }
            m.setStyle(Style.Builder().fromUri(OfflineRegions.STYLE_URL)) { s ->
                addLayers(s)
                style = s
            }
            m.addOnMapLongClickListener { point ->
                pendingPin = point
                true
            }
            m.addOnMapClickListener { point ->
                if (measuringRef.value) { draftAddRef.value(point); return@addOnMapClickListener true }
                val p = m.projection.toScreenLocation(point)
                val box = RectF(p.x - 36, p.y - 36, p.x + 36, p.y + 36)
                val f = m.queryRenderedFeatures(box, SRC_PINS, SRC_NODES, SRC_KNOWN).firstOrNull()
                tapped = f?.let { feature ->
                    feature.getStringProperty("pid")?.let { id -> pinsRef.value.firstOrNull { it.id == id }?.let { MapTap.Pin(it) } }
                        ?: feature.getNumberProperty("num")?.let { n ->
                            val fw = feature.getStringProperty("fw")
                            nodesRef.value.firstOrNull { it.num == n.toLong() && it.firmware.rawValue == fw }?.let { MapTap.Node(it) }
                        }
                        // "id" is reserved in GeoJSON, so the radio id goes in "rid".
                        ?: feature.getStringProperty("rid")?.let { id -> KnownRadioStore.state.value.radios.firstOrNull { it.id == id }?.let { MapTap.Radio(it) } }
                }
                tapped != null
            }
        }
    }
    pinsRef.value = pins
    nodesRef.value = nodes

    // The blue dot comes from the app's own location listener (no Google services).
    LaunchedEffect(style, permitted) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!permitted) return@LaunchedEffect
        runCatching {
            val lc = m.locationComponent
            if (!lc.isLocationComponentActivated) {
                lc.activateLocationComponent(LocationComponentActivationOptions.builder(context, s).useDefaultLocationEngine(false).build())
            }
            @Suppress("MissingPermission")
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.NORMAL
        }
    }
    LaunchedEffect(location, style) {
        val loc = location ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        runCatching { if (m.locationComponent.isLocationComponentActivated) m.locationComponent.forceLocationUpdate(loc) }
        // On the first reasonable fix, centre on the user at street level.
        if (!MapMemory.snappedToUser && loc.hasAccuracy() && loc.accuracy <= 500) {
            MapMemory.snappedToUser = true
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), max(m.cameraPosition.zoom, 14.0)))
        }
    }

    // Shaded relief under the roads and labels, from the saved (or live) elevation tiles.
    LaunchedEffect(style, look, terrainState.tileCount) {
        val s = style ?: return@LaunchedEffect
        s.getLayer("terrain-hillshade")?.let { s.removeLayer(it) }
        s.getSource("terrain-dem")?.let { s.removeSource(it) }
        if (look.exaggeration <= 0f) return@LaunchedEffect
        val tiles = org.maplibre.android.style.sources.TileSet("2.2.0", terrainStore.template).apply { encoding = "terrarium"; maxZoom = 11f; minZoom = 0f }
        s.addSource(org.maplibre.android.style.sources.RasterDemSource("terrain-dem", tiles, 256))
        val layer = org.maplibre.android.style.layers.HillshadeLayer("terrain-hillshade", "terrain-dem")
            .withProperties(PropertyFactory.hillshadeExaggeration(look.exaggeration))
        val firstAbove = s.layers.firstOrNull { it is LineLayer || it is SymbolLayer }
        if (firstAbove != null) s.addLayerBelow(layer, firstAbove.id) else s.addLayer(layer)
    }
    // The 300-mile NVIS ring around you.
    LaunchedEffect(style, showNvis, location) {
        val loc = location
        val ring = if (showNvis && loc != null) org.nomadpocket.ham.Nvis.circle(loc.latitude, loc.longitude, org.nomadpocket.ham.Nvis.RANGE_KM) else emptyList()
        style?.source(SRC_NVIS)?.setGeoJson(FeatureCollection.fromFeatures(
            if (ring.size < 3) emptyList() else listOf(Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(ring.map { Point.fromLngLat(it.second, it.first) })))))
    }
    // Parks on the Air: parks as green dots, people on the air from a park as orange ones.
    LaunchedEffect(style, potaState, showPota) {
        val snap = potaState.snapshot
        val spots = if (showPota) potaStore.activeSpots(null) else emptyList()
        val live = spots.map { it.reference }.toSet()
        val feats = if (showPota && snap != null) snap.parks.filter { it.reference !in live }.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).also { it.addStringProperty("name", p.reference); it.addNumberProperty("live", 0) }
        } else emptyList()
        style?.source(SRC_POTA)?.setGeoJson(FeatureCollection.fromFeatures(feats + spots.map { s ->
            Feature.fromGeometry(Point.fromLngLat(s.longitude, s.latitude)).also { it.addStringProperty("name", "${s.activator} ${s.frequencyText} ${s.mode}"); it.addNumberProperty("live", 1) }
        }))
    }
    // The radio path last checked, as a line coloured by the verdict.
    LaunchedEffect(style, pathOverlay) {
        val o = pathOverlay
        style?.source(SRC_PATH)?.setGeoJson(FeatureCollection.fromFeatures(
            if (o == null) emptyList() else listOf(Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(
                listOf(Point.fromLngLat(o.from.longitude, o.from.latitude), Point.fromLngLat(o.to.longitude, o.to.latitude)))).also {
                it.addStringProperty("v", when (o.verdict) { PathVerdict.CLEAR -> "clear"; PathVerdict.MARGINAL -> "marginal"; else -> "blocked" })
            })))
    }
    // Aircraft heard by an SDR box.
    LaunchedEffect(style, sdrAircraft, sdrShips, sdrSondes, sdrSpots, showAircraft) {
        val now = System.currentTimeMillis()
        val sdr = org.nomadpocket.app.sdr.SdrState
        fun pt(lon: Double, lat: Double, name: String, kind: Int) = Feature.fromGeometry(Point.fromLngLat(lon, lat)).also { it.addStringProperty("name", name); it.addNumberProperty("kind", kind) }
        val feats = if (!showAircraft) emptyList() else
            sdr.visibleAircraft(now).map { a -> pt(a.longitude, a.latitude, "${a.callsign.ifEmpty { a.icao }} ${if (a.altitudeFeet >= 18000) "FL${a.altitudeFeet / 100}" else "${a.altitudeFeet} ft"}", 0) } +
            sdr.visibleShips(now).map { s -> pt(s.longitude, s.latitude, s.name.ifEmpty { "MMSI ${s.mmsi}" }, 1) } +
            sdr.visibleSondes(now).map { s -> pt(s.longitude, s.latitude, "${s.id} ${s.altitudeMetres / 100 * 100} m", 2) } +
            sdr.visibleSpots(now).map { s -> pt(s.longitude, s.latitude, "${s.call} ${s.snr} dB", 3) }
        style?.source(SRC_AIRCRAFT)?.setGeoJson(FeatureCollection.fromFeatures(feats))
    }
    // Go to: keep the position fresh for the arrival check, and draw the dashed line.
    LaunchedEffect(location) { location?.let { NavigationState.update(ShapePoint(it.latitude, it.longitude)) } }
    LaunchedEffect(style, showSearch, showFavorites, navTarget, location) {
        val favs = if (showFavorites) favoriteStore.favorites else emptyList()
        style?.source(SRC_FAVORITES)?.setGeoJson(FeatureCollection.fromFeatures(favs.map {
            Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).also { f -> f.addStringProperty("name", it.name) }
        }))
        val t = navTarget
        val loc = location
        val line = if (t != null && loc != null) listOf(
            Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(listOf(Point.fromLngLat(loc.longitude, loc.latitude), Point.fromLngLat(t.current.longitude, t.current.latitude)))),
            Feature.fromGeometry(Point.fromLngLat(t.current.longitude, t.current.latitude))) else emptyList()
        style?.source(SRC_GOTO)?.setGeoJson(FeatureCollection.fromFeatures(line))
        val road = t?.line.orEmpty()
        style?.source(SRC_ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(
            if (road.size >= 2) listOf(Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(road.map { Point.fromLngLat(it.longitude, it.latitude) }))) else emptyList()))
    }
    LaunchedEffect(style, pins) { style?.source(SRC_PINS)?.setGeoJson(pinFeatures(pins)) }
    LaunchedEffect(style, directNodes, meshKnownNodes) { style?.source(SRC_NODES)?.setGeoJson(nodeFeatures(directNodes, meshKnownNodes)) }
    LaunchedEffect(style, shownKnown) { style?.source(SRC_KNOWN)?.setGeoJson(knownFeatures(shownKnown)) }
    LaunchedEffect(style, shapes, showShapes) {
        style?.source(SRC_SHAPES)?.setGeoJson(shapeFeatures(if (showShapes) shapes else emptyList()))
        style?.source(SRC_SHAPE_LABELS)?.setGeoJson(shapeLabelFeatures(if (showShapes) shapes else emptyList()))
    }
    LaunchedEffect(style, draft.toList(), draftClosed) { style?.source(SRC_SHAPE_DRAFT)?.setGeoJson(draftFeatures(draft, draftClosed)) }
    // Recent earthquakes near the user (from Conditions).
    val conditions by org.nomadpocket.app.conditions.ConditionsStore.state.collectAsState()
    val quakes = if (showQuakes) org.nomadpocket.app.conditions.ConditionsStore.nearbyQuakes(conditions) else emptyList()
    LaunchedEffect(style, quakes) { style?.source(SRC_QUAKES)?.setGeoJson(quakeFeatures(quakes)) }
    // Ham APRS stations heard by the TNC.
    val aprs = if (showAprs && org.nomadpocket.ham.Ham.isInitialized) org.nomadpocket.ham.Ham.service.stations.filter { it.hasPosition } else emptyList()
    LaunchedEffect(style, aprs) { style?.source(SRC_APRS)?.setGeoJson(aprsFeatures(aprs)) }
    LaunchedEffect(style, cells, myCells, coverageNetwork, coverageMode) {
        style?.source(SRC_CELLS)?.setGeoJson(if (coverageMode == CoverageLayer.RECORDED) myCellFeatures(myCells, coverageNetwork.firmware) else cellFeatures(cells))
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize())

        Column(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 56.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MapButton(if (followMode == CameraMode.TRACKING_COMPASS) Icons.Filled.Explore else Icons.Filled.MyLocation,
                when (followMode) { CameraMode.TRACKING -> "Following you, tap to turn with your heading"; CameraMode.TRACKING_COMPASS -> "Heading up, tap to stop following"; else -> "Show my location" }) {
                val m = map ?: return@MapButton
                val next = when (followMode) { CameraMode.NONE -> CameraMode.TRACKING; CameraMode.TRACKING -> CameraMode.TRACKING_COMPASS; else -> CameraMode.NONE }
                if (next == CameraMode.TRACKING) location?.let { m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), max(m.cameraPosition.zoom, 14.0))) }
                runCatching { if (m.locationComponent.isLocationComponentActivated) m.locationComponent.cameraMode = next }
                followMode = next
            }
            MapButton(Icons.Filled.Search, "Search and favorites") { showSearch = true }
            MapButton(Icons.AutoMirrored.Filled.List, tr("Places")) { showPlaces = true }
            MapButton(Icons.Filled.Straighten, if (measuring) "Stop measuring" else "Measure and draw") { measuring = !measuring }
            Box {
                MapButton(Icons.Filled.Layers, tr("Map layers")) { showLayers = true }
                DropdownMenu(expanded = showLayers, onDismissRequest = { showLayers = false }) {
                    CheckItem(tr("Radios heard directly"), Icons.Filled.SettingsInputAntenna, showNodes) {
                        showNodes = !showNodes; prefs.edit().putBoolean("showNodes", showNodes).apply()
                    }
                    CheckItem(tr("Known on the mesh"), Icons.Filled.Hub, showMeshKnown) {
                        showMeshKnown = !showMeshKnown; prefs.edit().putBoolean("showMeshKnown", showMeshKnown).apply()
                    }
                    CheckItem(tr("Known radios (community maps)"), Icons.Filled.Contactless, showKnown) {
                        showKnown = !showKnown; prefs.edit().putBoolean("showKnown", showKnown).apply()
                    }
                    MapLook.entries.forEach { l ->
                        DropdownMenuItem(text = { Text("Look: ${l.label}") }, leadingIcon = { Icon(if (look == l) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null) },
                            onClick = { look = l; prefs.edit().putString("look", l.name).apply(); showLayers = false })
                    }
                    DropdownMenuItem(text = { Text(tr("Download elevation (hills, line of sight)…")) }, leadingIcon = { Icon(Icons.Filled.Download, null) },
                        onClick = { showLayers = false; showTerrain = true })
                    CheckItem(tr("Aircraft (SDR box)"), Icons.Filled.Star, showAircraft) {
                        showAircraft = !showAircraft; prefs.edit().putBoolean("showAircraft", showAircraft).apply()
                    }
                    CheckItem(tr("NVIS range ring (300 mi)"), Icons.Filled.Star, showNvis) {
                        showNvis = !showNvis; prefs.edit().putBoolean("showNvis", showNvis).apply()
                    }
                    CheckItem(tr("Parks on the Air"), Icons.Filled.Star, showPota) {
                        showPota = !showPota; prefs.edit().putBoolean("showPota", showPota).apply()
                    }
                    DropdownMenuItem(text = { Text(tr("Parks on the Air…")) }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { showLayers = false; showPotaList = true })
                    CheckItem(tr("Favorites"), Icons.Filled.Star, showFavorites) {
                        showFavorites = !showFavorites; prefs.edit().putBoolean("showFavorites", showFavorites).apply()
                    }
                    CheckItem(tr("My drawings"), Icons.Filled.Straighten, showShapes) {
                        showShapes = !showShapes; prefs.edit().putBoolean("showShapes", showShapes).apply()
                    }
                    DropdownMenuItem(text = { Text(tr("Drawings…")) }, leadingIcon = { Icon(Icons.Filled.Straighten, null) },
                        onClick = { showLayers = false; showShapesList = true })
                    CheckItem(tr("Earthquakes (Conditions)"), Icons.Filled.Contactless, showQuakes) {
                        showQuakes = !showQuakes; prefs.edit().putBoolean("showQuakes", showQuakes).apply()
                    }
                    CheckItem(tr("Ham APRS stations heard"), Icons.Filled.Contactless, showAprs) {
                        showAprs = !showAprs; prefs.edit().putBoolean("showAprs", showAprs).apply()
                    }
                    DropdownMenuItem(text = { Text(tr("Download known radios…")) }, leadingIcon = { Icon(Icons.Filled.Download, null) },
                        onClick = { showLayers = false; showKnownSheet = true })
                    DropdownMenuItem(text = { Text(tr("Download streets around me…")) }, leadingIcon = { Icon(Icons.Filled.Map, null) },
                        onClick = { showLayers = false; showStreets = true })
                    HorizontalDivider()
                    Text(tr("Coverage"), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CoverageLayer.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            leadingIcon = { Icon(if (coverageMode == mode) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null) },
                            onClick = { coverageMode = mode; CoverageLayer.save(prefs, mode); showLayers = false },
                        )
                    }
                    if (coverageMode != CoverageLayer.OFF) {
                        Text(tr("Network"), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CoverageNetwork.entries.forEach { net ->
                            DropdownMenuItem(
                                text = { Text(net.label) },
                                leadingIcon = { Icon(if (coverageNetwork == net) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null,
                                    tint = net.firmware?.let { RadioColors.color(it) } ?: LocalContentColor.current) },
                                onClick = { coverageNetwork = net; prefs.edit().putString("coverageNetwork", net.id).apply(); showLayers = false },
                            )
                        }
                    }
                    DropdownMenuItem(text = { Text(tr("Coverage settings…")) }, leadingIcon = { Icon(Icons.Filled.Settings, null) },
                        onClick = { showLayers = false; onCoverageSettings() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(tr("Offline maps…")) }, leadingIcon = { Icon(Icons.Filled.Map, null) },
                        onClick = { showLayers = false; showOffline = true })
                }
            }
        }

        pathOverlay?.let { o ->
            Panel(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Radio path: ${o.label}", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { RadioPathOverlay.clear() }, Modifier.size(24.dp)) { Icon(Icons.Filled.Close, "Clear radio path") }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            navTarget?.let { if (!measuring) GoToPanel(it, navArrived, location?.let { l -> ShapePoint(l.latitude, l.longitude) }, heading) }
            if (measuring) MeasurePanel(draft, draftClosed, metric,
                onUndo = { if (draft.isNotEmpty()) draft.removeAt(draft.lastIndex) }, onClear = { draft.clear(); draftClosed = false },
                onClosed = { draftClosed = it }, onSave = { namingShape = true }, onDone = { measuring = false }, onList = { showShapesList = true })
            if (!measuring && coverageMode != CoverageLayer.OFF) CoverageLegend(allCoverage, coverage, coverageMode, coverageNetwork,
                if (coverageMode == CoverageLayer.SHARED) cells.size else myCells.size)
            val unidentified = if (showNodes) heard.unidentified else emptyList()
            if (!measuring && (nodes.isNotEmpty() || shownKnown.isNotEmpty() || unidentified.isNotEmpty()))
                RadioLegend(RadiosHeard.summary(directNodes), unidentified, meshKnownNodes.isNotEmpty())
            if (showKnown && known.radios.isEmpty()) {
                Panel(Modifier.clickable { showKnownSheet = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Download, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (known.downloading) "Downloading known radios…" else "Get known radios near you",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (regions.isEmpty()) {
                Panel(Modifier.clickable { showOffline = true }) {
                    Footnote(tr("Online map. To use it with no signal, save this area in Layers › Offline maps."))
                }
            }
        }
    }

    if (showTerrain) {
        TerrainDialog(terrainStore, terrainState, map?.cameraPosition?.target?.let { it.latitude to it.longitude } ?: location?.let { it.latitude to it.longitude }) { showTerrain = false }
    }
    if (showPotaList) PotaDialog { showPotaList = false }
    if (showSearch) {
        MapSearchSheet(pins, shapes, location?.let { it.latitude to it.longitude }, map?.cameraPosition?.target?.let { it.latitude to it.longitude },
            onFly = { lat, lon -> map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15.0)) },
            onDropPin = { lat, lon -> pendingPin = LatLng(lat, lon) }, onDismiss = { showSearch = false })
    }
    if (namingShape) {
        SaveShapeDialog(draftClosed && draft.size >= 3, onCancel = { namingShape = false }) { typed ->
            val pts = draft.map { ShapePoint(it.latitude, it.longitude) }
            shapeStore.add(MapShape(name = typed.ifEmpty { "Drawing ${shapes.size + 1}" }, colorIndex = shapes.size % ShapeMath.PALETTE.size, points = pts, closed = draftClosed && pts.size >= 3))
            shapes = shapeStore.shapes
            draft.clear(); draftClosed = false; namingShape = false
        }
    }
    if (showShapesList) {
        ShapesDialog(shapes, metric, context, onDismiss = { showShapesList = false },
            onShow = { s ->
                val b = org.maplibre.android.geometry.LatLngBounds.Builder()
                s.points.forEach { b.include(LatLng(it.latitude, it.longitude)) }
                if (s.points.size >= 2) map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 120))
                showShapesList = false
            },
            onRename = { s, n -> shapeStore.rename(s.id, n); shapes = shapeStore.shapes },
            onRecolor = { s -> shapeStore.recolor(s.id, (s.colorIndex + 1) % ShapeMath.PALETTE.size); shapes = shapeStore.shapes },
            onDelete = { s -> shapeStore.delete(s.id); shapes = shapeStore.shapes })
    }
    pendingPin?.let { point ->
        PinEditorSheet(point, onDismiss = { pendingPin = null }) { name, kind ->
            savePins(pins + MapPin(name = name.ifEmpty { kind.label }, kind = kind, latitude = point.latitude, longitude = point.longitude))
            pendingPin = null
        }
    }
    if (showPlaces) {
        PlacesSheet(
            pins = pins, regions = regions,
            onDismiss = { showPlaces = false },
            onPin = { pin ->
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pin.latitude, pin.longitude), 15.0))
                showPlaces = false
            },
            onDelete = { pin -> savePins(pins.filterNot { it.id == pin.id }) },
            onRegion = { r ->
                map?.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(r.north, r.east, r.south, r.west), 40))
                showPlaces = false
            },
            onWorld = {
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 0.0), 1.0))
                showPlaces = false
            },
        )
    }
    if (showKnownSheet) {
        KnownRadiosSheet(mapCenter = map?.cameraPosition?.target, onDismiss = { showKnownSheet = false })
    }
    if (showOffline) {
        OfflineMapsSheet(visibleBounds = { map?.projection?.visibleRegion?.latLngBounds }, onDismiss = { showOffline = false },
            onStreets = { showOffline = false; showStreets = true })
    }
    if (showStreets) {
        StreetsAroundMeSheet(mapCenter = map?.cameraPosition?.target, onDismiss = { showStreets = false })
    }
    tapped?.let { t -> TapDetailSheet(t) { tapped = null } }
}

/** The latest pins and nodes, read by the map's tap listener (registered once). */
private class Ref<T>(var value: T)
private val pinsRef = Ref<List<MapPin>>(emptyList())
private val measuringRef = Ref(false)
private val draftAddRef = Ref<(LatLng) -> Unit>({})
private val nodesRef = Ref<List<MapNode>>(emptyList())

private fun Style.source(id: String): GeoJsonSource? = getSourceAs(id)

private fun pinFeatures(pins: List<MapPin>) = FeatureCollection.fromFeatures(pins.map { p ->
    Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply {
        addStringProperty("pid", p.id)
        addStringProperty("name", p.name)
        addStringProperty("color", hex(p.kind.color))
    }
})

private fun nodeFeatures(direct: List<MapNode>, meshKnown: List<MapNode>) =
    FeatureCollection.fromFeatures(direct.map { it to true }.plus(meshKnown.map { it to false }).map { (n, heard) ->
    Feature.fromGeometry(Point.fromLngLat(n.longitude, n.latitude)).apply {
        addNumberProperty("direct", if (heard) 1 else 0)
        addNumberProperty("num", n.num)
        addStringProperty("name", n.name)
        addStringProperty("fw", if (n.firmware == Firmware.MESHCORE) "meshcore" else "meshtastic")
        addNumberProperty("rep", if (n.isRepeater) 1 else 0)
    }
})

private fun quakeFeatures(quakes: List<org.nomadpocket.app.conditions.Quake>) = FeatureCollection.fromFeatures(quakes.map { q ->
    Feature.fromGeometry(Point.fromLngLat(q.longitude, q.latitude)).apply {
        addNumberProperty("r", 4 + q.magnitude * 2.2)
        addNumberProperty("big", if (q.magnitude >= 5) 2 else if (q.magnitude >= 4) 1 else 0)
        addStringProperty("label", "M%.1f".format(java.util.Locale.US, q.magnitude))
    }
})

private fun aprsFeatures(stations: List<org.nomadpocket.ham.HamStation>) = FeatureCollection.fromFeatures(stations.map { s ->
    Feature.fromGeometry(Point.fromLngLat(s.longitude!!, s.latitude!!)).apply {
        addStringProperty("name", s.id)
        addNumberProperty("sos", if (s.emergency) 1 else 0)
        addNumberProperty("wx", if (s.weather != null) 1 else 0)
    }
})

private fun knownFeatures(radios: List<KnownRadio>) = FeatureCollection.fromFeatures(radios.map { r ->
    Feature.fromGeometry(Point.fromLngLat(r.longitude, r.latitude)).apply {
        addStringProperty("rid", r.id)
        addStringProperty("fw", if (r.firmware == Firmware.MESHCORE) "meshcore" else "meshtastic")
        addNumberProperty("big", if (r.isBig) 1 else 0)
    }
})

private fun snrColor(): Expression = Expression.interpolate(Expression.linear(), get("snr"),
    *CoverageColors.stops.map { (snr, c) -> stop(snr, color(c.toArgb())) }.toTypedArray())

private fun cellFeatures(cells: List<CoverageShare.Cell>) = FeatureCollection.fromFeatures(cells.map { cell ->
    val ring = cell.corners.map { (lat, lon) -> Point.fromLngLat(lon, lat) }.let { it + it.first() }
    Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
        // A hexagon with only pings is coloured as if signal matched its best ping.
        val snr = cell.bestSNR?.toDouble() ?: when {
            cell.pingsReached > 0 -> 8.0
            cell.pingsRelayed > 0 -> 0.0
            else -> -15.0
        }
        addNumberProperty("snr", snr)
        // Outlined by network: green Meshtastic, blue MeshCore, white where both were heard.
        addStringProperty("fw", CoverageNetwork.key(cell.firmwares))
    }
})

/**
 * The private layer: this phone's recordings and pings binned into hexagons.
 * A hexagon pinged from is coloured by the best answer there (green answered,
 * yellow repeated, red nothing); one only heard in, by its best signal.
 */
private fun myCellFeatures(cells: List<CoverageShare.MapCell>, network: Firmware?) = FeatureCollection.fromFeatures(cells.map { cell ->
    val ring = cell.corners.map { (lat, lon) -> Point.fromLngLat(lon, lat) }.let { it + it.first() }
    Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
        addNumberProperty("snr", (cell.bestSNR ?: -10f).toDouble())
        cell.bestPing(network)?.let { addStringProperty("outcome", PingColors.key(it)) }
        addStringProperty("fw", CoverageNetwork.key(cell.firmwares))
    }
})

/** The hexagons sharing would publish: from the demo in debug screenshots, else from :mesh's coverage store. */
@Composable
private fun rememberSharedCells(enabled: Boolean, network: CoverageNetwork): List<CoverageShare.Cell> {
    if (!enabled) return emptyList()
    DemoData.sharedCells?.let { all ->
        return remember(all.size, network) { all.filter { c -> c.firmwares.any { network.includes(it) } } }
    }
    if (!Mesh.isInitialized) return emptyList()
    val store = Mesh.coverage
    val samples by store.samplesFlow.collectAsState()
    val pings by store.pingsFlow.collectAsState()
    val zones by store.zonesFlow.collectAsState()
    // Hiding a radio leaves its samples out of the cells, so redraw.
    val hidden by Mesh.radios.hidden.flow.collectAsState()
    return remember(samples, pings, zones, network, hidden) { store.sharedCells(network.firmware) }
}

private fun hex(c: Color): String = String.format("#%06X", c.toArgb() and 0xFFFFFF)

private fun firmwareColor(): Expression =
    Expression.match(get("fw"), color(RadioColors.meshtastic.toArgb()), stop("meshcore", color(RadioColors.meshcore.toArgb())))

/** A hexagon's outline: its network's colour, white when both networks were heard there. */
private fun cellOutlineColor(): Expression =
    Expression.match(get("fw"), color(RadioColors.meshtastic.toArgb()),
        stop("meshcore", color(RadioColors.meshcore.toArgb())), stop("both", color(android.graphics.Color.WHITE)))

/** Known radios under coverage, under live nodes, under pins. */
private fun addLayers(style: Style) {
    listOf(SRC_QUAKES, SRC_KNOWN, SRC_CELLS, SRC_APRS, SRC_NODES, SRC_PINS, SRC_FAVORITES, SRC_GOTO, SRC_ROUTE, SRC_AIRCRAFT, SRC_PATH, SRC_POTA, SRC_NVIS).forEach {
        style.addSource(GeoJsonSource(it, FeatureCollection.fromFeatures(emptyList())))
    }
    style.addLayer(CircleLayer(SRC_AIRCRAFT, SRC_AIRCRAFT).withProperties(
        PropertyFactory.circleRadius(5f),
        PropertyFactory.circleColor(Expression.match(get("kind"), color(android.graphics.Color.rgb(48, 176, 199)),
            stop(1, color(android.graphics.Color.rgb(10, 132, 255))), stop(2, color(android.graphics.Color.rgb(255, 69, 58))), stop(3, color(android.graphics.Color.rgb(175, 82, 222))))),
        PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE), PropertyFactory.circleStrokeWidth(1.5f)))
    style.addLayer(SymbolLayer("sdr-aircraft-names", SRC_AIRCRAFT).withProperties(
        PropertyFactory.textField(get("name")), PropertyFactory.textFont(LABEL_FONT), PropertyFactory.textSize(11f),
        PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP), PropertyFactory.textOffset(arrayOf(0f, 0.8f)),
        PropertyFactory.textColor(android.graphics.Color.rgb(48, 176, 199)), PropertyFactory.textHaloColor(android.graphics.Color.BLACK),
        PropertyFactory.textHaloWidth(1.5f), PropertyFactory.textOptional(true)))
    style.addLayer(LineLayer("nvis-ring", SRC_NVIS).withProperties(
        PropertyFactory.lineColor(android.graphics.Color.rgb(175, 82, 222)), PropertyFactory.lineWidth(3f), PropertyFactory.lineDasharray(arrayOf(3f, 2f))))
    style.addLayer(CircleLayer(SRC_POTA, SRC_POTA).withProperties(
        PropertyFactory.circleRadius(Expression.switchCase(eq(get("live"), literal(1)), literal(7f), literal(4f))),
        PropertyFactory.circleColor(Expression.switchCase(eq(get("live"), literal(1)), color(android.graphics.Color.rgb(255, 149, 0)), color(android.graphics.Color.rgb(52, 199, 89)))),
        PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE), PropertyFactory.circleStrokeWidth(1.5f)))
    style.addLayer(SymbolLayer("pota-names", SRC_POTA).withProperties(
        PropertyFactory.textField(get("name")), PropertyFactory.textFont(LABEL_FONT), PropertyFactory.textSize(10f),
        PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP), PropertyFactory.textOffset(arrayOf(0f, 0.8f)),
        PropertyFactory.textColor(android.graphics.Color.rgb(52, 199, 89)), PropertyFactory.textHaloColor(android.graphics.Color.BLACK),
        PropertyFactory.textHaloWidth(1.5f), PropertyFactory.textOptional(true)))
    style.addLayer(LineLayer("radio-path-line", SRC_PATH).withProperties(
        PropertyFactory.lineColor(Expression.match(get("v"), color(android.graphics.Color.rgb(229, 72, 77)),
            stop("clear", color(android.graphics.Color.rgb(52, 199, 89))), stop("marginal", color(android.graphics.Color.rgb(255, 149, 0))))),
        PropertyFactory.lineWidth(5f), PropertyFactory.lineDasharray(arrayOf(2f, 1.5f))))
    style.addLayer(LineLayer("route-line", SRC_ROUTE).withProperties(
        PropertyFactory.lineColor(android.graphics.Color.rgb(10, 132, 255)), PropertyFactory.lineWidth(6f),
        PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)))
    style.addLayer(LineLayer("goto-line", SRC_GOTO).withProperties(
        PropertyFactory.lineColor(android.graphics.Color.rgb(52, 199, 89)), PropertyFactory.lineWidth(4f),
        PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f))).withFilter(Expression.eq(Expression.geometryType(), "LineString")))
    style.addLayer(CircleLayer("goto-target", SRC_GOTO).withProperties(
        PropertyFactory.circleRadius(11f), PropertyFactory.circleColor(android.graphics.Color.TRANSPARENT),
        PropertyFactory.circleStrokeColor(android.graphics.Color.rgb(52, 199, 89)), PropertyFactory.circleStrokeWidth(4f))
        .withFilter(Expression.eq(Expression.geometryType(), "Point")))
    style.addLayer(CircleLayer("favorite-dots", SRC_FAVORITES).withProperties(
        PropertyFactory.circleRadius(8f), PropertyFactory.circleColor(android.graphics.Color.rgb(255, 204, 26)),
        PropertyFactory.circleStrokeColor(android.graphics.Color.BLACK), PropertyFactory.circleStrokeWidth(2f)))
    style.addLayer(SymbolLayer("favorite-names", SRC_FAVORITES).withProperties(
        PropertyFactory.textField(get("name")), PropertyFactory.textFont(LABEL_FONT), PropertyFactory.textSize(12f),
        PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP), PropertyFactory.textOffset(arrayOf(0f, 0.9f)),
        PropertyFactory.textColor(android.graphics.Color.rgb(255, 204, 26)), PropertyFactory.textHaloColor(android.graphics.Color.BLACK),
        PropertyFactory.textHaloWidth(1.5f), PropertyFactory.textOptional(true)))
    // Earthquakes: rings sized and coloured by magnitude, under everything else.
    val quakeColor = Expression.switchCase(eq(get("big"), literal(2)), color(android.graphics.Color.rgb(229, 72, 77)),
        eq(get("big"), literal(1)), color(android.graphics.Color.rgb(255, 149, 0)), color(android.graphics.Color.rgb(255, 214, 10)))
    style.addLayer(CircleLayer(SRC_QUAKES, SRC_QUAKES).withProperties(
        PropertyFactory.circleRadius(Expression.toNumber(get("r"))),
        PropertyFactory.circleColor(quakeColor),
        PropertyFactory.circleOpacity(0.35f),
        PropertyFactory.circleStrokeColor(quakeColor),
        PropertyFactory.circleStrokeWidth(1.5f),
    ))
    style.addLayer(SymbolLayer("quake-labels", SRC_QUAKES).withProperties(
        PropertyFactory.textField(get("label")),
        PropertyFactory.textFont(LABEL_FONT),
        PropertyFactory.textSize(11f),
        PropertyFactory.textColor(android.graphics.Color.rgb(255, 149, 0)),
        PropertyFactory.textHaloColor(android.graphics.Color.BLACK),
        PropertyFactory.textHaloWidth(1.5f),
        PropertyFactory.textOptional(true),
    ))
    style.addLayer(CircleLayer(SRC_KNOWN, SRC_KNOWN).withProperties(
        PropertyFactory.circleColor(firmwareColor()),
        PropertyFactory.circleRadius(Expression.switchCase(eq(get("big"), literal(1)), literal(6.5f), literal(4f))),
        PropertyFactory.circleStrokeColor(Expression.switchCase(eq(get("big"), literal(1)), color(android.graphics.Color.WHITE),
            color(android.graphics.Color.argb(89, 0, 0, 0)))),
        PropertyFactory.circleStrokeWidth(Expression.switchCase(eq(get("big"), literal(1)), literal(2f), literal(0.5f))),
        PropertyFactory.circleOpacity(0.9f),
    ))
    // Coverage hexagons (what sharing would publish, or the private ones): coloured by the best
    // drive-test answer where there was a ping, else by best signal.
    style.addLayer(FillLayer(SRC_CELLS, SRC_CELLS).withProperties(
        PropertyFactory.fillColor(Expression.switchCase(Expression.has("outcome"),
            Expression.match(get("outcome"), color(PingColors.none.toArgb()),
                stop("reached", color(PingColors.reached.toArgb())), stop("relayed", color(PingColors.relayed.toArgb()))),
            snrColor())),
        PropertyFactory.fillOpacity(0.45f),
    ))
    style.addLayer(LineLayer("coverage-outline", SRC_CELLS).withProperties(
        PropertyFactory.lineColor(cellOutlineColor()),
        PropertyFactory.lineWidth(1.6f),
    ))
    // APRS stations: purple (teal for weather, red in an emergency), callsign beside them.
    style.addLayer(CircleLayer(SRC_APRS, SRC_APRS).withProperties(
        PropertyFactory.circleRadius(7f),
        PropertyFactory.circleColor(Expression.switchCase(eq(get("sos"), literal(1)), color(android.graphics.Color.rgb(229, 72, 77)),
            eq(get("wx"), literal(1)), color(android.graphics.Color.rgb(48, 176, 199)), color(android.graphics.Color.rgb(175, 82, 222)))),
        PropertyFactory.circleStrokeColor(color(android.graphics.Color.WHITE)),
        PropertyFactory.circleStrokeWidth(2f),
    ))
    style.addLayer(SymbolLayer("aprs-labels", SRC_APRS).withProperties(
        PropertyFactory.textField(get("name")),
        PropertyFactory.textFont(LABEL_FONT),
        PropertyFactory.textSize(11f),
        PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
        PropertyFactory.textColor(android.graphics.Color.rgb(200, 150, 240)),
        PropertyFactory.textHaloColor(android.graphics.Color.BLACK),
        PropertyFactory.textHaloWidth(1.5f),
        PropertyFactory.textOptional(true),
    ))
    // Radios heard directly: the main layer. Network colour, repeaters ringed in white.
    // Radios known only through the mesh are smaller and faded.
    val direct = eq(get("direct"), literal(1))
    style.addLayer(CircleLayer(SRC_NODES, SRC_NODES).withProperties(
        PropertyFactory.circleRadius(Expression.switchCase(direct, literal(11f), literal(6f))),
        PropertyFactory.circleOpacity(Expression.switchCase(direct, literal(1f), literal(0.55f))),
        PropertyFactory.circleStrokeOpacity(Expression.switchCase(direct, literal(1f), literal(0.55f))),
        PropertyFactory.circleColor(firmwareColor()),
        PropertyFactory.circleStrokeColor(Expression.switchCase(eq(get("rep"), literal(1)), color(android.graphics.Color.WHITE),
            color(android.graphics.Color.BLACK))),
        PropertyFactory.circleStrokeWidth(Expression.switchCase(eq(get("rep"), literal(1)), literal(3.5f), literal(2f))),
    ))
    style.addLayer(SymbolLayer("mesh-node-labels", SRC_NODES).withProperties(
        PropertyFactory.textField(get("name")),
        PropertyFactory.textFont(LABEL_FONT),
        PropertyFactory.textSize(12f),
        PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
        PropertyFactory.textColor(android.graphics.Color.WHITE),
        PropertyFactory.textHaloColor(android.graphics.Color.BLACK),
        PropertyFactory.textHaloWidth(1.5f),
        PropertyFactory.textOptional(true),
    ))
    // My drawings: above the radios, below the pins.
    addShapeLayers(style, LABEL_FONT)
    style.addLayer(CircleLayer(SRC_PINS, SRC_PINS).withProperties(
        PropertyFactory.circleRadius(9f),
        PropertyFactory.circleColor(Expression.toColor(get("color"))),
        PropertyFactory.circleStrokeColor(color(android.graphics.Color.WHITE)),
        PropertyFactory.circleStrokeWidth(2.5f),
    ))
    style.addLayer(SymbolLayer("pin-labels", SRC_PINS).withProperties(
        PropertyFactory.textField(get("name")),
        PropertyFactory.textFont(LABEL_FONT),
        PropertyFactory.textSize(13f),
        PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
        PropertyFactory.textColor(android.graphics.Color.WHITE),
        PropertyFactory.textHaloColor(android.graphics.Color.BLACK),
        PropertyFactory.textHaloWidth(1.5f),
        PropertyFactory.textOptional(true),
    ))
}

@Composable
private fun rememberMapView(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

@Composable
private fun MapButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        modifier = Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun CheckItem(label: String, icon: ImageVector, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = { if (checked) Icon(Icons.Filled.Check, contentDescription = "On") },
        onClick = onClick,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f), RoundedCornerShape(12.dp)).padding(10.dp)) {
        content()
    }
}

@Composable
private fun LegendDot(color: Color, label: String, size: Int = 10) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(size.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CoverageLegend(all: CoverageData, coverage: CoverageData, mode: CoverageLayer, network: CoverageNetwork, cellCount: Int) {
    val shared = mode == CoverageLayer.SHARED
    val counts = Firmware.entries.associateWith { f -> all.samples.count { it.firmware == f } + all.pings.count { it.firmware == f } }
    val bothNetworks = counts.values.count { it > 0 } > 1
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (shared) "Would be shared" else "Where I drove (private)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CoverageColors.stops.forEach { (_, c) -> Box(Modifier.size(10.dp).background(c, CircleShape)) }
                Text(tr("weak → strong"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The ring (or hexagon outline) says which network: shown once both have data, or when filtered.
            if (bothNetworks || network != CoverageNetwork.BOTH) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Firmware.entries.filter { network.includes(it) }.forEach { f ->
                        RingDot(RadioColors.color(f), "${if (f == Firmware.MESHCORE) "MeshCore" else "Meshtastic"} ${counts[f] ?: 0}")
                    }
                    if (shared && network == CoverageNetwork.BOTH) RingDot(Color.White, "Both")
                }
            }
            if (shared) {
                Text(if (cellCount == 0) "Nothing to share yet." else "$cellCount hexagons, no exact positions, names or private zones.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!shared && cellCount > 0) {
                Text("$cellCount hexagons along where you drove, about 300 m across. Only you see this. Exact spots are never drawn.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!shared && coverage.pings.isNotEmpty()) {
                Text(tr("Drive-test pings (best per hexagon)"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegendDot(PingColors.reached, "Answered")
                    LegendDot(PingColors.relayed, "Repeated")
                    LegendDot(PingColors.none, "Nothing")
                }
            }
            if (!shared && coverage.samples.isEmpty() && coverage.pings.isEmpty()) {
                Text(if (all.samples.isNotEmpty() || all.pings.isNotEmpty()) "Nothing on this network yet. Change it in Layers."
                    else "Nothing yet. Turn on recording in Coverage settings, connect a radio and move around.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A grey dot ringed in a network's colour, as recordings are drawn. */
@Composable
private fun RingDot(ring: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(11.dp).background(Color.Gray, CircleShape).border(2.dp, ring, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RadioLegend(heard: String?, unidentified: List<UnidentifiedRepeater>, showsMeshKnown: Boolean) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("Mesh radios"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            heard?.let { Text("$it directly", style = MaterialTheme.typography.labelSmall) }
            // Heard as the last hop, but no known radio with that hash is in range: named, never placed.
            if (unidentified.isNotEmpty()) {
                val shown = unidentified.take(4).joinToString(", ") { it.hash }
                val more = if (unidentified.size > 4) " and ${unidentified.size - 4} more" else ""
                Text(if (unidentified.size == 1) unidentified[0].label
                    else "Repeaters $shown$more, not identified", style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot(RadioColors.meshtastic, "Meshtastic")
                LegendDot(RadioColors.meshcore, "MeshCore")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(12.dp).background(Color.Gray, CircleShape).border(2.dp, Color.White, CircleShape))
                Text(tr("Repeater or router"), style = MaterialTheme.typography.labelSmall)
                Box(Modifier.size(7.dp).background(Color.Gray, CircleShape))
                Text(tr("Other"), style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(12.dp).background(Color.Gray, CircleShape).border(2.dp, Color.Black, CircleShape))
                Text(tr("Heard by your radios"), style = MaterialTheme.typography.labelSmall)
            }
            if (showsMeshKnown) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(7.dp).background(Color.Gray.copy(alpha = 0.55f), CircleShape))
                Text(tr("Known on the mesh (relayed)"), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
