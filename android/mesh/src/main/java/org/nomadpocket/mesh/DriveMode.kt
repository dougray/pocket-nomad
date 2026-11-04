// DriveMode.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Drive mode: keeps the coverage scanner running with the screen off or the
// phone in a mount, and (optionally) sends a drive-test ping every few hundred
// metres to map where messages actually get through, not just where others
// are heard. Pings are rate limited so a drive doesn't crowd the mesh.
// Ported from App/Sources/Mesh/DriveMode.swift. The Android side (foreground
// service, GPS) is in DriveModeService; this class is the plain logic.

package org.nomadpocket.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DriveState(
    val active: Boolean = false,
    val startedAtMillis: Long? = null,
    val sent: Int = 0,
    val reached: Int = 0,
    val relayed: Int = 0,
    val unanswered: Int = 0,
    val lastOutcome: PingOutcome? = null,
    val waiting: Boolean = false,
    /** Why pings aren't going out right now, for the status line. */
    val pingNote: String? = null,
)

class DriveMode(
    private val mesh: MeshService,
    private val coverage: CoverageStore,
    private val prefs: MeshPrefs,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Starts (true) or stops (false) the foreground service and background GPS. */
    private val onActiveChanged: (Boolean) -> Unit = {},
) {
    companion object {
        /** At most one ping per 45 s, and only after moving 300 m (about one map hexagon). */
        const val MIN_INTERVAL_MILLIS = 45_000L
        const val MIN_DISTANCE_METRES = 300.0
        /** Drive mode turns itself off after this long, in case it is forgotten. */
        const val MAX_DURATION_MILLIS = 4 * 3600 * 1000L
        const val TICK_MILLIS = 5_000L
    }

    private val _state = MutableStateFlow(DriveState())
    val stateFlow: StateFlow<DriveState> = _state.asStateFlow()
    val state: DriveState get() = _state.value

    private val _pinging = MutableStateFlow(prefs.getBoolean(Keys.PINGING, true))
    private val _target = MutableStateFlow(prefs.getLong(Keys.TARGET, 0).takeIf { it != 0L })
    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(Keys.SCREEN, true))
    val pingingFlow: StateFlow<Boolean> = _pinging.asStateFlow()
    val targetFlow: StateFlow<Long?> = _target.asStateFlow()
    val keepScreenOnFlow: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    var pinging: Boolean
        get() = _pinging.value
        set(v) { _pinging.value = v; prefs.putBoolean(Keys.PINGING, v) }

    /** Radio to ping; null means "any radio that repeats it" (Meshtastic only). */
    var target: Long?
        get() = _target.value
        set(v) { _target.value = v; prefs.putLong(Keys.TARGET, v ?: 0) }

    var keepScreenOn: Boolean
        get() = _keepScreenOn.value
        set(v) { _keepScreenOn.value = v; prefs.putBoolean(Keys.SCREEN, v) }

    private var loop: Job? = null
    private var lastPing: Pair<Long, Fix>? = null
    private var coverageWasOn = false

    private object Keys {
        const val PINGING = "drive.pinging"
        const val TARGET = "drive.target"
        const val SCREEN = "drive.keepScreenOn"
    }

    fun start() {
        if (state.active) return
        _state.value = DriveState(active = true, startedAtMillis = now())
        lastPing = null
        coverageWasOn = coverage.recording
        coverage.recording = true
        onActiveChanged(true)
        loop = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MILLIS)
            }
        }
    }

    fun stop() {
        if (!state.active) return
        _state.value = state.copy(active = false, pingNote = null, waiting = false)
        loop?.cancel()
        loop = null
        onActiveChanged(false)
        if (!coverageWasOn) coverage.recording = false
    }

    /** One step of the loop; public so tests can drive it. */
    fun tick() {
        val startedAt = state.startedAtMillis
        if (startedAt != null && now() - startedAt > MAX_DURATION_MILLIS) { stop(); return }
        if (!pinging) { note(null); return }
        if (mesh.connection !is MeshConnectionState.Connected) { note("Waiting for the radio"); return }
        val target = target
        if (mesh.firmware == Firmware.MESHCORE && target == null) { note("MeshCore pings need a radio to ping"); return }
        if (target != null && mesh.nodes[target] == null) { note("The radio to ping isn't known on this mesh"); return }
        val fix = coverage.freshFix() ?: run { note("Waiting for GPS"); return }
        note(null)
        if (state.waiting) return
        lastPing?.let { (date, loc) ->
            if (now() - date < MIN_INTERVAL_MILLIS ||
                CoverageShare.distance(loc.latitude, loc.longitude, fix.latitude, fix.longitude) < MIN_DISTANCE_METRES) return
        }
        val firmware = mesh.firmware
        val sentOK = mesh.ping(target) { outcome ->
            val s = state
            _state.value = s.copy(
                waiting = false, lastOutcome = outcome,
                reached = s.reached + if (outcome == PingOutcome.REACHED) 1 else 0,
                relayed = s.relayed + if (outcome == PingOutcome.RELAYED) 1 else 0,
                unanswered = s.unanswered + if (outcome == PingOutcome.NONE) 1 else 0,
            )
            coverage.recordPing(outcome, fix, firmware)
        }
        if (sentOK) {
            _state.value = state.copy(waiting = true, sent = state.sent + 1)
            lastPing = now() to fix
        }
    }

    private fun note(text: String?) {
        if (state.pingNote != text) _state.value = state.copy(pingNote = text)
    }
}
