// The map's coverage network filter (Both / Meshtastic / MeshCore) and the
// network key a hexagon is outlined by. Coordinates are made up.
package org.nomadpocket.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.nomadpocket.app.map.CoverageNetwork
import org.nomadpocket.mesh.CoverageData
import org.nomadpocket.mesh.CoveragePing
import org.nomadpocket.mesh.CoverageSample
import org.nomadpocket.mesh.Firmware
import org.nomadpocket.mesh.PingOutcome

class CoverageNetworkTest {
    private val data = CoverageData(
        listOf(CoverageSample(10.1, -20.1, 4f, Firmware.MESHTASTIC), CoverageSample(10.2, -20.2, -3f, Firmware.MESHCORE),
            CoverageSample(10.3, -20.3, null, Firmware.MESHCORE)),
        listOf(CoveragePing(10.1, -20.1, PingOutcome.REACHED, Firmware.MESHCORE)),
    )

    @Test fun filtersSamplesAndPingsByNetwork() {
        assertEquals(data, CoverageNetwork.BOTH.filter(data))
        val mt = CoverageNetwork.MESHTASTIC.filter(data)
        assertEquals(1, mt.samples.size)
        assertEquals(0, mt.pings.size)
        val mc = CoverageNetwork.MESHCORE.filter(data)
        assertEquals(listOf(Firmware.MESHCORE, Firmware.MESHCORE), mc.samples.map { it.firmware })
        assertEquals(1, mc.pings.size)
    }

    @Test fun savedChoiceAndHexagonKeys() {
        assertEquals(CoverageNetwork.BOTH, CoverageNetwork.from(null))
        assertEquals(CoverageNetwork.MESHCORE, CoverageNetwork.from("meshcore"))
        assertEquals("both", CoverageNetwork.key(setOf(Firmware.MESHTASTIC, Firmware.MESHCORE)))
        assertEquals("meshcore", CoverageNetwork.key(setOf(Firmware.MESHCORE)))
        assertEquals("meshtastic", CoverageNetwork.key(setOf(Firmware.MESHTASTIC)))
    }
}
