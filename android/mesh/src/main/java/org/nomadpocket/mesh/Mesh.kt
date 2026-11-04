// Mesh.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Entry point for :app. Call Mesh.init(context) once from Application.onCreate
// (on the main thread) so the last radio reconnects at launch, as
// MeshService.shared.start() does on iOS. The map reads Mesh.repository.

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
    private var _service: MeshService? = null
    private var _coverage: CoverageStore? = null
    private var _drive: DriveMode? = null
    private var _location: MeshLocation? = null
    private var _transport: MeshBleTransport? = null
    internal var appContext: Context? = null
        private set

    val isInitialized: Boolean get() = _service != null

    /** Radios, chat history and the link. */
    val service: MeshService get() = _service ?: notReady()
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
        val transport = MeshBleTransport(app)
        val dir = File(app.filesDir, "mesh")
        val service = MeshService(transport, MeshStore(dir), prefs, scope,
            location = { location.currentLocation() }, locationDenied = { !location.hasPermission() })
        val coverage = CoverageStore(prefs, File(dir, "coverage.json"), scope,
            location = { location.fix },
            nodeName = { service.nodes[it]?.longName ?: "" },
            useLocation = { if (it) location.begin() else location.end() })
        val drive = DriveMode(service, coverage, prefs, scope, onActiveChanged = {
            if (it) DriveModeService.start(app) else DriveModeService.stop(app)
        })
        _location = location; _transport = transport; _service = service; _coverage = coverage; _drive = drive

        service.onHeard = { coverage.record(it) }
        service.start()
        coverage.start()

        scope.launch {
            combine(service.nodesFlow, service.blockedFlow, service.radioFlow) { nodes, blocked, radio ->
                MeshService.sortedNodes(nodes, radio.myNodeNum, blocked).mapNotNull { it.toMapNode() }
            }.collect { repository.nodesState.value = it }
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
        _transport?.updateAvailability()
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

    /** Radios with a position, excluding your own and blocked ones, most recently heard first. */
    val nodes: StateFlow<List<MapNode>> = nodesState.asStateFlow()
    /** This phone's coverage recordings and drive-test pings (opted-out radios left out). */
    val coverage: StateFlow<CoverageData> = coverageState.asStateFlow()
}

data class MapNode(val num: Long, val name: String, val firmware: Firmware, val latitude: Double, val longitude: Double, val lastHeardMillis: Long?)
enum class Firmware { MESHTASTIC, MESHCORE }
data class CoverageData(val samples: List<CoverageSample>, val pings: List<CoveragePing>)
data class CoverageSample(val latitude: Double, val longitude: Double, val snr: Float?, val firmware: Firmware)
data class CoveragePing(val latitude: Double, val longitude: Double, val outcome: PingOutcome, val firmware: Firmware)
enum class PingOutcome { REACHED, RELAYED, NONE }

/** MeshCore contacts carry a key prefix; Meshtastic nodes don't. */
internal fun MeshNode.toMapNode(): MapNode? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    val fw = if (publicKeyPrefix != null || meshCoreType != null) Firmware.MESHCORE else Firmware.MESHTASTIC
    return MapNode(num, displayName, fw, lat, lon, lastHeardMillis)
}
