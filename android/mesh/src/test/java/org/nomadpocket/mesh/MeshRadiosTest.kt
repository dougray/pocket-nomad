// Two radios at once (MeshRadios): one Meshtastic and one MeshCore link, each
// on its own fake transport. Covers both links up together, messages staying
// on their own network, SOS on every radio, replacing a radio of the same
// firmware, moving a radio the scan mis-labelled, migrating the single
// remembered radio, and coverage + drive-mode pings tagged by network.
// Coordinates are made up.
package org.nomadpocket.mesh

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.mesh.proto.*
import java.io.File

class MeshRadiosTest {
    private val h = ServiceHarness()
    private var clock = System.currentTimeMillis()
    private var fix: Fix? = null
    private val me = 0x1111_1111L
    /** The same number on both networks: MeshCore's Ana (from her key) and a Meshtastic node. */
    private val shared = 0x4433_22AAL

    private val scanner = FakeMeshTransport()
    private val mt = FakeMeshTransport()
    private val mc = FakeMeshTransport()
    private val core = FakeMeshCoreRadio(mc)

    @After fun tearDown() = h.cleanup()

    private fun radios(): MeshRadios = MeshRadios.create(h.prefs, h.dir, h.scope, scanner,
        { if (it == Firmware.MESHCORE) mc else mt }, location = { fix }, now = { clock })

    private fun nowSec() = clock / 1000

    private fun MeshRadios.connectMeshtastic(id: String = "mt-1") {
        connect(MeshDiscoveredDevice(id, "Trail Radio", -60, 0, Firmware.MESHTASTIC))
        mt.emit(MeshTransportEvent.Ready(id, "Trail Radio", Firmware.MESHTASTIC))
        mt.enqueue(MeshPBFromRadioVariant.MyInfo(MeshPBMyNodeInfo(myNodeNum = me)))
        mt.enqueue(MeshPBFromRadioVariant.NodeInfo(MeshPBNodeInfo(num = me, user = MeshPBUser(longName = "My Radio", shortName = "ME"))))
        mt.enqueue(MeshPBFromRadioVariant.NodeInfo(MeshPBNodeInfo(num = shared, user = MeshPBUser(longName = "Peak", shortName = "PK"),
            position = MeshPBPosition(latitudeI = 101_234_560, longitudeI = -206_543_210), lastHeard = nowSec() - 60)))
        mt.enqueue(MeshPBFromRadioVariant.Channel(MeshPBChannel(index = 0, name = "LongFast", hasPSK = true, role = 1)))
        mt.enqueue(MeshPBFromRadioVariant.LoraConfig(MeshPBLoRaConfig(region = 1, hopLimit = 3)))
        mt.enqueue(MeshPBFromRadioVariant.ConfigCompleteID(mt.lastWantConfigID()!!))
        mt.pump()
    }

    private fun MeshRadios.connectMeshCore(id: String = "mc-1") {
        connect(MeshDiscoveredDevice(id, "Ridge Companion", -70, 0, Firmware.MESHCORE))
        mc.emit(MeshTransportEvent.Ready(id, "Ridge Companion", Firmware.MESHCORE))
        core.run()
    }

    private fun both(): MeshRadios = radios().apply {
        start()
        assertNull("a free slot replaces nothing", replacing(MeshDiscoveredDevice("x", "X", 0, 0, Firmware.MESHCORE)))
        connectMeshtastic()
        connectMeshCore()
    }

    private fun coreWritesWithCode(code: Int) = mc.writes.filter { (it[0].toInt() and 0xFF) == code }

    // MARK: Two links

    @Test fun bothRadiosConnectAtOnceEachOnItsOwnLink() {
        val r = both()
        val mtLink = r.link(Firmware.MESHTASTIC)
        val mcLink = r.link(Firmware.MESHCORE)
        assertEquals(MeshConnectionState.Connected("Trail Radio"), mtLink.connection)
        assertEquals(MeshConnectionState.Connected("Ridge Companion"), mcLink.connection)
        assertEquals(listOf(Firmware.MESHTASTIC, Firmware.MESHCORE), r.connected.map { it.first })
        assertFalse("both slots are taken", r.canAdd)
        assertEquals("mt-1", mt.connectedID)
        assertEquals("mc-1", mc.connectedID)
        assertNull("the scanner never connects", scanner.connectedID)
        // Each link keeps its own nodes and channels.
        assertEquals("LongFast", mtLink.radio.primaryChannelName)
        assertEquals("Public", mcLink.radio.primaryChannelName)
        assertEquals("Peak", mtLink.nodes[shared]?.longName)
        assertEquals("Ana", mcLink.nodes[shared]?.longName)
        // The merged node list has both, each with its network.
        val merged = r.sortedNodes.map { it.first to it.second.displayName }.toSet()
        assertTrue(merged.contains(Firmware.MESHTASTIC to "Peak"))
        assertTrue(merged.contains(Firmware.MESHCORE to "Ana"))
        // And each saves to its own folder.
        r.saveNow()
        assertTrue(File(h.dir, "meshtastic/mesh.json").exists())
        assertTrue(File(h.dir, "meshcore/mesh.json").exists())
    }

    @Test fun bothRadiosReconnectAtLaunch() {
        both()
        // A new launch with the same prefs and files.
        val again = MeshRadios.create(h.prefs, h.dir, h.scope, FakeMeshTransport(), { if (it == Firmware.MESHCORE) mc else mt })
        mt.connectedID = null; mc.connectedID = null
        again.start()
        assertEquals("mt-1", mt.connectedID)
        assertEquals("mc-1", mc.connectedID)
        assertEquals(2, again.inUse.size)
        assertFalse(again.canAdd)
    }

    // MARK: Messages stay on their network

    @Test fun messagesGoOutAndComeInOnTheirOwnNetwork() {
        val r = both()
        val mtLink = r.link(Firmware.MESHTASTIC)
        val mcLink = r.link(Firmware.MESHCORE)
        val coreBefore = mc.writes.size
        assertTrue(mtLink.send("hello trail", MeshConversation.Channel(0)))
        assertEquals("hello trail", String(mt.lastSentPacket()!!.decoded!!.payload))
        assertEquals("nothing went to the MeshCore radio", coreBefore, mc.writes.size)

        val mtBefore = mt.writes.size
        assertTrue(mcLink.send("hello ridge", MeshConversation.Channel(0)))
        core.run()
        assertEquals(mtBefore, mt.writes.size)
        assertTrue(coreWritesWithCode(3).any { String(it).contains("hello ridge") })

        // A reply in a direct thread uses that network's addressing.
        assertTrue(mcLink.send("hi Ana", MeshConversation.Direct(shared)))
        core.run()
        assertTrue(coreWritesWithCode(2).any { String(it).contains("hi Ana") })

        // Incoming: a Meshtastic text shows up only in the Meshtastic link.
        mt.enqueue(MeshPBFromRadioVariant.Packet(MeshPBPacket(from = shared, to = MeshConstants.BROADCAST_NUM,
            decoded = MeshPBData(portnum = MeshPort.TEXT_MESSAGE, payload = "from the peak".toByteArray()), id = 77, rxTime = nowSec())))
        mt.emit(MeshTransportEvent.FromNumChanged); mt.pump()
        assertTrue(mtLink.messages.any { it.text == "from the peak" })
        assertFalse(mcLink.messages.any { it.text == "from the peak" })
        // And a MeshCore one only in the MeshCore link (no bridging).
        mc.emit(MeshTransportEvent.Frame(CoreFrames.channelMessageV3(0, "Ana: on the ridge", nowSec())))
        assertTrue(mcLink.messages.any { it.text.contains("on the ridge") })
        assertFalse(mtLink.messages.any { it.text.contains("on the ridge") })
    }

    // MARK: SOS

    @Test fun sosGoesOutOnEveryConnectedRadio() = runBlocking {
        val r = both()
        fix = Fix(10.5, -20.25, 5.0, clock)
        val results = r.sendSOS(includeLocation = true)
        core.run()
        assertEquals(listOf(Firmware.MESHTASTIC, Firmware.MESHCORE), results.map { it.firmware })
        assertEquals(listOf("Trail Radio", "Ridge Companion"), results.map { it.radioName })
        assertTrue(results.all { it.text?.contains("Location: 10.50000, -20.25000") == true })
        assertTrue(String(mt.lastSentPacket()!!.decoded!!.payload).startsWith("SOS"))
        assertTrue(coreWritesWithCode(3).any { String(it).contains("SOS") })
        // MeshCore's shorter limit is respected on that radio.
        assertTrue(MeshText.byteCount(results[1].text!!) <= Firmware.MESHCORE.maxTextBytes)
    }

    @Test fun sosReportsEachRadioAndSkipsOnesNotConnected() = runBlocking {
        val r = both()
        r.link(Firmware.MESHCORE).disconnect()
        val results = r.sendSOS(includeLocation = false)
        assertEquals(listOf(Firmware.MESHTASTIC), results.map { it.firmware })
        assertTrue(results[0].text!!.contains("Location unknown"))
        r.link(Firmware.MESHTASTIC).disconnect()
        assertTrue(r.sendSOS(false).isEmpty())
    }

    @Test fun sosSummarySaysHowEachRadioDid() {
        val ok = MeshSOSResult(Firmware.MESHTASTIC, "Trail Radio", "SOS - I need help. Location unknown.")
        val failed = MeshSOSResult(Firmware.MESHCORE, "Ridge Companion", null)
        val two = sosSummary(listOf(ok, failed), withLocation = false)
        assertTrue(two.contains("Meshtastic (Trail Radio): sent"))
        assertTrue(two.contains("MeshCore (Ridge Companion): NOT sent"))
        assertFalse("one radio reads as before", sosSummary(listOf(ok), false).contains("Meshtastic ("))
        assertTrue(sosSummary(listOf(failed), false).startsWith("Could not send"))
    }

    // MARK: Slots

    @Test fun aSecondRadioOfTheSameFirmwareReplacesTheFirst() {
        val r = both()
        val other = MeshDiscoveredDevice("mt-2", "Spare Radio", -50, 0, Firmware.MESHTASTIC)
        assertEquals("Trail Radio", r.replacing(other))
        assertNull("reconnecting the same radio replaces nothing",
            r.replacing(MeshDiscoveredDevice("mt-1", "Trail Radio", -50, 0, Firmware.MESHTASTIC)))
        r.connect(other)
        assertEquals("mt-2", mt.connectedID)
        assertEquals("mt-2", r.link(Firmware.MESHTASTIC).lastDeviceID)
        assertEquals("the MeshCore radio is untouched", "mc-1", mc.connectedID)
        assertTrue(r.link(Firmware.MESHCORE).connection.isConnected)
    }

    @Test fun aRadioTheScanMislabelledMovesToItsFirmwaresSlot() {
        val r = radios()
        r.start()
        r.connectMeshtastic()
        // Advertised no service UUID, so the scan guessed Meshtastic; it is MeshCore.
        r.connect(MeshDiscoveredDevice("odd", "Quiet Radio", -60, 0, Firmware.MESHTASTIC))
        assertEquals("odd", mt.connectedID)
        mt.emit(MeshTransportEvent.Ready("odd", "Quiet Radio", Firmware.MESHCORE))
        assertEquals("it left the Meshtastic slot", null, r.link(Firmware.MESHTASTIC).lastDeviceID)
        assertEquals("odd", mc.connectedID)
        assertEquals("odd", r.link(Firmware.MESHCORE).lastDeviceID)
    }

    // MARK: Migration

    @Test fun theSingleRememberedRadioMovesIntoItsFirmwaresSlot() {
        val old = MeshSnapshot(
            messages = listOf(MeshMessage(packetID = 5, from = 9, to = MeshConstants.BROADCAST_NUM, channel = 0, text = "old chat",
                dateMillis = clock, isOutgoing = false, delivery = MeshDeliveryState.Received)),
            radio = MeshRadioInfo(firmware = Firmware.MESHCORE, myNodeNum = 3))
        MeshStore(h.dir).save(old)
        h.prefs.putString("mesh.lastDeviceID", "core-radio")
        h.prefs.putString("mesh.lastDeviceName", "Old Companion")
        h.prefs.putBoolean("mesh.autoReconnect", true)
        h.prefs.putString("mesh.blocked", "42")
        h.prefs.putLong("drive.target", 9)

        val r = radios()
        assertFalse(File(h.dir, "mesh.json").exists())
        assertTrue(File(h.dir, "meshcore/mesh.json").exists())
        assertNull(h.prefs.getString("mesh.lastDeviceID"))
        val link = r.link(Firmware.MESHCORE)
        assertEquals("core-radio", link.lastDeviceID)
        assertEquals("Old Companion", link.lastDeviceName)
        assertEquals(setOf(42L), link.blocked)
        assertEquals("old chat", link.messages.single().text)
        assertNull(r.link(Firmware.MESHTASTIC).lastDeviceID)
        assertEquals(9L, h.prefs.getLong("drive.target.meshcore", 0))
        assertFalse(h.prefs.contains("drive.target"))
        // It reconnects in its slot at launch.
        r.start()
        assertEquals("core-radio", mc.connectedID)
        assertNull(mt.connectedID)
        // Migration runs once.
        h.prefs.putString("mesh.lastDeviceID", "stray")
        MeshRadios.migrate(h.prefs, h.dir)
        assertEquals("stray", h.prefs.getString("mesh.lastDeviceID"))
    }

    @Test fun aRadioWithNoSavedFirmwareMigratesAsMeshtastic() {
        h.prefs.putString("mesh.lastDeviceID", "aa:bb")
        h.prefs.putString("mesh.lastDeviceName", "Old Radio")
        h.prefs.putBoolean("mesh.autoReconnect", true)
        val r = radios()
        assertEquals("aa:bb", r.link(Firmware.MESHTASTIC).lastDeviceID)
        assertNull(r.link(Firmware.MESHCORE).lastDeviceID)
    }

    // MARK: Coverage and drive mode from both radios

    private fun coverage(r: MeshRadios): CoverageStore {
        val c = CoverageStore(h.prefs, File(h.dir, "coverage.json"), h.scope, location = { fix },
            nodeName = { r.nodeName(it) }, now = { clock })
        r.links.values.forEach { it.onHeard = { heard -> c.record(heard) } }
        c.recording = true
        return c
    }

    private fun heardOnBoth() {
        mt.enqueue(MeshPBFromRadioVariant.Packet(MeshPBPacket(from = shared, to = MeshConstants.BROADCAST_NUM,
            decoded = MeshPBData(portnum = MeshPort.POSITION, payload = ByteArray(0)), id = 88, rxTime = nowSec(), rxSNR = 6f)))
        mt.emit(MeshTransportEvent.FromNumChanged); mt.pump()
        mc.emit(MeshTransportEvent.Frame(CoreFrames.b(listOf(0x80) + core.ana)))
    }

    @Test fun bothRadiosRecordCoverageTaggedWithTheirNetwork() {
        val r = both()
        val c = coverage(r)
        fix = Fix(10.2, -20.4, 5.0, clock)
        heardOnBoth()
        // Same node number, same place, same moment: still two records, one per network.
        assertEquals(listOf(Firmware.MESHTASTIC, Firmware.MESHCORE), c.samples.map { it.firmware })
        assertEquals(listOf(shared, shared), c.samples.map { it.node })
        heardOnBoth()
        assertEquals("the 20 s limit applies per network", 2, c.samples.size)

        c.addZone("Far away", 0.0, 0.0, 100.0)
        c.sharing = true
        assertEquals(setOf(Firmware.MESHTASTIC, Firmware.MESHCORE), c.sharedCells.single().firmwares)
        assertEquals(1, c.sharedCells(Firmware.MESHCORE).single().count)
        assertEquals(setOf(Firmware.MESHCORE), c.sharedCells(Firmware.MESHCORE).single().firmwares)
        val props = JSONObject(CoverageShare.geoJSON(c.sharedCells)).getJSONArray("features").getJSONObject(0).getJSONObject("properties")
        assertEquals("[\"meshcore\",\"meshtastic\"]", props.getJSONArray("firmware").toString())
        assertEquals(2, props.getInt("count"))

        // Saved and read back with the network kept.
        c.saveNow()
        val again = CoverageStore(h.prefs, File(h.dir, "coverage.json"), h.scope, location = { fix }, now = { clock })
        assertEquals(c.samples.map { it.firmware }, again.samples.map { it.firmware })
    }

    @Test fun coverageFilesWrittenBeforeTwoRadiosStillLoad() {
        val file = File(h.dir, "old-coverage.json")
        file.writeText("""[{"date":$clock,"latitude":10.1,"longitude":-20.1,"accuracy":5,"firmware":"meshtastic","node":7,"snr":3.5}]""")
        val c = CoverageStore(h.prefs, file, h.scope, location = { null }, now = { clock })
        assertEquals(Firmware.MESHTASTIC, c.samples.single().firmware)
    }

    @Test fun driveModePingsEachRadioStaggeredAndTagsEachResult() {
        val r = both()
        val c = coverage(r)
        val d = DriveMode({ r.inUse }, c, h.prefs, h.scope, now = { clock })
        d.setTarget(Firmware.MESHCORE, shared) // MeshCore pings need a target: Ana
        d.start()
        d.tick()
        assertEquals("Waiting for GPS", d.state.pingNote)
        fix = Fix(10.2, -20.4, 5.0, clock)
        d.tick()
        // Meshtastic went first; MeshCore waits out the stagger.
        assertEquals(1, d.state.sent)
        assertEquals(1, d.state.radios[Firmware.MESHTASTIC]?.sent)
        assertNull(d.state.radios[Firmware.MESHCORE]?.sent?.takeIf { it > 0 })
        clock += DriveMode.STAGGER_MILLIS
        fix = Fix(10.2, -20.4, 5.0, clock)
        d.tick()
        core.run()
        assertEquals(2, d.state.sent)
        assertEquals(1, d.state.radios[Firmware.MESHCORE]?.sent)

        // Answers come back on each network.
        val ping = mt.lastSentPacket()!!
        assertEquals(MeshService.PING_PORT, ping.decoded!!.portnum)
        mt.enqueue(MeshPBFromRadioVariant.Packet(MeshPBPacket(from = me, to = me,
            decoded = MeshPBData(portnum = MeshPort.ROUTING, payload = MeshPBRouting(0).encode(), requestID = ping.id), id = 99)))
        mt.emit(MeshTransportEvent.FromNumChanged); mt.pump()
        mc.emit(MeshTransportEvent.Frame(CoreFrames.sendConfirmed(0xA0A0_0001L)))
        assertEquals(listOf(Firmware.MESHTASTIC to PingOutcome.RELAYED, Firmware.MESHCORE to PingOutcome.REACHED),
            c.pings.map { it.firmware to it.outcome })
        assertEquals(1, d.state.relayed)
        assertEquals(1, d.state.reached)
        assertFalse(d.state.waiting)
    }

    @Test fun driveModeNamesTheRadioANoteIsAbout() {
        val r = both()
        val c = coverage(r)
        val d = DriveMode({ r.inUse }, c, h.prefs, h.scope, now = { clock })
        fix = Fix(10.2, -20.4, 5.0, clock)
        d.start()
        d.tick()
        assertEquals("MeshCore: Waiting to hear a repeater", d.state.pingNote)
        assertEquals(1, d.state.sent) // Meshtastic still pings
        r.link(Firmware.MESHTASTIC).disconnect()
        d.tick()
        assertEquals("Meshtastic: Waiting for the radio · MeshCore: Waiting to hear a repeater", d.state.pingNote)
    }

    @Test fun driveModePingsTheRepeaterHeardWithoutAChoice() {
        val r = both()
        val c = coverage(r)
        val d = DriveMode({ r.inUse }, c, h.prefs, h.scope, now = { clock })
        fix = Fix(10.2, -20.4, 5.0, clock)
        mc.emit(MeshTransportEvent.Frame(CoreFrames.rxLog(5f, CoreFrames.packet(1, 5, listOf(listOf(0xC7)), listOf(1)))))
        d.start()
        d.tick()
        clock += DriveMode.STAGGER_MILLIS
        fix = Fix(10.2, -20.4, 5.0, clock)
        d.tick()
        core.run()
        assertEquals(1, d.state.radios[Firmware.MESHCORE]?.sent)
        assertEquals(1, coreWritesWithCode(36).size)
    }

    @Test fun driveModeSurvivesTheAppBeingClosedAndLogsWhy() {
        val r = both()
        val c = coverage(r)
        val log = DriveLog(File(h.dir, "drive-log.txt"), now = { clock })
        val d = DriveMode({ r.inUse }, c, h.prefs, h.scope, now = { clock }, log = log::note)
        d.start()
        val started = d.state.startedAtMillis
        d.tick()
        // Android closes the app; a new launch picks the drive back up with the same start time.
        val again = DriveMode({ r.inUse }, c, h.prefs, h.scope, now = { clock }, log = log::note)
        again.resumeIfNeeded()
        assertTrue(again.state.active)
        assertEquals(started, again.state.startedAtMillis)
        again.stop()
        val third = DriveMode({ r.inUse }, c, h.prefs, h.scope, now = { clock }, log = log::note)
        third.resumeIfNeeded()
        assertFalse("a drive turned off stays off", third.state.active)
        val text = File(h.dir, "drive-log.txt").readText()
        listOf("drive mode on", "MeshCore radio connected", "still running", "drive mode resumed", "drive mode off: turned off")
            .forEach { assertTrue(it, text.contains(it)) }
    }
}
