// Which radio a coverage recording credits (HeardResolver): a last-hop hash is
// only a known radio when one with that key prefix and a position is within
// range of where it was heard, else an unidentified repeater; node 0 is
// coverage only; stand-in numbers in saved recordings re-derive; own radios
// never show. Synthetic radios around Denver.
package org.nomadpocket.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.mesh.proto.Bytes
import org.nomadpocket.mesh.proto.MeshCoreContact
import org.nomadpocket.mesh.proto.MeshCoreContactType

class HeardResolverTest {
    private val denver = 39.7392 to -104.9903

    private fun key(vararg b: Int) = Bytes(ByteArray(6) { if (it < b.size) b[it].toByte() else (0x10 + it).toByte() })

    private fun contact(name: String, k: Bytes, lat: Double?, lon: Double?, type: MeshCoreContactType = MeshCoreContactType.REPEATER) =
        MeshNode(MeshCoreContact.nodeNum(k.array.copyOf(4)), longName = name, publicKeyPrefix = k, meshCoreType = type,
            latitude = lat, longitude = lon)

    // Same first byte (0x3F) on all three: one near Denver, one closer still but a chat radio, one in Pueblo (~160 km).
    private val golden = contact("Golden Ridge", key(0x3F, 0x01), 39.7555, -105.2211)
    private val companion = contact("Hiker", key(0x3F, 0x02), 39.7400, -104.9910, MeshCoreContactType.CHAT)
    private val pueblo = contact("Pueblo Hill", key(0x3F, 0x03), 38.2544, -104.6091)
    private val nearer = contact("Capitol Hill", key(0x3F, 0x04), 39.7310, -104.9800)
    private val noGps = contact("Basement", key(0x7A, 0x01), null, null)

    private fun map(vararg n: MeshNode) = n.associateBy { it.num }

    private fun sample(node: Long, hops: Long?, at: Pair<Double, Double> = denver, fw: Firmware = Firmware.MESHCORE) =
        CoverageRecord(1_000, at.first, at.second, 5.0, fw, node, -3f, hops)

    private fun standIn(vararg b: Int) = MeshCoreContact.nodeNumForHash(ByteArray(b.size) { b[it].toByte() })

    @Test fun nearestRepeaterInRangeIsCredited() {
        val contacts = map(golden, pueblo, nearer, companion)
        val c = HeardResolver.credit(sample(standIn(0x3F), 4), contacts)
        // The chat radio is closest but cannot repeat; the nearest repeater wins.
        assertEquals(HeardCredit.Radio(nearer), c)
        // Companions never repeat: with no repeater or room in range, the hash stays unidentified.
        assertEquals(HeardCredit.Unidentified(Bytes(byteArrayOf(0x3F))), HeardResolver.credit(sample(standIn(0x3F), 4), map(pueblo, companion)))
        // A room server counts.
        val room = contact("Library Room", key(0x3F, 0x05), 39.7420, -104.9880, MeshCoreContactType.ROOM)
        assertEquals(HeardCredit.Radio(room), HeardResolver.credit(sample(standIn(0x3F), 4), map(pueblo, companion, room)))
        // A two-byte hash narrows it to the one radio whose key starts with both bytes.
        assertEquals(HeardCredit.Radio(golden), HeardResolver.credit(sample(standIn(0x3F, 0x01), 2), contacts))
    }

    @Test fun outOfRangeOrUnlocatedIsUnidentified() {
        val far = HeardResolver.credit(sample(standIn(0x3F), 6), map(pueblo))
        assertEquals(HeardCredit.Unidentified(Bytes(byteArrayOf(0x3F))), far)
        assertEquals("Repeater 3f, not identified", UnidentifiedRepeater("3f", 1, 0).label)
        // Same radio, heard from Pueblo: now it is in range.
        assertEquals(HeardCredit.Radio(pueblo), HeardResolver.credit(sample(standIn(0x3F), 6, at = 38.27 to -104.62), map(pueblo)))
        // A match with no position is never placed.
        assertTrue(HeardResolver.credit(sample(standIn(0x7A), 1), map(noGps)) is HeardCredit.Unidentified)
        // A chat radio in range with a matching key is never credited as a repeater.
        assertTrue(HeardResolver.credit(sample(standIn(0x3F), 1), map(companion)) is HeardCredit.Unidentified)
        // No match at all.
        assertTrue(HeardResolver.credit(sample(standIn(0xEE), 1), map(golden)) is HeardCredit.Unidentified)
    }

    @Test fun nodeZeroAndUnknownHopsAreCoverageOnly() {
        val contacts = map(golden)
        assertEquals(HeardCredit.CoverageOnly, HeardResolver.credit(sample(0, 3), contacts))
        assertEquals(HeardCredit.CoverageOnly, HeardResolver.credit(sample(0, 0), contacts))
        assertEquals(HeardCredit.CoverageOnly, HeardResolver.credit(sample(golden.num, null), contacts))
        // Meshtastic samples with hops name the far sender, not who was in range.
        val mt = MeshNode(0xA001, longName = "Peak", latitude = 39.75, longitude = -105.0)
        assertEquals(HeardCredit.CoverageOnly, HeardResolver.credit(sample(mt.num, 2, fw = Firmware.MESHTASTIC), map(mt)))
        assertEquals(HeardCredit.Radio(mt), HeardResolver.credit(sample(mt.num, 0, fw = Firmware.MESHTASTIC), map(mt)))

        val r = HeardResolver.derive(listOf(sample(0, 3), sample(0, 0), sample(standIn(0xEE), 2)), mapOf(Firmware.MESHCORE to contacts))
        assertEquals(2, r.coverageOnly)
        assertTrue("node 0 is never a radio", r.direct.isEmpty())
        assertEquals(listOf("ee"), r.unidentified.map { it.hash })
    }

    @Test fun savedRecordingsReDerive() {
        val contacts = map(golden, pueblo, nearer)
        // Stand-in numbers: 1-, 2- and 3-byte hashes padded with zeros.
        assertEquals(Bytes(byteArrayOf(0x3F)), HeardResolver.hashOf(standIn(0x3F), contacts))
        assertEquals(Bytes(byteArrayOf(0x3F, 0x01)), HeardResolver.hashOf(standIn(0x3F, 0x01), contacts))
        assertEquals(Bytes(byteArrayOf(0x3F, 0x01, 0x12)), HeardResolver.hashOf(standIn(0x3F, 0x01, 0x12), contacts))
        // Older recordings credited the first contact whose key matched (here the one in Pueblo):
        // its first key byte is the hash, re-resolved by distance.
        assertEquals(Bytes(byteArrayOf(0x3F)), HeardResolver.hashOf(pueblo.num, contacts))
        assertEquals(HeardCredit.Radio(nearer), HeardResolver.credit(sample(pueblo.num, 5), contacts))
        // A contact heard straight from itself (zero hops) stays that contact.
        assertEquals(HeardCredit.Radio(pueblo), HeardResolver.credit(sample(pueblo.num, 0), contacts))

        val r = HeardResolver.derive(listOf(sample(pueblo.num, 5), sample(standIn(0x3F, 0x01), 2)), mapOf(Firmware.MESHCORE to contacts))
        assertEquals(setOf(nearer.num, golden.num), r.direct.map { it.num }.toSet())
        assertEquals("the far one is only known on the mesh", listOf(pueblo.num), r.known.map { it.num })
    }

    @Test fun ownRadioIsNeverShown() {
        val mine = contact("My Repeater", key(0x3F, 0x09), 39.7390, -104.9900)
        val myCompanion = key(0x55, 0x66)
        val own = { n: Long, fw: Firmware ->
            n == mine.num || (fw == Firmware.MESHCORE && (1..3).any { MeshCoreContact.nodeNumForHash(myCompanion.prefix(it).array) == n })
        }
        val contacts = map(mine, golden)
        // The nearest match is ours: not drawn.
        assertEquals(HeardCredit.Own, HeardResolver.credit(sample(standIn(0x3F), 1), contacts, own))
        // A hash that starts our companion's key.
        assertEquals(HeardCredit.Own, HeardResolver.credit(sample(standIn(0x55), 1), contacts, own))
        val r = HeardResolver.derive(listOf(sample(standIn(0x3F), 1), sample(mine.num, 0)), mapOf(Firmware.MESHCORE to contacts), own,
            alsoDirect = listOf(Firmware.MESHCORE to mine))
        assertTrue(r.direct.none { it.num == mine.num })
        assertTrue(r.known.none { it.num == mine.num })
        assertEquals(0, r.coverageOnly)
    }
}
