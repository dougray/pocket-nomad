package org.nomadpocket.app.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Map regions saved for offline use with MapLibre's OfflineManager. The user picks
 * an area by moving the map, or taps "streets around me"; tiles come from
 * OpenFreeMap and stay on the phone.
 */
object OfflineRegions {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
    /** OpenFreeMap's vector tiles stop at zoom 14; MapLibre draws closer zooms from them. */
    const val MAX_ZOOM = 14
    /** Bigger areas take hours and hundreds of MB, so the app asks for a smaller one. */
    const val MAX_TILES = 60_000L
    /**
     * MapLibre refuses to store more than 6,000 tiles by default. Room for a
     * "streets around me" pair plus a few areas, while still stopping a runaway download.
     */
    const val TILE_COUNT_LIMIT = 250_000L
    /** Metadata "kind" of the two regions a "streets around me" download makes. */
    const val KIND_STREETS = "streets"

    data class Region(
        val id: Long,
        val name: String,
        val bytes: Long,
        val complete: Boolean,
        val downloading: Boolean,
        val percent: Int,
        val south: Double, val west: Double, val north: Double, val east: Double,
        /** [KIND_STREETS] for "streets around me" regions, else null. */
        val kind: String? = null,
    ) {
        val isStreets: Boolean get() = kind == KIND_STREETS
    }

    private val _regions = MutableStateFlow<List<Region>>(emptyList())
    val regions: StateFlow<List<Region>> = _regions.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val live = mutableMapOf<Long, OfflineRegion>()

    private fun manager(context: Context): OfflineManager =
        OfflineManager.getInstance(context).also { it.setOfflineMapboxTileCountLimit(TILE_COUNT_LIMIT) }

    fun refresh(context: Context) {
        manager(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = offlineRegions?.toList() ?: emptyList()
                list.forEach { live[it.id] = it }
                _regions.value = list.map { region(it, null) }
                list.forEach { r ->
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) { status?.let { update(r, it) } }
                        override fun onError(error: String?) {}
                    })
                }
            }

            override fun onError(error: String) { _error.value = error }
        })
    }

    private fun name(r: OfflineRegion): String =
        runCatching { JSONObject(String(r.metadata)).getString("name") }.getOrDefault("Map region")

    private fun kind(r: OfflineRegion): String? =
        runCatching { JSONObject(String(r.metadata)).optString("kind").ifEmpty { null } }.getOrNull()

    private fun region(r: OfflineRegion, status: OfflineRegionStatus?): Region {
        val b = r.definition.bounds
        val pct = status?.let {
            if (it.requiredResourceCount > 0) (100 * it.completedResourceCount / it.requiredResourceCount).toInt() else 0
        } ?: 0
        return Region(
            r.id, name(r), status?.completedResourceSize ?: 0, status?.isComplete ?: false,
            status?.downloadState == OfflineRegion.STATE_ACTIVE && status.isComplete.not(), pct,
            b?.latitudeSouth ?: 0.0, b?.longitudeWest ?: 0.0, b?.latitudeNorth ?: 0.0, b?.longitudeEast ?: 0.0,
            kind(r),
        )
    }

    private fun update(r: OfflineRegion, status: OfflineRegionStatus) {
        val updated = region(r, status)
        _regions.value = _regions.value.map { if (it.id == r.id) updated else it }.let { list ->
            if (list.any { it.id == r.id }) list else list + updated
        }
    }

    fun download(context: Context, name: String, bounds: LatLngBounds, minZoom: Int = 0, maxZoom: Int = MAX_ZOOM, kind: String? = null) {
        _error.value = null
        val definition = OfflineTilePyramidRegionDefinition(
            STYLE_URL, bounds, minZoom.toDouble(), maxZoom.toDouble(), context.resources.displayMetrics.density,
        )
        val metadata = JSONObject().put("name", name).apply { kind?.let { put("kind", it) } }.toString().toByteArray()
        manager(context).createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                live[offlineRegion.id] = offlineRegion
                _regions.value = _regions.value + region(offlineRegion, null)
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        update(offlineRegion, status)
                        if (status.isComplete) offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    }

                    override fun onError(error: OfflineRegionError) {
                        _error.value = "Download problem: ${error.message}. It will keep trying while you're online."
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        _error.value = "The phone's offline map is full ($limit tiles). Delete a saved area or pick a smaller one, then try again."
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) { _error.value = "Couldn't start the download: $error" }
        })
    }

    /**
     * "Streets around me": two regions, the wide one (highways, main roads,
     * cities and towns: zoom 0-10) and the detailed one (every street: zoom 11-14).
     */
    fun downloadStreets(context: Context, plan: StreetsArea.Plan) {
        fun bounds(b: StreetsArea.Box) = LatLngBounds.from(b.north, b.east, b.south, b.west)
        download(context, StreetsArea.wideName(plan.wideKm), bounds(plan.wide), 0, StreetsArea.WIDE_MAX_ZOOM, KIND_STREETS)
        download(context, StreetsArea.detailName(plan.detailKm), bounds(plan.detail), StreetsArea.DETAIL_MIN_ZOOM, MAX_ZOOM, KIND_STREETS)
    }

    /** Stops and deletes "streets around me" regions that haven't finished. */
    fun cancelStreets() {
        _regions.value.filter { it.isStreets && !it.complete }.forEach { delete(it.id) }
    }

    fun delete(id: Long) {
        val r = live[id] ?: return
        r.setDownloadState(OfflineRegion.STATE_INACTIVE)
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                live.remove(id)
                _regions.value = _regions.value.filterNot { it.id == id }
            }

            override fun onError(error: String) { _error.value = error }
        })
    }
}

/**
 * The maths for "streets around me": square areas around a point and how many
 * tiles they take. Plain functions, so they are easy to test.
 */
object StreetsArea {
    /** Highways, main roads, cities and towns show up to this zoom. */
    const val WIDE_MAX_ZOOM = 10
    /** Every street from here to OfflineRegions.MAX_ZOOM. */
    const val DETAIL_MIN_ZOOM = 11
    val wideChoicesKm = listOf(100, 200, 400)
    val detailChoicesKm = listOf(25, 50, 100)
    const val DEFAULT_WIDE_KM = 400
    const val DEFAULT_DETAIL_KM = 50
    /** Most tiles one "streets around me" download may ask for, both parts together. */
    const val MAX_TILES = 150_000L

    data class Box(val south: Double, val west: Double, val north: Double, val east: Double)

    data class Plan(val wideKm: Int, val detailKm: Int, val wide: Box, val detail: Box, val wideTiles: Long, val detailTiles: Long) {
        val tiles: Long get() = wideTiles + detailTiles
        /** Style, fonts and icons are shared, so only counted once. */
        val bytes: Long get() = TileMath.estimateBytes(tiles)
        val tooBig: Boolean get() = tiles > MAX_TILES
    }

    /** A square reaching [radiusKm] north, south, east and west of the point, kept on the map. */
    fun square(lat: Double, lon: Double, radiusKm: Double): Box {
        val dLat = radiusKm / 110.574
        val dLon = radiusKm / (111.320 * max(cos(Math.toRadians(lat)), 0.01))
        return Box(
            max(lat - dLat, -85.0), max(lon - dLon, -180.0),
            min(lat + dLat, 85.0), min(lon + dLon, 180.0),
        )
    }

    fun tiles(b: Box, minZoom: Int, maxZoom: Int): Long = TileMath.tileCount(b.south, b.west, b.north, b.east, minZoom, maxZoom)

    fun plan(lat: Double, lon: Double, wideKm: Int, detailKm: Int): Plan {
        val wide = square(lat, lon, wideKm.toDouble())
        val detail = square(lat, lon, detailKm.toDouble())
        return Plan(wideKm, detailKm, wide, detail,
            tiles(wide, 0, WIDE_MAX_ZOOM), tiles(detail, DETAIL_MIN_ZOOM, OfflineRegions.MAX_ZOOM))
    }

    fun wideName(km: Int) = "Streets · $km km (highways & cities)"
    fun detailName(km: Int) = "Streets · $km km (all streets)"
}
