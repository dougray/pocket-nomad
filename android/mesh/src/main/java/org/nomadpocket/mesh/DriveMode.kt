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
    /** The drive diary (DriveLog.note in the app). */
    private val log: (String) -> Unit = {},
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
        const val TICK_MILLIS = 5_000L
        /** The status line when every radio in use is away (not nearby or not with the user). */
        const val AWAY_NOTE = "Radio not nearby · pings resume when it's back"

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
    /** Radio to ping on each network; null means "any radio that repeats it" on Meshtastic and "the repeater heard most recently" on MeshCore. */
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
    /** For the drive log: what was last written about each radio, and when the last heartbeat went in. */
    private val loggedConnection = mutableMapOf<Firmware, String>()
    private val loggedNotes = mutableMapOf<Firmware, String?>()
    private var lastHeartbeatMillis: Long? = null
    private var samplesAtHeartbeat = 0
    /** Each radio's MeshService.briefDrops at the last heartbeat. */
    private val dropsAtHeartbeat = mutableMapOf<Firmware, Int>()

    private object Keys {
        const val PINGING = "drive.pinging"
        const val SCREEN = "drive.keepScreenOn"
        /** Set while a drive runs, so a drive survives Android closing the app. */
        const val ACTIVE_SINCE = "drive.activeSince"
        const val COVERAGE_WAS_ON = "drive.coverageWasOn"
    }

    fun start() {
        if (state.active) return
        coverageWasOn = coverage.recording
        begin(now())
        log("drive mode on")
    }

    /** Picks a drive back up after Android closed the app mid-drive. Call once at launch. */
    fun resumeIfNeeded() {
        if (state.active || !prefs.contains(Keys.ACTIVE_SINCE)) return
        val since = prefs.getLong(Keys.ACTIVE_SINCE, 0)
        coverageWasOn = prefs.getBoolean(Keys.COVERAGE_WAS_ON, false)
        log("drive mode resumed after Android closed the app")
        begin(since)
    }

    private fun begin(since: Long) {
        _state.value = DriveState(active = true, startedAtMillis = since)
        prefs.putLong(Keys.ACTIVE_SINCE, since)
        prefs.putBoolean(Keys.COVERAGE_WAS_ON, coverageWasOn)
        lastPing.clear()
        lastAnyPingMillis = null
        loggedConnection.clear()
        loggedNotes.clear()
        lastHeartbeatMillis = null
        samplesAtHeartbeat = coverage.samples.size
        dropsAtHeartbeat.clear()
        radios().forEach { (fw, mesh) -> dropsAtHeartbeat[fw] = mesh.briefDrops }
        coverage.recording = true
        onActiveChanged(true)
        loop = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MILLIS)
            }
        }
    }

    fun stop(reason: String = "turned off") {
        if (!state.active) return
        log("drive mode off: $reason · ${state.sent} pings, ${state.reached} reached, ${state.relayed} relayed, ${state.unanswered} unanswered")
        prefs.remove(Keys.ACTIVE_SINCE)
        _state.value = state.copy(active = false, pingNote = null, waiting = false,
            radios = state.radios.mapValues { it.value.copy(waiting = false, note = null) })
        loop?.cancel()
        loop = null
        onActiveChanged(false)
        if (!coverageWasOn) coverage.recording = false
    }

    /** One step of the loop; public so tests can drive it. */
    fun tick() {
        val inUse = radios()
        // The link timers (brief drop, Not nearby) also run here, in case Android delayed their own wake-up.
        inUse.forEach { it.second.checkLink() }
        logState(inUse)
        // A radio that is not nearby, or not with the user today, is left out as if it weren't there.
        val radios = inUse.filterNot { it.second.connection.isAway }
        inUse.filter { it.second.connection.isAway }.forEach { (fw, _) -> radioNote(fw, null) }
        if (!pinging) {
            radios.forEach { (fw, _) -> radioNote(fw, null) }
            notes(radios); return
        }
        if (radios.isEmpty()) { note(if (inUse.isEmpty()) "Waiting for the radio" else AWAY_NOTE); return }
        val fix = coverage.freshFix()
        for ((fw, mesh) in radios) {
            val target = target(fw) ?: mesh.autoPingTarget()
            // Notes go by the displayed link, so a drop of a few seconds doesn't change them.
            val why = when {
                mesh.displayConnection !is MeshConnectionState.Connected -> "Waiting for the radio"
                fw == Firmware.MESHCORE && target == null -> "Waiting to hear a repeater"
                target != null && mesh.nodes[target] == null && mesh.neighbors[target] == null -> "The radio to ping isn't known on this mesh"
                fix == null -> "Waiting for GPS"
                else -> null
            }
            radioNote(fw, why)
            if (why != null || fix == null) continue
            // A brief drop still in progress: wait quietly for the link to come back.
            if (mesh.connection !is MeshConnectionState.Connected) continue
            if (radio(fw).waiting) continue
            val last = lastPing[fw]
            if (last != null && (now() - last.first < MIN_INTERVAL_MILLIS ||
                    CoverageShare.distance(last.second.latitude, last.second.longitude, fix.latitude, fix.longitude) < MIN_DISTANCE_METRES)) continue
            // The other radio pinged a moment ago: wait so the two don't go out together.
            if (radios.size > 1 && lastAnyPingMillis?.let { now() - it < STAGGER_MILLIS } == true) continue
            val firmware = mesh.firmware
            val sentOK = mesh.ping(target) { outcome -> finished(fw, outcome, fix, firmware) }
            if (sentOK) {
                log("${fw.displayName} ping sent" + (target?.let { " to !%08x".format(it) } ?: ""))
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
        log("${fw.displayName} ping: ${outcome.name.lowercase()}")
    }

    /**
     * Radio links coming and going (with why they went, by the displayed link,
     * so drops shorter than MeshService.FLICKER_MILLIS don't appear), and a
     * heartbeat each minute that counts those brief drops.
     */
    private fun logState(radios: List<Pair<Firmware, MeshService>>) {
        for ((fw, mesh) in radios) {
            val c = mesh.displayConnection
            val text = when (c) {
                is MeshConnectionState.Connected -> "connected"
                is MeshConnectionState.NotNearby -> "not nearby · reconnects when it's back"
                is MeshConnectionState.NotWithMe -> "not with me today · no reconnecting until Connect"
                else -> "not connected (${c::class.simpleName?.lowercase()})"
            }
            val was = loggedConnection[fw]
            if (was != text) {
                loggedConnection[fw] = text
                val why = when {
                    was == null -> ""
                    was == "connected" && c !is MeshConnectionState.NotWithMe -> mesh.lastDropReason?.let { ": $it" } ?: ""
                    was.startsWith("not nearby") -> " (was not nearby)"
                    was.startsWith("not with me") -> " (was not with me today)"
                    else -> ""
                }
                log("${fw.displayName} radio $text$why")
            }
        }
        val t = now()
        val last = lastHeartbeatMillis
        if (last != null && t - last < 60_000) return
        lastHeartbeatMillis = t
        val fix = coverage.freshFix()
        val gps = if (fix != null) "GPS ±${fix.accuracy.toInt()} m" else "no fresh GPS fix"
        val points = coverage.samples.size
        val drops = radios.mapNotNull { (fw, mesh) ->
            val n = mesh.briefDrops - (dropsAtHeartbeat[fw] ?: mesh.briefDrops)
            dropsAtHeartbeat[fw] = mesh.briefDrops
            if (n > 0) (if (radios.size > 1) "${fw.displayName} " else "") + "brief drop ×$n" else null
        }
        log((listOf("still running", gps, "${points - samplesAtHeartbeat} new coverage points") + drops).joinToString(" · "))
        samplesAtHeartbeat = points
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
        if (loggedNotes[fw] != text) {
            loggedNotes[fw] = text
            if (text != null && state.active) log("${fw.displayName} pings waiting: $text")
        }
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
