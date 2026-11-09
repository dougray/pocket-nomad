// Port of AppTests/CoverageTests.swift and the coverage/drive parts of
// AppTests/DriveAndKnownRadioTests.swift. Coordinates are made up (open ocean).
package org.nomadpocket.mesh

import kotlinx.coroutines.test.TestScope
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.mesh.proto.MeshPBFromRadioVariant
import org.nomadpocket.mesh.proto.MeshPBMyNodeInfo
import org.nomadpocket.mesh.proto.MeshPBNodeInfo
import org.nomadpocket.mesh.proto.MeshPBUser
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos

class CoverageTest {
    private val dir: File = Files.createTempDirectory("coverage").toFile()
    private val prefs = MemoryMeshPrefs()
    private val file = File(dir, "coverage.json")
    private val scope = TestScope()
    private var fix: Fix? = null
    private var clock = 1_800_000_000_000L

    @After fun tearDown() { dir.deleteRecursively() }

    private fun store(names: Map<Long, String> = emptyMap()) =
        CoverageStore(prefs, file, scope, location = { fix }, nodeName = { names[it] ?: "" }, now = { clock })

    private fun at(lat: Double, lon: Double, accuracy: Double = 8.0, ageSeconds: Long = 2) =
        Fix(lat, lon, accuracy, clock - ageSeconds * 1000)

    private val heard = MeshHeard(0xABCD, 6.5f, 1, Firmware.MESHTASTIC)

    @Test fun nothingIsRecordedUntilTurnedOn() {
        val s = store()
        assertFalse("off by default", s.recording)
        assertFalse("sharing off by default", s.sharing)
        fix = at(10.14, -20.39)
        s.record(heard)
        assertTrue(s.samples.isEmpty())
    }

    @Test fun recordsFreshAccurateFixesAndThrottles() {
        val s = store()
        s.recording = true
        fix = at(10.14, -20.39, ageSeconds = 60)
        s.record(heard)
        fix = at(10.14, -20.39, accuracy = 500.0)
        s.record(heard)
        assertTrue(s.samples.isEmpty())

        fix = at(10.14, -20.39)
        s.record(heard)
        assertEquals(1, s.samples.size)
        assertEquals(6.5f, s.samples.first().snr)
        clock += 5_000
        fix = at(10.14, -20.39)
        s.record(heard)
        assertEquals(1, s.samples.size)
        fix = at(10.15, -20.39) // moved ~1 km
        s.record(heard)
        assertEquals(2, s.samples.size)
        s.record(MeshHeard(0x1234, null, null, Firmware.MESHCORE))
        assertEquals(3, s.samples.size)
    }

    @Test fun retentionAndDeleteAll() {
        val s = store()
        s.recording = true
        fix = at(10.14, -20.39)
        s.record(heard)
        s.saveNow()
        clock += 40 * 86_400_000L
        fix = at(10.15, -20.39)
        s.record(heard)
        s.retentionDays = 30
        assertEquals("the 40-day-old sample is pruned", 1, s.samples.size)
        s.deleteAll()
        assertTrue(s.samples.isEmpty())
        assertTrue("deletion is saved", store().samples.isEmpty())
    }

    @Test fun recordingAndSamplesPersist() {
        val s = store()
        s.recording = true
        fix = at(10.14, -20.39)
        s.record(heard)
        s.saveNow()
        val again = store()
        assertTrue(again.recording)
        assertEquals(s.samples, again.samples)
    }

    @Test fun sharingNeedsAPrivateZone() {
        val s = store()
        s.sharing = true
        assertFalse(s.sharing)
        assertThrows<IllegalStateException> { s.exportFile(dir) }
        s.addZone("Home", 10.14, -20.39, 500.0)
        s.sharing = true
        assertTrue(s.sharing)
        assertTrue(s.exportFile(File(dir, "export")).exists())
        s.removeZones(listOf(0))
        assertFalse("removing the last zone turns sharing off", s.sharing)
    }

    @Test fun hexBinningIsDeterministicAndGroupsNearbyPoints() {
        val a = CoverageShare.hex(10.1392, -20.3903)
        assertEquals(a, CoverageShare.hex(10.1392, -20.3903))
        assertEquals("a few metres away", a, CoverageShare.hex(10.13925, -20.39035))
        assertTrue("2 km away", a != CoverageShare.hex(10.16, -20.39))
        val c = CoverageShare.center(a.first, a.second)
        assertEquals(a, CoverageShare.hex(c.first, c.second))
        val corners = CoverageShare.corners(a.first, a.second)
        val width = CoverageShare.distance(corners[0].first, corners[0].second, corners[3].first, corners[3].second)
        assertEquals(2 * 400 * cos(10.14 * PI / 180), width, 30.0)
    }

    @Test fun sharedCellsDropPrivateZonesAndKeepOnlySummaries() {
        val home = CoverageZone(name = "Home", latitude = 10.14, longitude = -20.39, radius = 500.0)
        val day = 1_800_000_000_000L
        val samples = listOf(
            CoverageRecord(day, 10.1401, -20.3901, 5.0, Firmware.MESHTASTIC, 1, 9f, 0),  // at home
            CoverageRecord(day, 10.1445, -20.39, 5.0, Firmware.MESHTASTIC, 1, 4f, 0),    // ~500 m: cell touches home
            CoverageRecord(day, 10.20, -20.39, 5.0, Firmware.MESHTASTIC, 2, -3f, 1),     // 6.7 km away
            CoverageRecord(day + 86_400_000L * 40, 10.20001, -20.39001, 5.0, Firmware.MESHCORE, 3, 2f, 2),
        )
        val cells = CoverageShare.cells(samples, zones = listOf(home))
        assertEquals("only the far cell survives", 1, cells.size)
        val cell = cells.first()
        assertEquals(2, cell.count)
        assertEquals(2f, cell.bestSNR)
        assertEquals(setOf(Firmware.MESHTASTIC, Firmware.MESHCORE), cell.firmwares)
        assertEquals("2027-01", cell.firstMonth)
        assertEquals("2027-02", cell.lastMonth)

        val json = CoverageShare.geoJSON(cells)
        for (secret in listOf("node", "name", "10.2,", "10.20001", "accuracy", "hops", "date\"")) {
            assertFalse("export must not contain $secret", json.contains(secret))
        }
        assertTrue(json.contains("\"best_snr\""))
        assertTrue(json.contains("\"first_month\""))
        val feature = JSONObject(json).getJSONArray("features").getJSONObject(0)
        val ring = feature.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
        assertEquals("a closed hexagon", 7, ring.length())
        assertEquals(setOf("count", "best_snr", "firmware", "first_month", "last_month"),
            feature.getJSONObject("properties").keys().asSequence().toSet())
    }

    @Test fun deleteMeshHistoryKeepsOnlyThisRadio() {
        val h = ServiceHarness()
        try {
            val fake = FakeMeshTransport()
            val radio = FakeMeshCoreRadio(fake)
            radio.waiting.addLast(CoreFrames.channelMessageV3(0, "Ana: hello", 1_790_000_000))
            val svc = h.service(fake)
            svc.start()
            svc.connect(MeshDiscoveredDevice(UUID.randomUUID().toString(), "R", -50, 0, Firmware.MESHCORE))
            fake.emit(MeshTransportEvent.Ready(fake.connectedID!!, "R", Firmware.MESHCORE))
            radio.run()
            assertFalse(svc.messages.isEmpty())
            assertFalse(svc.sortedNodes.isEmpty())
            svc.deleteHistory()
            assertTrue(svc.messages.isEmpty())
            assertTrue(svc.sortedNodes.isEmpty())
            assertEquals("this radio's own entry stays", "Base Camp", svc.myNode?.longName)
            assertTrue("the deletion is saved", h.service().messages.isEmpty())
        } finally { h.cleanup() }
    }

    @Test fun blockingARadioHidesItAndPersists() {
        val h = ServiceHarness()
        try {
            val fake = FakeMeshTransport()
            val radio = FakeMeshCoreRadio(fake)
            val svc = h.service(fake)
            svc.start()
            svc.connect(MeshDiscoveredDevice(UUID.randomUUID().toString(), "R", -50, 0, Firmware.MESHCORE))
            fake.emit(MeshTransportEvent.Ready(fake.connectedID!!, "R", Firmware.MESHCORE))
            radio.run()
            val target = svc.sortedNodes.first()
            svc.block(target.num)
            assertFalse("blocked radios leave the node list", svc.sortedNodes.any { it.num == target.num })
            assertEquals(listOf(target.num), svc.blockedNodes.map { it.num })
            assertTrue(svc.messages.all { it.from != target.num })
            svc.block(svc.myNodeNum)
            assertFalse("can't block your own radio", svc.isBlocked(svc.myNodeNum))
            val reloaded = h.service()
            assertTrue("the block is saved", reloaded.isBlocked(target.num))
            reloaded.unblock(target.num)
            assertFalse(h.service().isBlocked(target.num))
        } finally { h.cleanup() }
    }

    @Test fun meshServiceReportsLiveMeshCoreTrafficOnly() {
        val h = ServiceHarness()
        try {
            val fake = FakeMeshTransport()
            val radio = FakeMeshCoreRadio(fake)
            val svc = h.service(fake)
            val heard = mutableListOf<MeshHeard>()
            svc.onHeard = { heard.add(it) }
            radio.waiting.addLast(CoreFrames.channelMessageV3(0, "Ana: old news", 1_000_000))
            svc.start()
            svc.connect(MeshDiscoveredDevice(UUID.randomUUID().toString(), "R", -50, 0, Firmware.MESHCORE))
            fake.emit(MeshTransportEvent.Ready(fake.connectedID!!, "R", Firmware.MESHCORE))
            radio.run()
            assertTrue("contacts from the handshake and old messages don't count", heard.isEmpty())
            fake.emit(MeshTransportEvent.Frame(CoreFrames.b(listOf(0x80) + radio.ana)))
            assertEquals(1, heard.size)
            assertEquals(Firmware.MESHCORE, heard.first().firmware)
            radio.waiting.addLast(CoreFrames.channelMessageV3(0, "Ana: hi", System.currentTimeMillis() / 1000))
            fake.emit(MeshTransportEvent.Frame(bytes(0x83)))
            radio.run()
            assertEquals(2, heard.size)
            assertEquals(5.5f, heard.last().snr)
        } finally { h.cleanup() }
    }

    // From DriveAndKnownRadioTests.swift (the known-radio parsing tests belong to :app).

    @Test fun optOutEmojiMatchesWithOrWithoutVariationSelector() {
        assertTrue(OptOut.optsOut("Base ⛔"))
        assertTrue(OptOut.optsOut("Base ⛔️"))
        assertTrue(OptOut.optsOut("🛑 Hilltop"))
        assertTrue(OptOut.optsOut("Car🚫"))
        assertFalse(OptOut.optsOut("Valley Repeater 📡"))
    }

    @Test fun optedOutRadiosAreNeverRecordedOrShared() {
        val s = store(mapOf(1L to "Home ⛔", 2L to "Hilltop"))
        s.recording = true
        fix = at(12.27, -30.74)
        s.record(MeshHeard(1, 5f, 0, Firmware.MESHTASTIC))
        s.record(MeshHeard(2, 5f, 0, Firmware.MESHTASTIC))
        assertEquals(listOf(2L), s.samples.map { it.node })
    }

    @Test fun pingsAreSavedZonedAndExported() {
        val s = store()
        s.recordPing(PingOutcome.REACHED, at(12.27, -30.74), Firmware.MESHTASTIC)
        s.recordPing(PingOutcome.NONE, at(12.27, -30.74), Firmware.MESHTASTIC)
        s.recordPing(PingOutcome.RELAYED, at(12.30, -30.70), Firmware.MESHCORE)
        s.saveNow()
        assertEquals("pings persist", 3, store().pings.size)

        s.addZone("Home", 12.30, -30.70, 300.0)
        val cells = s.sharedCells
        assertEquals("the ping inside the private zone is dropped", 1, cells.size)
        assertEquals(0, cells[0].count)
        assertEquals(listOf(1, 0, 1), listOf(cells[0].pingsReached, cells[0].pingsRelayed, cells[0].pingsNone))

        val props = JSONObject(CoverageShare.geoJSON(cells)).getJSONArray("features").getJSONObject(0).getJSONObject("properties")
        assertEquals(1, props.getInt("pings_reached"))
        assertEquals(1, props.getInt("pings_none"))
        assertEquals(0, props.getInt("count"))
        assertFalse(props.has("node"))
    }

    @Test fun pingsBecomeHexagonsWithTheBestAnswerPerNetworkNeverPoints() {
        // A drive past one spot and on: five pings, a few metres apart, then one ~2 km on.
        val pings = listOf(
            CoveragePing(12.2700, -30.7400, PingOutcome.NONE, Firmware.MESHTASTIC),
            CoveragePing(12.2701, -30.7401, PingOutcome.RELAYED, Firmware.MESHTASTIC),
            CoveragePing(12.2702, -30.7400, PingOutcome.NONE, Firmware.MESHTASTIC),
            CoveragePing(12.2701, -30.7400, PingOutcome.REACHED, Firmware.MESHCORE),
            CoveragePing(12.2700, -30.7402, PingOutcome.NONE, Firmware.MESHCORE),
            CoveragePing(12.2900, -30.7400, PingOutcome.NONE, Firmware.MESHCORE),
        )
        val samples = listOf(CoverageSample(12.2701, -30.7401, 4f, Firmware.MESHTASTIC), CoverageSample(12.2700, -30.7400, -2f, Firmware.MESHTASTIC))
        val cells = CoverageShare.mapCells(CoverageData(samples, pings))
        assertEquals("one hexagon per place, not one dot per ping", 2, cells.size)
        val here = cells.first { it.pings == 5 }
        assertEquals(mapOf(Firmware.MESHTASTIC to PingOutcome.RELAYED, Firmware.MESHCORE to PingOutcome.REACHED), here.bestPing)
        assertEquals(PingOutcome.REACHED, here.bestPing(null))
        assertEquals(PingOutcome.RELAYED, here.bestPing(Firmware.MESHTASTIC))
        assertEquals(2, here.heard)
        assertEquals(4f, here.bestSNR)
        // Drawn at the hexagon's own corners: no ping's exact position is in it.
        val corners = here.corners.toSet()
        assertFalse(pings.any { (it.latitude to it.longitude) in corners || (it.latitude to it.longitude) == here.center })
        val there = cells.first { it.pings == 1 }
        assertEquals(PingOutcome.NONE, there.bestPing(null))
        assertNull("no Meshtastic ping there", there.bestPing(Firmware.MESHTASTIC))

        // The network filter keeps only that network's recordings and pings.
        val mt = CoverageShare.mapCells(CoverageData(samples, pings), Firmware.MESHTASTIC)
        assertEquals(1, mt.size)
        assertEquals(setOf(Firmware.MESHTASTIC), mt[0].firmwares)
        assertEquals(3, mt[0].pings)
    }

    @Test fun anExportHoldsHexagonsNotPings() {
        val s = store()
        s.recordPing(PingOutcome.REACHED, at(12.2700, -30.7400), Firmware.MESHTASTIC)
        s.recordPing(PingOutcome.NONE, at(12.2703, -30.7402), Firmware.MESHTASTIC)
        s.recordPing(PingOutcome.RELAYED, at(12.3000, -30.7000), Firmware.MESHCORE)
        s.addZone("Somewhere else", 12.50, -30.50, 100.0)
        s.sharing = true
        val text = s.exportFile(File(dir, "out")).readText()
        val features = JSONObject(text).getJSONArray("features")
        assertEquals("two hexagons for three pings", 2, features.length())
        for (i in 0 until features.length()) {
            assertEquals("Polygon", features.getJSONObject(i).getJSONObject("geometry").getString("type"))
        }
        assertFalse("no ping position is written", text.contains("12.27,") || text.contains("-30.74,") || text.contains("12.3,"))
        assertEquals("the raw pings stay on the phone for the cells", 3, s.pings.size)
    }

    @Test fun cellsWithoutPingsLeaveThePingFieldsOut() {
        val sample = CoverageRecord(clock, 12.27, -30.74, 5.0, Firmware.MESHTASTIC, 9, 3f, 0)
        val props = JSONObject(CoverageShare.geoJSON(CoverageShare.cells(listOf(sample), zones = emptyList())))
            .getJSONArray("features").getJSONObject(0).getJSONObject("properties")
        assertFalse(props.has("pings_reached"))
        assertEquals(1, props.getInt("count"))
    }
}

/** Drive mode's loop logic (DriveMode.swift): notes, rate limit, auto-off, coverage switch. */
class DriveModeTest {
    private val h = ServiceHarness()
    private val prefs = MemoryMeshPrefs()
    private var clock = System.currentTimeMillis()
    private var fix: Fix? = null
    private val active = mutableListOf<Boolean>()

    @After fun tearDown() = h.cleanup()

    private fun connectedMesh(): Pair<MeshService, FakeMeshTransport> {
        val fake = FakeMeshTransport()
        val svc = MeshService(fake, MeshStore(h.dir), h.prefs, h.scope, now = { clock })
        svc.start()
        svc.connect(MeshDiscoveredDevice("dev", "R", -50, 0))
        fake.becomeReady()
        fake.enqueue(MeshPBFromRadioVariant.MyInfo(MeshPBMyNodeInfo(myNodeNum = 1)))
        fake.enqueue(MeshPBFromRadioVariant.NodeInfo(MeshPBNodeInfo(num = 2, user = MeshPBUser(longName = "Peak"))))
        fake.enqueue(MeshPBFromRadioVariant.ConfigCompleteID(fake.lastWantConfigID()!!))
        fake.pump()
        return svc to fake
    }

    private fun drive(mesh: MeshService): Pair<DriveMode, CoverageStore> {
        val coverage = CoverageStore(prefs, File(h.dir, "c.json"), h.scope, location = { fix }, now = { clock })
        return DriveMode(mesh, coverage, prefs, h.scope, now = { clock }, onActiveChanged = { active.add(it) }) to coverage
    }

    @Test fun pingsAreRateLimitedByTimeAndDistance() {
        val (mesh, fake) = connectedMesh()
        val (d, coverage) = drive(mesh)
        assertTrue("pinging is on by default", d.pinging)
        d.start()
        assertEquals(listOf(true), active)
        assertTrue("drive mode turns recording on", coverage.recording)
        d.tick()
        assertEquals("Waiting for GPS", d.state.pingNote)
        fix = Fix(12.27, -30.74, 5.0, clock)
        d.tick()
        assertNull(d.state.pingNote)
        assertEquals(1, d.state.sent)
        assertTrue(d.state.waiting)
        val p = fake.lastSentPacket()!!
        // A neighbour repeats it.
        fake.enqueue(MeshPBFromRadioVariant.Packet(org.nomadpocket.mesh.proto.MeshPBPacket(from = 1, to = 1,
            decoded = org.nomadpocket.mesh.proto.MeshPBData(portnum = 5, payload = org.nomadpocket.mesh.proto.MeshPBRouting(0).encode(), requestID = p.id), id = 9)))
        fake.emit(MeshTransportEvent.FromNumChanged); fake.pump()
        assertEquals(1, d.state.relayed)
        assertEquals(PingOutcome.RELAYED, d.state.lastOutcome)
        assertEquals(1, coverage.pings.size)

        clock += 60_000
        fix = Fix(12.27, -30.74, 5.0, clock) // a minute later but hasn't moved
        d.tick()
        assertEquals(1, d.state.sent)
        fix = Fix(12.28, -30.74, 5.0, clock) // ~1.1 km on
        d.tick()
        assertEquals(2, d.state.sent)
        d.stop()
        assertEquals(listOf(true, false), active)
        assertFalse("recording goes back off", coverage.recording)
    }

    @Test fun notesExplainWhyNoPingAndItTurnsItselfOff() {
        val (mesh, _) = connectedMesh()
        val (d, _) = drive(mesh)
        d.target = 0x9999
        d.start()
        d.tick()
        assertEquals("The radio to ping isn't known on this mesh", d.state.pingNote)
        mesh.disconnect()
        d.tick()
        assertEquals("Waiting for the radio", d.state.pingNote)
        clock += 12 * 3600 * 1000L
        d.tick()
        assertTrue("drive mode runs until the user stops it", d.state.active)
    }
}
