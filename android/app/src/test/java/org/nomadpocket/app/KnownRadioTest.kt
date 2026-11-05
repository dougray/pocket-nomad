package org.nomadpocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.app.map.KnownRadio
import org.nomadpocket.app.map.KnownRadioParser
import org.nomadpocket.mesh.Firmware
import org.nomadpocket.mesh.MeshCoreRadioParams
import org.nomadpocket.mesh.NearbyRadioParams
import org.nomadpocket.mesh.OptOut
import java.time.Instant

/**
 * Ports AppTests/DriveAndKnownRadioTests.swift. Same JSON shapes and values, but the
 * positions are moved to Denver, the place used for all test and demo data.
 */
class KnownRadioTest {
    @Test fun parsesMeshmapNodes() {
        val json = """
        {"1002094074": {"longName": " Hill Router ", "shortName": "HR", "role": "ROUTER", "latitude": 397392000, "longitude": -1049903000,
                        "seenBy": {"msh/US/a": 1793801450, "msh/US/b": 1793803896}},
         "55": {"longName": "", "shortName": "CL", "role": "CLIENT_MUTE", "latitude": 397400000, "longitude": -1049900000, "seenBy": {}},
         "77": {"longName": "No position", "role": "CLIENT"}}
        """
        val radios = KnownRadioParser.parseMeshtastic(json).sortedBy { it.id }
        assertEquals(2, radios.size)
        val router = radios.first { it.id == "t1002094074" }
        assertEquals("Hill Router", router.name)
        assertEquals(KnownRadio.Role.REPEATER, router.role)
        assertEquals(Firmware.MESHTASTIC, router.firmware)
        assertEquals(39.7392, router.latitude, 1e-6)
        assertEquals(-104.9903, router.longitude, 1e-6)
        assertEquals(Instant.ofEpochSecond(1793803896), router.lastSeen)
        assertEquals("Router", router.roleLabel)
        assertEquals("falls back to the short name", "CL", radios.first { it.id == "t55" }.name)
        assertEquals(KnownRadio.Role.CLIENT, radios.first { it.id == "t55" }.role)
    }

    @Test fun parsesMeshCoreNodes() {
        val json = """
        [{"public_key": "0100000ae030862bebf148f4bbb9", "type": 2, "adv_name": "Hilltop RPT",
          "last_advert": "2024-01-01T00:00:00.000Z", "updated_date": "2026-10-17T23:28:51.000Z", "adv_lat": 39.7690, "adv_lon": -105.0210, "params": {"freq": 910.525}},
         {"public_key": "ff", "type": 1, "adv_name": "Zero", "adv_lat": 0, "adv_lon": 0},
         {"public_key": "aa", "type": 3, "adv_name": "Room", "adv_lat": 39.7, "adv_lon": -104.9}]
        """
        val radios = KnownRadioParser.parseMeshCore(json)
        assertEquals("radios without a position are skipped", 2, radios.size)
        assertEquals(KnownRadio.Role.REPEATER, radios[0].role)
        assertEquals("Repeater", radios[0].roleLabel)
        assertEquals(Firmware.MESHCORE, radios[0].firmware)
        assertEquals("c0100000ae030862b", radios[0].id)
        assertEquals("server time, not the radio's clock", 1_792_279_731L, radios[0].lastSeen!!.epochSecond)
        assertEquals(KnownRadio.Role.ROOM, radios[1].role)
        assertEquals("Room server", radios[1].roleLabel)
    }

    private val denverLat = 39.7392
    private val denverLon = -104.9903
    private val radios = listOf(
        KnownRadio("a", Firmware.MESHCORE, "Close", KnownRadio.Role.REPEATER, 39.77, -105.00),
        KnownRadio("b", Firmware.MESHTASTIC, "Colorado Springs", KnownRadio.Role.CLIENT, 38.83, -104.82),
        KnownRadio("c", Firmware.MESHTASTIC, "Private 🚫", KnownRadio.Role.CLIENT, 39.74, -104.99),
    )

    @Test fun nearbyKeepsTheRadiusAndDropsOptOuts() {
        val optsOut: (String) -> Boolean = { n -> listOf("⛔", "🛑", "🚫").any { n.contains(it) } }
        assertEquals(listOf("a"), KnownRadioParser.nearby(radios, denverLat, denverLon, 50.0, optsOut).map { it.id })
        assertEquals(listOf("a", "b"), KnownRadioParser.nearby(radios, denverLat, denverLon, 200.0, optsOut).map { it.id })
    }

    /** The real check from :mesh: ⛔ 🛑 🚫, with or without a variation selector. */
    @Test fun nearbyUsesTheMeshOptOutCheck() {
        assertTrue(OptOut.optsOut("Base ⛔"))
        assertTrue(OptOut.optsOut("Base ⛔️"))
        assertTrue(OptOut.optsOut("🛑 Hilltop"))
        assertTrue(OptOut.optsOut("Car🚫"))
        assertFalse(OptOut.optsOut("Denver Repeater 📡"))
        assertEquals(listOf("a", "b"), KnownRadioParser.nearby(radios, denverLat, denverLon, 200.0).map { it.id })
    }

    @Test fun distance() {
        // Denver to Colorado Springs is about 102 km.
        assertEquals(102_000.0, KnownRadioParser.distance(39.7392, -104.9903, 38.8339, -104.8214), 3_000.0)
    }

    @Test fun savedFileRoundTrips() {
        val updated = Instant.ofEpochMilli(1_800_000_000_000)
        val list = radios + KnownRadio("d", Firmware.MESHCORE, "Seen", KnownRadio.Role.SENSOR, 1.0, 2.0, Instant.ofEpochSecond(5))
        val saved = KnownRadioParser.fromJson(KnownRadioParser.toJson(updated, "Denver", list))
        assertEquals(updated, saved.updated)
        assertEquals("Denver", saved.areaName)
        assertEquals(list, saved.radios)
    }

    @Test fun parsesMeshCoreRadioSettings() {
        // map.meshcore.io sends params as numbers in MHz/kHz, sometimes ints, sometimes empty or missing.
        val json = """
        [{"public_key": "aa01", "type": 2, "adv_name": "Ridge", "adv_lat": 39.74, "adv_lon": -104.99, "params": {"freq": 910.525, "bw": 62.5, "sf": 7, "cr": 5}},
         {"public_key": "aa02", "type": 1, "adv_name": "Park", "adv_lat": 39.75, "adv_lon": -104.98, "params": {"freq": 910.52502, "bw": 62.5, "cr": 5, "sf": 7}},
         {"public_key": "aa03", "type": 1, "adv_name": "Hill", "adv_lat": 39.76, "adv_lon": -104.97, "params": {"freq": 927, "bw": 125, "sf": 9, "cr": 8}},
         {"public_key": "aa04", "type": 1, "adv_name": "Empty", "adv_lat": 39.77, "adv_lon": -104.96, "params": {}},
         {"public_key": "aa05", "type": 1, "adv_name": "None", "adv_lat": 39.78, "adv_lon": -104.95}]
        """
        val radios = KnownRadioParser.parseMeshCore(json).associateBy { it.name }
        assertEquals(MeshCoreRadioParams(910.525, 62.5, 7, 5), radios.getValue("Ridge").params)
        assertEquals(910_525L, radios.getValue("Park").params!!.frequencyKHz)
        assertEquals(MeshCoreRadioParams(927.0, 125.0, 9, 8), radios.getValue("Hill").params)
        assertEquals(null, radios.getValue("Empty").params)
        assertEquals(null, radios.getValue("None").params)
        val match = NearbyRadioParams.mostCommon(radios.values.map { it.params })!!
        assertEquals(2, match.count)
        assertEquals(3, match.total)

        // Saved and read back for offline use.
        val saved = KnownRadioParser.fromJson(KnownRadioParser.toJson(Instant.ofEpochSecond(1_790_000_000), null, radios.values.toList()))
        assertEquals(radios.values.map { it.params }, saved.radios.map { it.params })
    }
}
