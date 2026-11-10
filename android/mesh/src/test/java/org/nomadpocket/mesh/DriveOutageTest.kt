// Drive mode's "not recording radios" alert: with a radio remembered, no radio
// connected for 5 minutes in a row raises it once per outage (state + drive
// log; DriveModeService turns the state into the notification), and a radio
// connecting clears and resets it. Never raised with no radio remembered.
// Every timer runs on an injected clock.
package org.nomadpocket.mesh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.mesh.proto.MeshPBFromRadioVariant
import org.nomadpocket.mesh.proto.MeshPBMyNodeInfo
import java.io.File

class DriveOutageTest {
    private val h = ServiceHarness()
    private var clock = 1_800_000_000_000L
    private val log = mutableListOf<String>()

    @After fun tearDown() = h.cleanup()

    private fun service(fake: FakeMeshTransport) = MeshService(fake, MeshStore(h.dir), h.prefs, h.scope, now = { clock })

    private fun FakeMeshTransport.handshake() {
        becomeReady()
        enqueue(MeshPBFromRadioVariant.MyInfo(MeshPBMyNodeInfo(myNodeNum = 1)))
        enqueue(MeshPBFromRadioVariant.ConfigCompleteID(lastWantConfigID()!!))
        pump()
    }

    private fun connected(): Pair<MeshService, FakeMeshTransport> {
        val fake = FakeMeshTransport()
        val svc = service(fake)
        svc.start()
        svc.connect(MeshDiscoveredDevice("dev", "R", -50, 0))
        fake.handshake()
        assertTrue(svc.connection.isConnected)
        return svc to fake
    }

    private fun lose(fake: FakeMeshTransport) =
        fake.emit(MeshTransportEvent.Disconnected("dev", "Link lost: out of range (Bluetooth status 8, link timed out).", true))

    private fun drive(radios: () -> List<Pair<Firmware, MeshService>>): DriveMode {
        val coverage = CoverageStore(h.prefs, File(h.dir, "c.json"), h.scope, location = { null }, now = { clock })
        return DriveMode(radios, coverage, h.prefs, h.scope, now = { clock }, log = { log.add(it) })
    }

    private fun alerts() = log.count { it.startsWith("alert: no radio connected") }

    /** Moves the clock in drive-mode ticks. */
    private fun DriveMode.run(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, DriveMode.TICK_MILLIS)
            clock += step
            left -= step
            tick()
        }
    }

    @Test fun noAlertBeforeFiveMinutesThenExactlyOne() {
        val (svc, fake) = connected()
        val d = drive { listOf(svc.firmware to svc) }
        d.start()
        lose(fake)
        d.tick()
        d.run(DriveMode.RADIO_OUTAGE_MILLIS - 1)
        assertFalse(d.state.radioOutage)
        assertEquals(0, alerts())

        d.run(1)
        assertTrue(d.state.radioOutage)
        assertEquals(1, alerts())

        // Not nearby and an hour more of nothing: still the one alert.
        d.run(60 * 60_000L)
        assertTrue(d.state.radioOutage)
        assertEquals(1, alerts())
    }

    @Test fun aRadioConnectingClearsItAndTheNextOutageAlertsAgain() {
        val (svc, fake) = connected()
        val d = drive { listOf(svc.firmware to svc) }
        d.start()
        lose(fake)
        d.tick()
        d.run(DriveMode.RADIO_OUTAGE_MILLIS + 60_000)
        assertEquals(1, alerts())

        fake.handshake()
        d.tick()
        assertFalse(d.state.radioOutage)
        assertTrue(log.any { it.startsWith("radio back after 6 min") && it.endsWith("recording radios again") })

        // A second outage is a new one: no alert for its first 5 minutes, then one more.
        lose(fake)
        d.tick()
        d.run(DriveMode.RADIO_OUTAGE_MILLIS - DriveMode.TICK_MILLIS)
        assertEquals(1, alerts())
        d.run(DriveMode.TICK_MILLIS)
        assertEquals(2, alerts())
    }

    @Test fun shortDropsResetTheClock() {
        val (svc, fake) = connected()
        val d = drive { listOf(svc.firmware to svc) }
        d.start()
        repeat(3) {
            lose(fake)
            d.tick()
            d.run(4 * 60_000L)
            fake.handshake()
            d.tick()
        }
        assertEquals(0, alerts())
        assertFalse(d.state.radioOutage)
    }

    @Test fun startingWithTheRadioMissingAlertsFiveMinutesIn() {
        connected()
        // Next launch: remembered, but it isn't here.
        val svc = service(FakeMeshTransport())
        svc.start()
        val d = drive { listOf(svc.firmware to svc) }
        d.start()
        d.tick() // the loop's first tick, at the start
        d.run(DriveMode.RADIO_OUTAGE_MILLIS - DriveMode.TICK_MILLIS)
        assertEquals(0, alerts())
        d.run(DriveMode.TICK_MILLIS)
        assertEquals(1, alerts())
        assertTrue(d.state.radioOutage)

        d.stop()
        assertFalse("stopping drive mode clears it", d.state.radioOutage)
    }

    @Test fun noAlertWithNoRadioRemembered() {
        val svc = service(FakeMeshTransport())
        svc.start()
        val d = drive { listOf(svc.firmware to svc) }
        d.start()
        d.run(30 * 60_000L)
        assertEquals(0, alerts())
        assertFalse(d.state.radioOutage)

        // Nothing in use at all (what MeshRadios.inUse gives with no radio).
        val d2 = drive { emptyList() }
        d2.start()
        d2.run(30 * 60_000L)
        assertFalse(d2.state.radioOutage)
        assertEquals(0, alerts())
    }

    @Test fun notWithMeTodayIsNotAnOutage() {
        val (svc, fake) = connected()
        val d = drive { listOf(svc.firmware to svc) }
        d.start()
        lose(fake)
        svc.notWithMeToday()
        d.run(30 * 60_000L)
        assertEquals(0, alerts())
        assertFalse(d.state.radioOutage)
    }
}
