package org.nomadpocket.app.map

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import kotlin.math.max

/** Camera and first-fix state that outlive the map view when the user switches tabs. */
private object MapMemory {
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
private const val SRC_COVERAGE = "coverage"
private const val SRC_PINGS = "coverage-ping-source"
private const val SRC_NODES = "mesh-nodes"
private const val SRC_PINS = "pins"
private const val SRC_CELLS = "coverage-cells"
private val LABEL_FONT = arrayOf("Noto Sans Regular")

@Composable
fun MapScreen(onCoverageSettings: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("map", Context.MODE_PRIVATE) }
    var showNodes by remember { mutableStateOf(prefs.getBoolean("showNodes", true)) }
    var showKnown by remember { mutableStateOf(prefs.getBoolean("showKnown", true)) }
    var coverageMode by remember { mutableStateOf(CoverageLayer.from(prefs.getString("coverage", null))) }
    var pins by remember { mutableStateOf(MapPin.load(context)) }
    fun savePins(new: List<MapPin>) {
        pins = new
        MapPin.save(context, new)
    }

    val location = rememberLocation()
    val permitted by LocationService.permitted.collectAsState()
    val meshNodes by Mesh.repository.nodes.collectAsState()
    val demoNodes by DemoData.nodesFlow.collectAsState()
    val meshCoverage by Mesh.repository.coverage.collectAsState()
    val demoCoverage by DemoData.coverageFlow.collectAsState()
    val known by KnownRadioStore.state.collectAsState()
    val regions by OfflineRegions.regions.collectAsState()

    val nodes = if (showNodes) (meshNodes + demoNodes).distinctBy { it.num } else emptyList()
    val coverage: CoverageData = demoCoverage ?: meshCoverage
    val shownCoverage = if (coverageMode == CoverageLayer.RECORDED) coverage else CoverageData(emptyList(), emptyList())
    val shownKnown = if (showKnown) known.radios else emptyList()
    val cells = rememberSharedCells(coverageMode == CoverageLayer.SHARED)

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var pendingPin by remember { mutableStateOf<LatLng?>(null) }
    var showPlaces by remember { mutableStateOf(false) }
    var showKnownSheet by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }
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
                val p = m.projection.toScreenLocation(point)
                val box = RectF(p.x - 36, p.y - 36, p.x + 36, p.y + 36)
                val f = m.queryRenderedFeatures(box, SRC_PINS, SRC_NODES, SRC_KNOWN).firstOrNull()
                tapped = f?.let { feature ->
                    feature.getStringProperty("pid")?.let { id -> pinsRef.value.firstOrNull { it.id == id }?.let { MapTap.Pin(it) } }
                        ?: feature.getNumberProperty("num")?.let { n -> nodesRef.value.firstOrNull { it.num == n.toLong() }?.let { MapTap.Node(it) } }
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

    LaunchedEffect(style, pins) { style?.source(SRC_PINS)?.setGeoJson(pinFeatures(pins)) }
    LaunchedEffect(style, nodes) { style?.source(SRC_NODES)?.setGeoJson(nodeFeatures(nodes)) }
    LaunchedEffect(style, shownKnown) { style?.source(SRC_KNOWN)?.setGeoJson(knownFeatures(shownKnown)) }
    LaunchedEffect(style, shownCoverage) {
        style?.source(SRC_COVERAGE)?.setGeoJson(FeatureCollection.fromFeatures(shownCoverage.samples.map { s ->
            Feature.fromGeometry(Point.fromLngLat(s.longitude, s.latitude)).apply { addNumberProperty("snr", (s.snr ?: -10f).toDouble()) }
        }))
        style?.source(SRC_PINGS)?.setGeoJson(FeatureCollection.fromFeatures(shownCoverage.pings.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply { addStringProperty("outcome", PingColors.key(p.outcome)) }
        }))
    }

    LaunchedEffect(style, cells) { style?.source(SRC_CELLS)?.setGeoJson(cellFeatures(cells)) }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize())

        Column(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 56.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MapButton(Icons.Filled.MyLocation, "Show my location") {
                val m = map ?: return@MapButton
                location?.let { m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), max(m.cameraPosition.zoom, 14.0))) }
                runCatching { if (m.locationComponent.isLocationComponentActivated) m.locationComponent.cameraMode = CameraMode.TRACKING }
            }
            MapButton(Icons.AutoMirrored.Filled.List, "Places") { showPlaces = true }
            Box {
                MapButton(Icons.Filled.Layers, "Map layers") { showLayers = true }
                DropdownMenu(expanded = showLayers, onDismissRequest = { showLayers = false }) {
                    CheckItem("Radios I've heard", Icons.Filled.SettingsInputAntenna, showNodes) {
                        showNodes = !showNodes; prefs.edit().putBoolean("showNodes", showNodes).apply()
                    }
                    CheckItem("Known radios (community maps)", Icons.Filled.Contactless, showKnown) {
                        showKnown = !showKnown; prefs.edit().putBoolean("showKnown", showKnown).apply()
                    }
                    DropdownMenuItem(text = { Text("Download known radios…") }, leadingIcon = { Icon(Icons.Filled.Download, null) },
                        onClick = { showLayers = false; showKnownSheet = true })
                    HorizontalDivider()
                    Text("Coverage", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CoverageLayer.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            leadingIcon = { Icon(if (coverageMode == mode) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null) },
                            onClick = { coverageMode = mode; prefs.edit().putString("coverage", mode.id).apply(); showLayers = false },
                        )
                    }
                    DropdownMenuItem(text = { Text("Coverage settings…") }, leadingIcon = { Icon(Icons.Filled.Settings, null) },
                        onClick = { showLayers = false; onCoverageSettings() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Offline maps…") }, leadingIcon = { Icon(Icons.Filled.Map, null) },
                        onClick = { showLayers = false; showOffline = true })
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (coverageMode != CoverageLayer.OFF) CoverageLegend(coverage, coverageMode, cells.size)
            if (nodes.isNotEmpty() || shownKnown.isNotEmpty()) RadioLegend()
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
                    Footnote("Online map. To use it with no signal, save this area in Layers › Offline maps.")
                }
            }
        }
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
        OfflineMapsSheet(visibleBounds = { map?.projection?.visibleRegion?.latLngBounds }, onDismiss = { showOffline = false })
    }
    tapped?.let { t -> TapDetailSheet(t) { tapped = null } }
}

/** The latest pins and nodes, read by the map's tap listener (registered once). */
private class Ref<T>(var value: T)
private val pinsRef = Ref<List<MapPin>>(emptyList())
private val nodesRef = Ref<List<MapNode>>(emptyList())

private fun Style.source(id: String): GeoJsonSource? = getSourceAs(id)

private fun pinFeatures(pins: List<MapPin>) = FeatureCollection.fromFeatures(pins.map { p ->
    Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply {
        addStringProperty("pid", p.id)
        addStringProperty("name", p.name)
        addStringProperty("color", hex(p.kind.color))
    }
})

private fun nodeFeatures(nodes: List<MapNode>) = FeatureCollection.fromFeatures(nodes.map { n ->
    Feature.fromGeometry(Point.fromLngLat(n.longitude, n.latitude)).apply {
        addNumberProperty("num", n.num)
        addStringProperty("name", n.name)
        addStringProperty("fw", if (n.firmware == Firmware.MESHCORE) "meshcore" else "meshtastic")
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
    }
})

/** The hexagons sharing would publish: from the demo in debug screenshots, else from :mesh's coverage store. */
@Composable
private fun rememberSharedCells(enabled: Boolean): List<CoverageShare.Cell> {
    if (!enabled) return emptyList()
    DemoData.sharedCells?.let { return remember(it.size) { it } }
    if (!Mesh.isInitialized) return emptyList()
    val store = Mesh.coverage
    val samples by store.samplesFlow.collectAsState()
    val pings by store.pingsFlow.collectAsState()
    val zones by store.zonesFlow.collectAsState()
    return remember(samples, pings, zones) { store.sharedCells }
}

private fun hex(c: Color): String = String.format("#%06X", c.toArgb() and 0xFFFFFF)

private fun firmwareColor(): Expression =
    Expression.match(get("fw"), color(RadioColors.meshtastic.toArgb()), stop("meshcore", color(RadioColors.meshcore.toArgb())))

/** Known radios under coverage, under live nodes, under pins. */
private fun addLayers(style: Style) {
    listOf(SRC_KNOWN, SRC_CELLS, SRC_COVERAGE, SRC_PINGS, SRC_NODES, SRC_PINS).forEach {
        style.addSource(GeoJsonSource(it, FeatureCollection.fromFeatures(emptyList())))
    }
    style.addLayer(CircleLayer(SRC_KNOWN, SRC_KNOWN).withProperties(
        PropertyFactory.circleColor(firmwareColor()),
        PropertyFactory.circleRadius(Expression.switchCase(eq(get("big"), literal(1)), literal(6.5f), literal(4f))),
        PropertyFactory.circleStrokeColor(Expression.switchCase(eq(get("big"), literal(1)), color(android.graphics.Color.WHITE),
            color(android.graphics.Color.argb(89, 0, 0, 0)))),
        PropertyFactory.circleStrokeWidth(Expression.switchCase(eq(get("big"), literal(1)), literal(2f), literal(0.5f))),
        PropertyFactory.circleOpacity(0.9f),
    ))
    // What sharing would publish: the privacy-filtered hexagons, coloured by best signal.
    style.addLayer(FillLayer(SRC_CELLS, SRC_CELLS).withProperties(
        PropertyFactory.fillColor(snrColor()),
        PropertyFactory.fillOpacity(0.45f),
    ))
    style.addLayer(LineLayer("coverage-outline", SRC_CELLS).withProperties(
        PropertyFactory.lineColor(snrColor()),
        PropertyFactory.lineWidth(1.2f),
    ))
    style.addLayer(CircleLayer("coverage-points", SRC_COVERAGE).withProperties(
        PropertyFactory.circleRadius(6f),
        PropertyFactory.circleColor(snrColor()),
        PropertyFactory.circleOpacity(0.85f),
        PropertyFactory.circleStrokeColor(color(android.graphics.Color.argb(102, 0, 0, 0))),
        PropertyFactory.circleStrokeWidth(1f),
    ))
    // Drive-test pings: larger dots, coloured by what came back.
    style.addLayer(CircleLayer("coverage-pings", SRC_PINGS).withProperties(
        PropertyFactory.circleRadius(9f),
        PropertyFactory.circleColor(Expression.match(get("outcome"), color(PingColors.none.toArgb()),
            stop("reached", color(PingColors.reached.toArgb())), stop("relayed", color(PingColors.relayed.toArgb())))),
        PropertyFactory.circleOpacity(0.9f),
        PropertyFactory.circleStrokeColor(color(android.graphics.Color.WHITE)),
        PropertyFactory.circleStrokeWidth(2f),
    ))
    style.addLayer(CircleLayer(SRC_NODES, SRC_NODES).withProperties(
        PropertyFactory.circleRadius(11f),
        PropertyFactory.circleColor(firmwareColor()),
        PropertyFactory.circleStrokeColor(color(android.graphics.Color.BLACK)),
        PropertyFactory.circleStrokeWidth(2f),
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
private fun CoverageLegend(coverage: CoverageData, mode: CoverageLayer, cellCount: Int) {
    val shared = mode == CoverageLayer.SHARED
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (shared) "Would be shared" else "Heard here (private)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CoverageColors.stops.forEach { (_, c) -> Box(Modifier.size(10.dp).background(c, CircleShape)) }
                Text("weak → strong", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (shared) {
                Text(if (cellCount == 0) "Nothing to share yet." else "$cellCount hexagons, no exact positions, names or private zones.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!shared && coverage.pings.isNotEmpty()) {
                Text("Drive-test pings", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegendDot(PingColors.reached, "Answered")
                    LegendDot(PingColors.relayed, "Repeated")
                    LegendDot(PingColors.none, "Nothing")
                }
            }
            if (!shared && coverage.samples.isEmpty() && coverage.pings.isEmpty()) {
                Text("Nothing yet. Turn on recording in Coverage settings, connect a radio and move around.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RadioLegend() {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Mesh radios", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot(RadioColors.meshtastic, "Meshtastic")
                LegendDot(RadioColors.meshcore, "MeshCore")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(12.dp).background(Color.Gray, CircleShape).border(2.dp, Color.White, CircleShape))
                Text("Repeater or router", style = MaterialTheme.typography.labelSmall)
                Box(Modifier.size(7.dp).background(Color.Gray, CircleShape))
                Text("Other", style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(12.dp).background(Color.Gray, CircleShape).border(2.dp, Color.Black, CircleShape))
                Text("Heard by your radio", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
