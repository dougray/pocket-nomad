// Mesh.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Entry point for :app. Call Mesh.init(context) once from Application.onCreate
// (on the main thread) so the remembered radios reconnect at launch, as
// MeshService.shared.start() does on iOS. The map reads Mesh.repository,
// which merges both radios (one Meshtastic, one MeshCore) when two are connected.

package org.nomadpocket.mesh

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

// Holds only the application context (never an Activity), so no leak.
@SuppressLint("StaticFieldLeak")
object Mesh {
    private var scope: CoroutineScope? = null
    private var _radios: MeshRadios? = null
    private var _coverage: CoverageStore? = null
    private var _drive: DriveMode? = null
    private var _location: MeshLocation? = null
    private var _transports: List<MeshBleTransport> = emptyList()
    internal var appContext: Context? = null
        private set

    val isInitialized: Boolean get() = _radios != null

    /** Both radio slots, scanning, and the merged views (SOS, nodes). */
    val radios: MeshRadios get() = _radios ?: notReady()
    /** For single-radio callers: the first radio in use, else the Meshtastic slot. */
    val service: MeshService get() = radios.inUse.firstOrNull()?.second ?: radios.link(Firmware.MESHTASTIC)
    /** Coverage recordings, pings and private zones. */
    val coverage: CoverageStore get() = _coverage ?: notReady()
    val drive: DriveMode get() = _drive ?: notReady()
    val location: MeshLocation get() = _location ?: notReady()

    /** Map data. Exists before init (empty), so it is safe to collect at any time. */
    val repository: MeshRepository = MeshRepository()

    private fun notReady(): Nothing = error("Call Mesh.init(context) from Application.onCreate first.")

    fun init(context: Context) {
        if (isInitialized) return
        val app = context.applicationContext
        appContext = app
        val scope = MainScope()
        this.scope = scope
        val prefs = SharedMeshPrefs(app.getSharedPreferences("mesh", Context.MODE_PRIVATE))
        val location = MeshLocation(app)
        // One transport per radio slot (each its own GATT connection) plus one that only scans.
        val scanner = MeshBleTransport(app)
        val slotTransports = Firmware.entries.associateWith { MeshBleTransport(app) }
        val dir = File(app.filesDir, "mesh")
        val radios = MeshRadios.create(prefs, dir, scope, scanner, { slotTransports.getValue(it) },
            location = { location.currentLocation() }, locationDenied = { !location.hasPermission() })
        val coverage = CoverageStore(prefs, File(dir, "coverage.json"), scope,
            location = { location.fix },
            nodeName = { radios.nodeName(it) },
            useLocation = { if (it) location.begin() else location.end() })
        val drive = DriveMode({ radios.inUse }, coverage, prefs, scope, onActiveChanged = {
            if (it) DriveModeService.start(app) else DriveModeService.stop(app)
        })
        _location = location; _transports = listOf(scanner) + slotTransports.values; _radios = radios
        _coverage = coverage; _drive = drive

        // Both radios feed the coverage scanner at the same time; each MeshHeard carries its firmware.
        radios.links.values.forEach { it.onHeard = { heard -> coverage.record(heard) } }
        radios.start()
        coverage.start()

        scope.launch {
            val perLink = radios.links.map { (fw, link) ->
                combine(link.nodesFlow, link.blockedFlow, link.radioFlow) { nodes, blocked, radio ->
                    MeshService.sortedNodes(nodes, radio.myNodeNum, blocked).mapNotNull { it.toMapNode(fw) }
                }
            }
            combine(perLink) { lists -> lists.flatMap { it }.sortedByDescending { it.lastHeardMillis ?: Long.MIN_VALUE } }
                .collect { repository.nodesState.value = it }
        }
        scope.launch {
            combine(coverage.samplesFlow, coverage.pingsFlow) { samples, pings ->
                CoverageData(
                    samples.filterNot { coverage.optedOut(it.node) }
                        .map { CoverageSample(it.latitude, it.longitude, it.snr, it.firmware) },
                    pings.map { CoveragePing(it.latitude, it.longitude, it.outcome, it.firmware) },
                )
            }.collect { repository.coverageState.value = it }
        }
        (app as? Application)?.let { keepScreenOnDuringDrives(it, scope, drive) }
    }

    /** Call after the user grants Bluetooth or location permission. */
    fun permissionsChanged() {
        _transports.forEach { it.updateAvailability() }
        _location?.refresh()
    }

    /** Drive mode's "Keep screen on", for whichever activity is showing. */
    private fun keepScreenOnDuringDrives(app: Application, scope: CoroutineScope, drive: DriveMode) {
        var resumed: Activity? = null
        var on = false
        fun apply() {
            val w = resumed?.window ?: return
            if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumed = activity; apply() }
            override fun onActivityPaused(activity: Activity) { if (resumed === activity) resumed = null }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        scope.launch {
            combine(drive.stateFlow, drive.keepScreenOnFlow) { s, keep -> s.active && keep }.collect { on = it; apply() }
        }
    }
}

/** What the map shows from the mesh. */
class MeshRepository internal constructor() {
    internal val nodesState = MutableStateFlow<List<MapNode>>(emptyList())
    internal val coverageState = MutableStateFlow(CoverageData(emptyList(), emptyList()))

    /**
     * Radios with a position heard by either of your radios, excluding your own
     * and blocked ones, most recently heard first. Each carries its network.
     */
    val nodes: StateFlow<List<MapNode>> = nodesState.asStateFlow()
    /** This phone's coverage recordings and drive-test pings from both radios, each tagged with its firmware (opted-out radios left out). */
    val coverage: StateFlow<CoverageData> = coverageState.asStateFlow()
}

data class MapNode(val num: Long, val name: String, val firmware: Firmware, val latitude: Double, val longitude: Double, val lastHeardMillis: Long?)
enum class Firmware { MESHTASTIC, MESHCORE }
data class CoverageData(val samples: List<CoverageSample>, val pings: List<CoveragePing>)
data class CoverageSample(val latitude: Double, val longitude: Double, val snr: Float?, val firmware: Firmware)
data class CoveragePing(val latitude: Double, val longitude: Double, val outcome: PingOutcome, val firmware: Firmware)
enum class PingOutcome { REACHED, RELAYED, NONE }

/** The node's network is the one of the radio that heard it. */
internal fun MeshNode.toMapNode(firmware: Firmware): MapNode? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    return MapNode(num, displayName, firmware, lat, lon, lastHeardMillis)
}
