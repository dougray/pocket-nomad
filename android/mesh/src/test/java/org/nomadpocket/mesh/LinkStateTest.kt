// The radio link as the user lives with it: a remembered radio left behind goes
// "Not nearby" after two minutes (and still comes back by itself), "Not with me
// today" stops reconnecting for this session only, and drops shorter than 20 s
// at the edge of Bluetooth range change nothing the user sees or the drive log.
// Every timer runs on an injected clock. Coordinates are made up.
package org.nomadpocket.mesh

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

class LinkStateTest {
    private val h = ServiceHarness()
    private var clock = 1_800_000_000_000L
    private var fix: Fix? = null
    private val log = mutableListOf<String>()

    @After fun tearDown() = h.cleanup()

    private fun service(fake: FakeMeshTransport) = MeshService(fake, MeshStore(h.dir), h.prefs, h.scope, now = { clock })

    /** Ready plus the whole Meshtastic handshake, as after (re)connecting. */
    private fun FakeMeshTransport.handshake() {
        becomeReady()
        enqueue(MeshPBFromRadioVariant.MyInfo(MeshPBMyNodeInfo(myNodeNum = 1)))
        enqueue(MeshPBFromRadioVariant.NodeInfo(MeshPBNodeInfo(num = 2, user = MeshPBUser(longName = "Peak"))))
        enqueue(MeshPBFromRadioVariant.ConfigCompleteID(lastWantConfigID()!!))
        pump()
    }

    private fun connected(): Pair<MeshService, FakeMeshTransport> {
        val fake = FakeMeshTransport()
        val svc = service(fake)
        svc.start()
        svc.connect(MeshDiscoveredDevice("dev", "R", -50, 0))
        fake.handshake()
        assertEquals(MeshConnectionState.Connected("Test Radio"), svc.connection)
        return svc to fake
    }

    private fun lostLink(fake: FakeMeshTransport) =
        fake.emit(MeshTransportEvent.Disconnected("dev", "Link lost: out of range (Bluetooth status 8, link timed out).", true))

    private fun drive(mesh: MeshService): Pair<DriveMode, CoverageStore> {
        val coverage = CoverageStore(h.prefs, File(h.dir, "c.json"), h.scope, location = { fix }, now = { clock })
        return DriveMode({ listOf(mesh.firmware to mesh) }, coverage, h.prefs, h.scope, now = { clock }, log = { log.add(it) }) to coverage
    }

    @Test fun aRadioUnseenForTwoMinutesIsNotNearbyAndComesBackByItself() {
        val (svc, fake) = connected()
        lostLink(fake)
        clock += MeshService.NOT_NEARBY_MILLIS - 1
        svc.checkLink()
        assertEquals("still trying after 1:59", MeshConnectionState.Reconnecting("Test Radio"), svc.connection)
        assertFalse(fake.inLowPower)

        clock += 1
        svc.checkLink()
        assertEquals(MeshConnectionState.NotNearby("Test Radio"), svc.connection)
        assertEquals(MeshConnectionState.NotNearby("Test Radio"), svc.displayConnection)
        assertTrue("the background attempt goes low-duty", fake.inLowPower)
        assertEquals("the transport is still asked to connect", "dev", fake.connectedID)

        // Background attempts failing don't flip it back or pile up notices.
        val notice = svc.notice
        repeat(3) { fake.emit(MeshTransportEvent.ConnectFailed("dev", "Could not connect: Bluetooth error (status 133).")) }
        assertEquals(MeshConnectionState.NotNearby("Test Radio"), svc.connection)
        assertEquals(notice, svc.notice)

        // Back from the store: the radio reconnects on its own and Not nearby ends.
        fake.handshake()
        assertEquals(MeshConnectionState.Connected("Test Radio"), svc.connection)
        assertEquals(MeshConnectionState.Connected("Test Radio"), svc.displayConnection)
        assertFalse(fake.inLowPower)
    }

    @Test fun aRememberedRadioLeftAtHomeIsNotNearbyTwoMinutesAfterLaunch() {
        connected()
        val fake2 = FakeMeshTransport()
        val svc2 = service(fake2)
        svc2.start()
        assertEquals("dev", fake2.connectedID)
        clock += MeshService.NOT_NEARBY_MILLIS
        svc2.checkLink()
        assertEquals(MeshConnectionState.NotNearby("Test Radio"), svc2.connection)
        // A radio never set to reconnect is never "Not nearby": it just isn't connected.
        svc2.disconnect()
        clock += MeshService.NOT_NEARBY_MILLIS
        svc2.checkLink()
        assertEquals(MeshConnectionState.Idle, svc2.connection)
    }

    @Test fun notWithMeTodayStopsReconnectingButKeepsAutoReconnect() {
        val (svc, fake) = connected()
        lostLink(fake)
        svc.notWithMeToday()
        assertEquals(MeshConnectionState.NotWithMe("Test Radio"), svc.connection)
        assertTrue(svc.notWithMe)
        assertNull("no reconnect attempt this session", fake.connectedID)
        fake.emit(MeshTransportEvent.ConnectFailed("dev", "late"))
        assertEquals(MeshConnectionState.NotWithMe("Test Radio"), svc.connection)
        clock += MeshService.NOT_NEARBY_MILLIS * 3
        svc.checkLink()
        assertEquals(MeshConnectionState.NotWithMe("Test Radio"), svc.connection)

        // Next launch: auto-reconnect is still on, so it looks for the radio again.
        val fake2 = FakeMeshTransport()
        val svc2 = service(fake2)
        svc2.start()
        assertEquals("dev", fake2.connectedID)
        assertFalse(svc2.notWithMe)

        // Or tap Connect on the card now.
        svc.reconnectLast()
        assertEquals("dev", fake.connectedID)
        assertFalse(svc.notWithMe)
        fake.handshake()
        assertEquals(MeshConnectionState.Connected("Test Radio"), svc.connection)
    }

    @Test fun aDropUnderTwentySecondsChangesNothingShownOrLogged() {
        val (svc, fake) = connected()
        val (d, _) = drive(svc)
        fix = Fix(12.27, -30.74, 5.0, clock)
        d.start()
        d.tick()
        val note = d.state.pingNote
        val logged = log.size
        val shown = mutableListOf<MeshConnectionState>()

        repeat(3) {
            lostLink(fake)
            shown.add(svc.displayConnection)
            clock += 6_000
            fix = Fix(12.27, -30.74, 5.0, clock)
            d.tick()
            shown.add(svc.displayConnection)
            assertEquals(note, d.state.pingNote)
            fake.handshake()
            shown.add(svc.displayConnection)
        }
        assertTrue("the card read Connected throughout", shown.all { it == MeshConnectionState.Connected("Test Radio") })
        assertFalse(log.drop(logged).any { it.contains("not connected") || it.contains("pings waiting") })
        assertEquals(3, svc.briefDrops)

        // The minute heartbeat sums them up.
        clock += 60_000
        fix = Fix(12.27, -30.74, 5.0, clock)
        d.tick()
        assertTrue(log.last(), log.last().startsWith("still running") && log.last().endsWith("brief drop ×3"))
    }

    @Test fun aRealDropIsShownAndLoggedWithWhy() {
        val (svc, fake) = connected()
        val (d, _) = drive(svc)
        d.start()
        d.tick()
        lostLink(fake)
        clock += MeshService.FLICKER_MILLIS
        d.tick() // the drive loop re-checks the link timers itself
        assertEquals(MeshConnectionState.Reconnecting("Test Radio"), svc.displayConnection)
        assertEquals("Waiting for the radio", d.state.pingNote)
        assertTrue(log.toString(), log.any { it == "Meshtastic radio not connected (reconnecting): Link lost: out of range (Bluetooth status 8, link timed out)." })
        assertEquals(0, svc.briefDrops)

        clock += MeshService.NOT_NEARBY_MILLIS
        d.tick()
        assertTrue(log.toString(), log.any { it == "Meshtastic radio not nearby · reconnects when it's back" })
        fake.handshake()
        d.tick()
        assertTrue(log.toString(), log.any { it == "Meshtastic radio connected (was not nearby)" })

        svc.notWithMeToday()
        d.tick()
        assertTrue(log.toString(), log.last() == "Meshtastic radio not with me today · no reconnecting until Connect")
        svc.reconnectLast(); fake.handshake()
        d.tick()
        assertEquals("Meshtastic radio connected (was not with me today)", log.last { it.contains("radio") })
        svc.disconnect()
        d.tick()
        assertTrue(log.toString(), log.any { it == "Meshtastic radio not connected (idle): user disconnect" })
    }

    @Test fun driveModeIgnoresARadioThatIsNotNearby() {
        val (svc, fake) = connected()
        val (d, coverage) = drive(svc)
        d.start()
        lostLink(fake)
        clock += MeshService.NOT_NEARBY_MILLIS
        fix = Fix(12.27, -30.74, 5.0, clock)
        val writes = fake.writes.size
        repeat(3) { d.tick(); clock += 60_000; fix = Fix(12.27 + it * 0.01, -30.74, 5.0, clock) }
        assertEquals(MeshConnectionState.NotNearby("Test Radio"), svc.connection)
        assertEquals(DriveMode.AWAY_NOTE, d.state.pingNote)
        assertFalse(log.any { it.contains("Waiting for the radio") })
        assertEquals("no pings go out", 0, d.state.sent)
        assertEquals(writes, fake.writes.size)
        assertTrue(coverage.pings.isEmpty())
        assertEquals("logged once, not on every tick", 1, log.count { it.contains("not nearby") })
    }
}
