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

/**
 * Map regions saved for offline use with MapLibre's OfflineManager. The user picks
 * an area by moving the map; tiles come from OpenFreeMap and stay on the phone.
 */
object OfflineRegions {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
    /** OpenFreeMap's vector tiles stop at zoom 14; MapLibre draws closer zooms from them. */
    const val MAX_ZOOM = 14
    /** Bigger areas take hours and hundreds of MB, so the app asks for a smaller one. */
    const val MAX_TILES = 60_000L

    data class Region(
        val id: Long,
        val name: String,
        val bytes: Long,
        val complete: Boolean,
        val downloading: Boolean,
        val percent: Int,
        val south: Double, val west: Double, val north: Double, val east: Double,
    )

    private val _regions = MutableStateFlow<List<Region>>(emptyList())
    val regions: StateFlow<List<Region>> = _regions.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val live = mutableMapOf<Long, OfflineRegion>()

    fun refresh(context: Context) {
        OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
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

    private fun region(r: OfflineRegion, status: OfflineRegionStatus?): Region {
        val b = r.definition.bounds
        val pct = status?.let {
            if (it.requiredResourceCount > 0) (100 * it.completedResourceCount / it.requiredResourceCount).toInt() else 0
        } ?: 0
        return Region(
            r.id, name(r), status?.completedResourceSize ?: 0, status?.isComplete ?: false,
            status?.downloadState == OfflineRegion.STATE_ACTIVE && status.isComplete.not(), pct,
            b?.latitudeSouth ?: 0.0, b?.longitudeWest ?: 0.0, b?.latitudeNorth ?: 0.0, b?.longitudeEast ?: 0.0,
        )
    }

    private fun update(r: OfflineRegion, status: OfflineRegionStatus) {
        val updated = region(r, status)
        _regions.value = _regions.value.map { if (it.id == r.id) updated else it }.let { list ->
            if (list.any { it.id == r.id }) list else list + updated
        }
    }

    fun download(context: Context, name: String, bounds: LatLngBounds) {
        _error.value = null
        val definition = OfflineTilePyramidRegionDefinition(
            STYLE_URL, bounds, 0.0, MAX_ZOOM.toDouble(), context.resources.displayMetrics.density,
        )
        val metadata = JSONObject().put("name", name).toString().toByteArray()
        OfflineManager.getInstance(context).createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
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
                        _error.value = "This area is too big. Zoom in, then try again."
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) { _error.value = "Couldn't start the download: $error" }
        })
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
