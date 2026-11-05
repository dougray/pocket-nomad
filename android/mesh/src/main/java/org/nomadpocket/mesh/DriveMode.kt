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
//
// With two radios connected, each one pings on its own network with its own
// target and rate limit, staggered so they don't transmit in the same moment.
// Each ping is recorded with the firmware of the radio that sent it.

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
    /** Totals over every radio. */
    val sent: Int = 0,
    val reached: Int = 0,
    val relayed: Int = 0,
    val unanswered: Int = 0,
    val lastOutcome: PingOutcome? = null,
    /** True while any radio waits for a ping's answer. */
    val waiting: Boolean = false,
    /** Why pings aren't going out right now, for the status line (names the radio when there are two). */
    val pingNote: String? = null,
    /** The same, per radio. */
    val radios: Map<Firmware, RadioDriveState> = emptyMap(),
)

/** One radio's share of a drive. */
data class RadioDriveState(
    val sent: Int = 0,
    val reached: Int = 0,
    val relayed: Int = 0,
    val unanswered: Int = 0,
    val lastOutcome: PingOutcome? = null,
    val waiting: Boolean = false,
    val note: String? = null,
)

class DriveMode(
    /** The radios in use, by firmware (MeshRadios.inUse in the app). */
    private val radios: () -> List<Pair<Firmware, MeshService>>,
    private val coverage: CoverageStore,
    private val prefs: MeshPrefs,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Starts (true) or stops (false) the foreground service and background GPS. */
    private val onActiveChanged: (Boolean) -> Unit = {},
) {
    /** One radio only (tests, and the single-radio behaviour). */
    constructor(
        mesh: MeshService, coverage: CoverageStore, prefs: MeshPrefs, scope: CoroutineScope,
        now: () -> Long = System::currentTimeMillis, onActiveChanged: (Boolean) -> Unit = {},
    ) : this({ listOf(mesh.firmware to mesh) }, coverage, prefs, scope, now, onActiveChanged)

    companion object {
        /** At most one ping per 45 s, and only after moving 300 m (about one map hexagon). */
        const val MIN_INTERVAL_MILLIS = 45_000L
        const val MIN_DISTANCE_METRES = 300.0
        /** With two radios, at least this long between one radio's ping and the other's. */
        const val STAGGER_MILLIS = 15_000L
        /** Drive mode turns itself off after this long, in case it is forgotten. */
        const val MAX_DURATION_MILLIS = 4 * 3600 * 1000L
        const val TICK_MILLIS = 5_000L

        private const val LEGACY_TARGET = "drive.target"
        private fun targetKey(fw: Firmware) = "drive.target.${fw.rawValue}"

        /** Moves the single-radio ping target to that radio's firmware (MeshRadios.migrate). */
        internal fun migrateTarget(prefs: MeshPrefs, firmware: Firmware) {
            if (!prefs.contains(LEGACY_TARGET)) return
            val t = prefs.getLong(LEGACY_TARGET, 0)
            if (!prefs.contains(targetKey(firmware))) prefs.putLong(targetKey(firmware), t)
            prefs.remove(LEGACY_TARGET)
        }
    }

    private val _state = MutableStateFlow(DriveState())
    val stateFlow: StateFlow<DriveState> = _state.asStateFlow()
    val state: DriveState get() = _state.value

    private val _pinging = MutableStateFlow(prefs.getBoolean(Keys.PINGING, true))
    private val _targets = MutableStateFlow(Firmware.entries.associateWith { fw ->
        // A single-radio target not yet migrated still counts for that radio.
        (prefs.getLong(targetKey(fw), 0).takeIf { it != 0L } ?: prefs.getLong(LEGACY_TARGET, 0)).takeIf { it != 0L }
    })
    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(Keys.SCREEN, true))
    val pingingFlow: StateFlow<Boolean> = _pinging.asStateFlow()
    /** Radio to ping on each network; null means "any radio that repeats it" (Meshtastic only). */
    val targetsFlow: StateFlow<Map<Firmware, Long?>> = _targets.asStateFlow()
    val keepScreenOnFlow: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    var pinging: Boolean
        get() = _pinging.value
        set(v) { _pinging.value = v; prefs.putBoolean(Keys.PINGING, v) }

    fun target(firmware: Firmware): Long? = _targets.value[firmware]

    fun setTarget(firmware: Firmware, node: Long?) {
        _targets.value = _targets.value + (firmware to node)
        prefs.putLong(targetKey(firmware), node ?: 0)
    }

    /** The target of the first radio in use (single-radio callers). */
    var target: Long?
        get() = target(radios().firstOrNull()?.first ?: Firmware.MESHTASTIC)
        set(v) { setTarget(radios().firstOrNull()?.first ?: Firmware.MESHTASTIC, v) }

    var keepScreenOn: Boolean
        get() = _keepScreenOn.value
        set(v) { _keepScreenOn.value = v; prefs.putBoolean(Keys.SCREEN, v) }

    private var loop: Job? = null
    private val lastPing = mutableMapOf<Firmware, Pair<Long, Fix>>()
    private var lastAnyPingMillis: Long? = null
    private var coverageWasOn = false

    private object Keys {
        const val PINGING = "drive.pinging"
        const val SCREEN = "drive.keepScreenOn"
    }

    fun start() {
        if (state.active) return
        _state.value = DriveState(active = true, startedAtMillis = now())
        lastPing.clear()
        lastAnyPingMillis = null
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
        _state.value = state.copy(active = false, pingNote = null, waiting = false,
            radios = state.radios.mapValues { it.value.copy(waiting = false, note = null) })
        loop?.cancel()
        loop = null
        onActiveChanged(false)
        if (!coverageWasOn) coverage.recording = false
    }

    /** One step of the loop; public so tests can drive it. */
    fun tick() {
        val startedAt = state.startedAtMillis
        if (startedAt != null && now() - startedAt > MAX_DURATION_MILLIS) { stop(); return }
        val radios = radios()
        if (!pinging) {
            radios.forEach { (fw, _) -> radioNote(fw, null) }
            notes(radios); return
        }
        if (radios.isEmpty()) { note("Waiting for the radio"); return }
        val fix = coverage.freshFix()
        for ((fw, mesh) in radios) {
            val target = target(fw)
            val why = when {
                mesh.connection !is MeshConnectionState.Connected -> "Waiting for the radio"
                fw == Firmware.MESHCORE && target == null -> "MeshCore pings need a radio to ping"
                target != null && mesh.nodes[target] == null -> "The radio to ping isn't known on this mesh"
                fix == null -> "Waiting for GPS"
                else -> null
            }
            radioNote(fw, why)
            if (why != null || fix == null) continue
            if (radio(fw).waiting) continue
            val last = lastPing[fw]
            if (last != null && (now() - last.first < MIN_INTERVAL_MILLIS ||
                    CoverageShare.distance(last.second.latitude, last.second.longitude, fix.latitude, fix.longitude) < MIN_DISTANCE_METRES)) continue
            // The other radio pinged a moment ago: wait so the two don't go out together.
            if (radios.size > 1 && lastAnyPingMillis?.let { now() - it < STAGGER_MILLIS } == true) continue
            val firmware = mesh.firmware
            val sentOK = mesh.ping(target) { outcome -> finished(fw, outcome, fix, firmware) }
            if (sentOK) {
                updateRadio(fw) { it.copy(waiting = true, sent = it.sent + 1) }
                lastPing[fw] = now() to fix
                lastAnyPingMillis = now()
            }
        }
        notes(radios)
    }

    private fun finished(fw: Firmware, outcome: PingOutcome, fix: Fix, firmware: Firmware) {
        updateRadio(fw) {
            it.copy(waiting = false, lastOutcome = outcome,
                reached = it.reached + if (outcome == PingOutcome.REACHED) 1 else 0,
                relayed = it.relayed + if (outcome == PingOutcome.RELAYED) 1 else 0,
                unanswered = it.unanswered + if (outcome == PingOutcome.NONE) 1 else 0)
        }
        _state.value = state.copy(lastOutcome = outcome)
        coverage.recordPing(outcome, fix, firmware)
    }

    private fun radio(fw: Firmware): RadioDriveState = state.radios[fw] ?: RadioDriveState()

    /** Changes one radio's state and recomputes the totals. */
    private fun updateRadio(fw: Firmware, change: (RadioDriveState) -> RadioDriveState) {
        val radios = state.radios + (fw to change(radio(fw)))
        val all = radios.values
        _state.value = state.copy(
            radios = radios,
            sent = all.sumOf { it.sent }, reached = all.sumOf { it.reached },
            relayed = all.sumOf { it.relayed }, unanswered = all.sumOf { it.unanswered },
            waiting = all.any { it.waiting },
        )
    }

    private fun radioNote(fw: Firmware, text: String?) {
        if (radio(fw).note != text) updateRadio(fw) { it.copy(note = text) }
    }

    /** One status line: the note itself with one radio (or when both say the same), else each radio's. */
    private fun notes(radios: List<Pair<Firmware, MeshService>>) {
        val notes = radios.mapNotNull { (fw, _) -> radio(fw).note?.let { fw to it } }
        note(when {
            notes.isEmpty() -> null
            radios.size == 1 || (notes.size == radios.size && notes.map { it.second }.distinct().size == 1) -> notes.first().second
            else -> notes.joinToString(" · ") { (fw, n) -> "${fw.displayName}: $n" }
        })
    }

    private fun note(text: String?) {
        if (state.pingNote != text) _state.value = state.copy(pingNote = text)
    }
}
