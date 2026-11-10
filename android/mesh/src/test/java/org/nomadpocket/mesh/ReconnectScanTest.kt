// The reconnect scan: while a remembered radio should be connected and isn't,
// MeshRadios keeps the scanner's low-power background scan looking for it, a
// sighting connects that radio's link right away, and the scan stops once
// every wanted radio is connected. "Not with me today", a user disconnect and
// forgetting the radio all stop it; the device picker's scan is separate and
// never filled or stopped by it. No real Bluetooth: fake transports.
package org.nomadpocket.mesh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.mesh.proto.MeshPBFromRadioVariant
import org.nomadpocket.mesh.proto.MeshPBMyNodeInfo
import org.nomadpocket.mesh.proto.MeshPBNodeInfo
import org.nomadpocket.mesh.proto.MeshPBUser

class ReconnectScanTest {
    private val h = ServiceHarness()
    private var clock = 1_800_000_000_000L

    private val scanner = FakeMeshTransport()
    private val mt = FakeMeshTransport()
    private val mc = FakeMeshTransport()
    private val core = FakeMeshCoreRadio(mc)

    @After fun tearDown() = h.cleanup()

    private fun radios(scanner: FakeMeshTransport = this.scanner, mt: FakeMeshTransport = this.mt, mc: FakeMeshTransport = this.mc) =
        MeshRadios.create(h.prefs, h.dir, h.scope, scanner, { if (it == Firmware.MESHCORE) mc else mt }, now = { clock })

    private fun FakeMeshTransport.meshtasticHandshake(id: String = "mt-1") {
        emit(MeshTransportEvent.Ready(id, "Trail Radio", Firmware.MESHTASTIC))
        enqueue(MeshPBFromRadioVariant.MyInfo(MeshPBMyNodeInfo(myNodeNum = 1)))
        enqueue(MeshPBFromRadioVariant.NodeInfo(MeshPBNodeInfo(num = 2, user = MeshPBUser(longName = "Peak"))))
        enqueue(MeshPBFromRadioVariant.ConfigCompleteID(lastWantConfigID()!!))
        pump()
    }

    private fun MeshRadios.connectMeshtastic() {
        connect(MeshDiscoveredDevice("mt-1", "Trail Radio", -60, 0, Firmware.MESHTASTIC))
        mt.meshtasticHandshake()
    }

    private fun MeshRadios.connectMeshCore() {
        connect(MeshDiscoveredDevice("mc-1", "Ridge Companion", -70, 0, Firmware.MESHCORE))
        mc.emit(MeshTransportEvent.Ready("mc-1", "Ridge Companion", Firmware.MESHCORE))
        core.run()
    }

    private fun lose(fake: FakeMeshTransport, id: String) =
        fake.emit(MeshTransportEvent.Disconnected(id, "Link lost: out of range (Bluetooth status 8, link timed out).", true))

    @Test fun noScanWithoutARememberedRadio() {
        val r = radios()
        r.start()
        assertEquals(emptySet<String>(), scanner.reconnectTargets)
        assertEquals(emptySet<String>(), r.reconnectWanted)
    }

    @Test fun scansWhileTheRadioIsLostAndStopsWhenItIsBack() {
        val r = radios()
        r.start()
        r.connectMeshtastic()
        assertEquals(MeshConnectionState.Connected("Trail Radio"), r.link(Firmware.MESHTASTIC).connection)
        assertEquals("connected: no scan", emptySet<String>(), scanner.reconnectTargets)

        lose(mt, "mt-1")
        assertEquals(setOf("mt-1"), scanner.reconnectTargets)

        // Still scanning after it goes Not nearby (that is exactly when the scan matters).
        clock += MeshService.NOT_NEARBY_MILLIS
        r.link(Firmware.MESHTASTIC).checkLink()
        assertEquals(MeshConnectionState.NotNearby("Trail Radio"), r.link(Firmware.MESHTASTIC).connection)
        assertEquals(setOf("mt-1"), scanner.reconnectTargets)

        mt.meshtasticHandshake()
        assertEquals(MeshConnectionState.Connected("Trail Radio"), r.link(Firmware.MESHTASTIC).connection)
        assertEquals("every wanted radio connected: scan stops", emptySet<String>(), scanner.reconnectTargets)
        assertTrue("never a device-picker scan", !scanner.scanning)
    }

    @Test fun aRememberedRadioIsLookedForFromLaunch() {
        radios().apply { start(); connectMeshtastic() }
        val scanner2 = FakeMeshTransport()
        val mt2 = FakeMeshTransport()
        val r2 = radios(scanner2, mt2, FakeMeshTransport())
        r2.start()
        assertTrue("trying, not linked", !r2.link(Firmware.MESHTASTIC).connection.isLinkUp)
        assertEquals(setOf("mt-1"), scanner2.reconnectTargets)
    }

    @Test fun aSightingConnectsRightAwayButNotOverAndOver() {
        val r = radios()
        r.start()
        r.connectMeshtastic()
        lose(mt, "mt-1")
        clock += MeshService.NOT_NEARBY_MILLIS
        r.link(Firmware.MESHTASTIC).checkLink()
        mt.connects.clear()

        scanner.emit(MeshTransportEvent.Sighted("someone-else"))
        assertEquals("another radio is ignored", emptyList<String>(), mt.connects)

        scanner.emit(MeshTransportEvent.Sighted("mt-1"))
        assertEquals(listOf("mt-1"), mt.connects)
        assertEquals("away until Ready, as before", MeshConnectionState.NotNearby("Trail Radio"), r.link(Firmware.MESHTASTIC).connection)

        // Adverts keep coming while that connect is under way: leave it alone.
        clock += MeshService.SIGHTED_RETRY_MILLIS - 1
        repeat(5) { scanner.emit(MeshTransportEvent.Sighted("mt-1")) }
        assertEquals(listOf("mt-1"), mt.connects)

        // That attempt failed and it is still advertising: try again.
        clock += 1
        scanner.emit(MeshTransportEvent.Sighted("mt-1"))
        assertEquals(listOf("mt-1", "mt-1"), mt.connects)
        assertEquals("the other slot never connects it", emptyList<String>(), mc.connects)

        mt.meshtasticHandshake()
        mt.connects.clear()
        scanner.emit(MeshTransportEvent.Sighted("mt-1"))
        assertEquals("connected: sightings do nothing", emptyList<String>(), mt.connects)
    }

    @Test fun notWithMeDisconnectAndForgetStopTheScan() {
        val r = radios()
        r.start()
        r.connectMeshtastic()
        val link = r.link(Firmware.MESHTASTIC)

        lose(mt, "mt-1")
        link.notWithMeToday()
        assertEquals(emptySet<String>(), scanner.reconnectTargets)
        mt.connects.clear()
        scanner.emit(MeshTransportEvent.Sighted("mt-1"))
        assertEquals("not with me today: no reconnect", emptyList<String>(), mt.connects)

        link.reconnectLast()
        assertEquals("Connect looks for it again", setOf("mt-1"), scanner.reconnectTargets)
        link.disconnect()
        assertEquals("user disconnect", emptySet<String>(), scanner.reconnectTargets)

        link.reconnectLast()
        assertEquals(setOf("mt-1"), scanner.reconnectTargets)
        link.forgetDevice()
        assertEquals("forgotten", emptySet<String>(), scanner.reconnectTargets)
    }

    @Test fun twoRadiosEachLookedForUntilBothAreBack() {
        val r = radios()
        r.start()
        r.connectMeshtastic()
        r.connectMeshCore()
        assertEquals(emptySet<String>(), scanner.reconnectTargets)
        lose(mt, "mt-1")
        lose(mc, "mc-1")
        assertEquals(setOf("mt-1", "mc-1"), scanner.reconnectTargets)

        scanner.emit(MeshTransportEvent.Sighted("mc-1"))
        assertEquals(listOf("mc-1"), mc.connects.takeLast(1))
        mc.emit(MeshTransportEvent.Ready("mc-1", "Ridge Companion", Firmware.MESHCORE))
        core.run()
        assertEquals(MeshConnectionState.Connected("Ridge Companion"), r.link(Firmware.MESHCORE).connection)
        assertEquals("still looking for the other one", setOf("mt-1"), scanner.reconnectTargets)

        mt.meshtasticHandshake()
        assertEquals(emptySet<String>(), scanner.reconnectTargets)
    }

    @Test fun thePickerScanIsSeparate() {
        val r = radios()
        r.start()
        r.connectMeshtastic()
        lose(mt, "mt-1")
        r.startScan()
        assertTrue(scanner.scanning)

        // A sighting never lands in the picker's list...
        scanner.emit(MeshTransportEvent.Sighted("mt-1"))
        assertEquals(emptyList<MeshDiscoveredDevice>(), r.discovered)
        // ...and the reconnect scan changing never stops the picker's scan.
        mt.meshtasticHandshake()
        assertEquals(emptySet<String>(), scanner.reconnectTargets)
        assertTrue(scanner.scanning)

        // Stopping the picker's scan leaves the reconnect scan alone.
        lose(mt, "mt-1")
        val changes = scanner.reconnectScanChanges
        r.stopScan()
        assertEquals(setOf("mt-1"), scanner.reconnectTargets)
        assertEquals(changes, scanner.reconnectScanChanges)
    }

    @Test fun scanStartsStayWithinAndroidsLimit() {
        val t = 1_000_000L
        assertEquals(0L, BleScanLimit.wait(emptyList(), t))
        assertEquals(0L, BleScanLimit.wait(listOf(t - 1000, t - 900, t - 800), t))
        // Four in the window: wait until the oldest leaves it (plus a margin).
        assertEquals(29_000L + 500, BleScanLimit.wait(listOf(t - 1000, t - 900, t - 800, t - 700), t))
        // Starts older than the window don't count.
        assertEquals(0L, BleScanLimit.wait(listOf(t - 40_000, t - 900, t - 800, t - 700), t))
    }
}
