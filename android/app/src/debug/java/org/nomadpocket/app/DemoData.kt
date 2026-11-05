package org.nomadpocket.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.nomadpocket.app.map.KnownRadio
import org.nomadpocket.app.map.KnownRadioParser
import org.nomadpocket.app.map.KnownRadioStore
import org.nomadpocket.mesh.CoverageData
import org.nomadpocket.mesh.CoverageRecord
import org.nomadpocket.mesh.CoverageShare
import org.nomadpocket.mesh.CoverageZone
import org.nomadpocket.mesh.PingRecord
import org.nomadpocket.mesh.CoveragePing
import org.nomadpocket.mesh.CoverageSample
import org.nomadpocket.mesh.Firmware
import org.nomadpocket.mesh.MapNode
import org.nomadpocket.mesh.PingOutcome
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Debug builds only: demo radios and coverage around downtown Denver for
 * screenshots, like the iOS app's `-screen map:demo`. Start with
 * `adb shell am start -n <app>/org.nomadpocket.app.MainActivity -e screen map:demo`.
 */
object DemoData {
    val nodes = MutableStateFlow<List<MapNode>>(emptyList())
    val coverage = MutableStateFlow<CoverageData?>(null)

    val nodesFlow: StateFlow<List<MapNode>> get() = nodes
    val coverageFlow: StateFlow<CoverageData?> get() = coverage

    /** Where the map opens for the demo (latitude, longitude, zoom), instead of following GPS. */
    var camera: Triple<Double, Double, Double>? = null
        private set

    fun load(context: Context, screen: String?) {
        if (screen == "mesh:demo" || (screen?.startsWith("map:") == true)) loadNodes()
        // The Mesh tab with a demo Meshtastic and a demo MeshCore radio "connected" (nothing is sent).
        if (screen == "mesh:demo" && org.nomadpocket.mesh.Mesh.isInitialized) org.nomadpocket.mesh.Mesh.radios.loadDemo()
        if (screen == "map:shared") {
            loadCoverage()
            camera = Triple(39.7440, -104.9960, 12.6)
            context.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putString("coverage", "shared").apply()
        }
        if (screen == "map:demo") {
            loadCoverage()
            loadKnown()
            camera = Triple(39.7440, -104.9960, 12.6)
            context.getSharedPreferences("map", Context.MODE_PRIVATE).edit()
                .putString("coverage", "recorded").putBoolean("showNodes", true).putBoolean("showKnown", true).apply()
        }
    }

    /** "What sharing would publish" for the demo drive, with a private zone at the start. */
    val sharedCells: List<CoverageShare.Cell>?
        get() {
            val c = coverage.value ?: return null
            val now = System.currentTimeMillis()
            val records = c.samples.mapIndexed { i, s ->
                CoverageRecord(now - 3 * 86_400_000L + i * 30_000L, s.latitude, s.longitude, 6.0, s.firmware, 0xA000L + i % 3, s.snr, 0)
            }
            val pings = c.pings.map { PingRecord(now - 86_400_000L, it.latitude, it.longitude, 6.0, it.firmware, it.outcome) }
            return CoverageShare.cells(records, pings, listOf(CoverageZone(name = "Home (demo)", latitude = 39.7392, longitude = -104.9849, radius = 500.0)))
        }

    private fun loadNodes() {
        val now = System.currentTimeMillis()
        val demo = listOf(
            Triple("Ridge Repeater", 39.7480 to -104.9970, Firmware.MESHCORE),
            Triple("Ana", 39.7560 to -104.9870, Firmware.MESHTASTIC),
            Triple("Base Camp", 39.7410 to -105.0060, Firmware.MESHCORE),
            Triple("Cherry Creek", 39.7170 to -104.9530, Firmware.MESHTASTIC),
        )
        nodes.value = demo.mapIndexed { i, (name, pos, fw) ->
            MapNode(0xA000L + i, name, fw, pos.first, pos.second, now - i * 420_000L)
        }
    }

    /** A drive around a pretend tower, with signal fading with distance, and a few pings. */
    private fun loadCoverage() {
        val towerLat = 39.7480
        val towerLon = -104.9970
        val rng = Random(7)
        val samples = mutableListOf<CoverageSample>()
        val pings = mutableListOf<CoveragePing>()
        for (route in 0 until 6) {
            val angle = route * Math.PI / 3 + 0.4
            for (step in 0 until 40) {
                val d = step * 0.0011
                val lat = towerLat + d * sin(angle) + rng.nextDouble(-0.0004, 0.0004)
                val lon = towerLon + d * cos(angle) * 1.3 + rng.nextDouble(-0.0004, 0.0004)
                val km = KnownRadioParser.distance(towerLat, towerLon, lat, lon) / 1000
                val snr = (9 - km * 5 + rng.nextDouble(-2.0, 2.0)).toFloat()
                if (snr < -18) continue
                val fw = if (route % 2 == 0) Firmware.MESHTASTIC else Firmware.MESHCORE
                samples += CoverageSample(lat, lon, snr, fw)
                if (step % 8 == 4) {
                    val outcome = when {
                        snr > 2 -> PingOutcome.REACHED
                        snr > -8 -> PingOutcome.RELAYED
                        else -> PingOutcome.NONE
                    }
                    pings += CoveragePing(lat, lon, outcome, fw)
                }
            }
        }
        coverage.value = CoverageData(samples, pings)
    }

    private fun loadKnown() {
        KnownRadioStore.setForDemo(
            listOf(
                KnownRadio("demo1", Firmware.MESHCORE, "Demo Hill Repeater", KnownRadio.Role.REPEATER, 39.7690, -105.0210),
                KnownRadio("demo2", Firmware.MESHTASTIC, "Demo Router", KnownRadio.Role.REPEATER, 39.7290, -104.9660),
                KnownRadio("demo3", Firmware.MESHTASTIC, "Demo Client", KnownRadio.Role.CLIENT, 39.7350, -105.0180),
                KnownRadio("demo4", Firmware.MESHCORE, "Demo Room", KnownRadio.Role.ROOM, 39.7600, -104.9700),
                KnownRadio("demo5", Firmware.MESHTASTIC, "Demo Client 2", KnownRadio.Role.CLIENT, 39.7520, -105.0300),
            ),
        )
    }
}
