// Radios the user hid from the map ("This is mine: hide it from the map")
// count as their own: never recorded, never on either radio layer, never
// credited by the resolver, left out of the coverage cells, kept across launches.
// Synthetic radios and made-up coordinates (open ocean).
package org.nomadpocket.mesh

import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.mesh.proto.Bytes
import org.nomadpocket.mesh.proto.MeshCoreContact
import org.nomadpocket.mesh.proto.MeshCoreContactType
import java.io.File
import java.nio.file.Files

class HiddenRadiosTest {
    private val dir: File = Files.createTempDirectory("hidden").toFile()
    private val prefs = MemoryMeshPrefs()
    private val scope = TestScope()
    private var clock = 1_800_000_000_000L
    private var fix: Fix? = null

    @After fun tearDown() { dir.deleteRecursively() }

    private val repeaterKey = Bytes(byteArrayOf(0x3F, 0x09, 0x10, 0x11, 0x12, 0x13))
    private val repeater = MeshNode(MeshCoreContact.nodeNum(repeaterKey.array), longName = "My Hill Repeater",
        publicKeyPrefix = repeaterKey, meshCoreType = MeshCoreContactType.REPEATER, latitude = 10.20, longitude = -20.40)
    private val myPhone = MeshNode(0x2222_0001L, longName = "My Other Phone", latitude = 10.21, longitude = -20.41, hopsAway = 0)
    private val someone = MeshNode(0x2222_0002L, longName = "Someone", latitude = 10.22, longitude = -20.42, hopsAway = 0)
    private val otherRepeaterKey = Bytes(byteArrayOf(0x55, 0x01, 0x10, 0x11, 0x12, 0x13))
    private val otherRepeater = MeshNode(MeshCoreContact.nodeNum(otherRepeaterKey.array), longName = "Their Repeater",
        publicKeyPrefix = otherRepeaterKey, meshCoreType = MeshCoreContactType.REPEATER, latitude = 10.19, longitude = -20.39)

    private fun standIn(vararg b: Int) = MeshCoreContact.nodeNumForHash(ByteArray(b.size) { b[it].toByte() })

    private fun hideBoth(h: HiddenRadios) {
        h.hide(HiddenRadio(Firmware.MESHTASTIC, myPhone.num, myPhone.displayName))
        h.hide(HiddenRadio(Firmware.MESHCORE, repeater.num, repeater.displayName, repeaterKey.hex()))
    }

    @Test fun hiddenRadiosCountAsOwnAndPersist() {
        val h = HiddenRadios(prefs)
        assertFalse(h.matches(myPhone.num, Firmware.MESHTASTIC))
        hideBoth(h)
        assertTrue(h.matches(myPhone.num, Firmware.MESHTASTIC))
        assertTrue(h.matches(repeater.num, Firmware.MESHCORE))
        // Per network: the same number on the other network is someone else.
        assertFalse(h.matches(myPhone.num, Firmware.MESHCORE))
        assertFalse(h.matches(someone.num, Firmware.MESHTASTIC))
        assertFalse(h.matches(0, Firmware.MESHTASTIC))
        assertTrue(h.contains(Firmware.MESHCORE, repeater.num))
        assertEquals(listOf("meshtastic.${myPhone.num}", "meshcore.${repeater.num}"), h.list.map { it.id })

        // Kept across launches, as JSON in the prefs.
        val again = HiddenRadios(prefs)
        assertEquals(h.list, again.list)
        assertEquals("3f0910111213", again.list.last().keyHex)

        // Hiding again updates rather than duplicates; unhide removes.
        again.hide(HiddenRadio(Firmware.MESHTASTIC, myPhone.num, "Renamed"))
        assertEquals(2, again.list.size)
        again.unhide(Firmware.MESHTASTIC, myPhone.num)
        assertFalse(again.matches(myPhone.num, Firmware.MESHTASTIC))
        assertEquals(1, HiddenRadios(prefs).list.size)
    }

    @Test fun hiddenMeshCoreRepeaterHashStandInsCountAsOwn() {
        val h = HiddenRadios(prefs)
        hideBoth(h)
        // A repeater known only by its 1, 2 or 3-byte path hash.
        assertTrue(h.matches(standIn(0x3F), Firmware.MESHCORE))
        assertTrue(h.matches(standIn(0x3F, 0x09), Firmware.MESHCORE))
        assertTrue(h.matches(standIn(0x3F, 0x09, 0x10), Firmware.MESHCORE))
        assertFalse(h.matches(standIn(0x3F, 0x0A), Firmware.MESHCORE))
        assertFalse(h.matches(standIn(0x55), Firmware.MESHCORE))
        // Only on MeshCore.
        assertFalse(h.matches(standIn(0x3F), Firmware.MESHTASTIC))
    }

    @Test fun hiddenRadiosAreDroppedFromBothLayersAndTheResolver() {
        val h = HiddenRadios(prefs)
        val own = { n: Long, fw: Firmware -> h.matches(n, fw) }
        val contacts = mapOf(
            Firmware.MESHTASTIC to listOf(myPhone, someone).associateBy { it.num },
            Firmware.MESHCORE to listOf(repeater, otherRepeater).associateBy { it.num },
        )
        val samples = listOf(
            CoverageRecord(1_000, 10.2, -20.4, 5.0, Firmware.MESHCORE, standIn(0x3F), -3f, 2),
            CoverageRecord(1_000, 10.2, -20.4, 5.0, Firmware.MESHTASTIC, myPhone.num, 4f, 0),
            CoverageRecord(1_000, 10.2, -20.4, 5.0, Firmware.MESHTASTIC, someone.num, 4f, 0),
        )
        val before = HeardResolver.derive(samples, contacts, own)
        assertTrue(before.direct.any { it.num == repeater.num })
        assertTrue(before.direct.any { it.num == myPhone.num })

        hideBoth(h)
        assertEquals(HeardCredit.Own, HeardResolver.credit(samples[0], contacts.getValue(Firmware.MESHCORE), own))
        val after = HeardResolver.derive(samples, contacts, own,
            alsoDirect = listOf(Firmware.MESHTASTIC to myPhone, Firmware.MESHCORE to repeater))
        val all = after.direct + after.known
        assertFalse("hidden repeater never drawn", all.any { it.num == repeater.num })
        assertFalse("hidden phone never drawn", all.any { it.num == myPhone.num })
        assertEquals(listOf(someone.num), after.direct.map { it.num })
        assertEquals("other radios are still known on the mesh", listOf(otherRepeater.num), after.known.map { it.num })
        assertEquals(0, after.coverageOnly)
    }

    @Test fun hiddenRadiosAreNeverRecordedAndLeaveTheCells() {
        val h = HiddenRadios(prefs)
        val store = CoverageStore(prefs, File(dir, "coverage.json"), scope, location = { fix }, now = { clock },
            isOwnRadio = { n, fw -> h.matches(n, fw) })
        store.recording = true
        fix = Fix(10.2, -20.4, 5.0, clock)
        // Recorded before the user hid them: two different places, so two cells.
        store.record(MeshHeard(myPhone.num, 5f, 0, Firmware.MESHTASTIC))
        store.record(MeshHeard(standIn(0x3F), 2f, 2, Firmware.MESHCORE))
        fix = Fix(10.6, -20.9, 5.0, clock)
        store.record(MeshHeard(someone.num, 3f, 0, Firmware.MESHTASTIC))
        assertEquals(3, store.samples.size)
        store.addZone("Far away", 0.0, 0.0, 100.0)
        assertEquals(2, store.sharedCells.size)

        hideBoth(h)
        // Existing samples credited to hidden radios leave the private and the shared view.
        assertEquals(listOf(someone.num), store.samples.filterNot { store.hidden(it) }.map { it.node })
        assertEquals(1, store.sharedCells.size)
        assertEquals(1, store.sharedCells.single().count)
        val private = CoverageData(store.samples.filterNot { store.hidden(it) }.map { CoverageSample(it.latitude, it.longitude, it.snr, it.firmware) }, emptyList())
        assertEquals(1, CoverageShare.mapCells(private).size)

        // And never recorded again.
        store.record(MeshHeard(myPhone.num, 5f, 0, Firmware.MESHTASTIC))
        store.record(MeshHeard(repeater.num, 5f, 0, Firmware.MESHCORE))
        clock += 60_000; fix = Fix(10.6, -20.9, 5.0, clock)
        store.record(MeshHeard(standIn(0x3F, 0x09), 5f, 1, Firmware.MESHCORE))
        assertEquals(3, store.samples.size)
    }

    @Test fun communityMapFilterIncludesHiddenRadios() {
        val h = HiddenRadios(prefs)
        assertNull(h.list.firstOrNull())
        hideBoth(h)
        val own = OwnRadios(nodeNums = setOf(0x1111L)) + h.own
        assertEquals(setOf(0x1111L, myPhone.num), own.nodeNums)
        assertEquals(setOf("3f0910111213"), own.meshCoreKeyPrefixes)
    }
}
