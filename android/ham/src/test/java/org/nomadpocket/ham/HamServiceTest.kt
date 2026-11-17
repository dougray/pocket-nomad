package org.nomadpocket.ham

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class FakeHamTransport : HamTransport {
    override var onEvent: (HamTransportEvent) -> Unit = {}
    val written = ArrayList<ByteArray>()
    override fun startScan(showAll: Boolean) {}
    override fun stopScan() {}
    override fun connect(device: HamDevice) { onEvent(HamTransportEvent.Ready(device.address, "Test TNC")) }
    override fun disconnect() {}
    override fun write(bytes: ByteArray) { written.add(bytes) }
    val frames: List<Ax25Frame>
        get() {
            val d = KissDecoder()
            return written.flatMap { d.feed(it) }.filter { it.command == 0 }.mapNotNull { Ax25Frame.decode(it.payload) }
        }
}

class PlainBox : SecretBox {
    override fun seal(plain: String) = "sealed:" + plain.reversed()
    override fun open(sealed: String) = sealed.removePrefix("sealed:").reversed()
}

/** Listen-only enforcement, APRS messaging and connected-mode routing. Mirrors HamServiceTests.swift. */
class HamServiceTest {
    private lateinit var transport: FakeHamTransport
    private lateinit var prefs: MemoryHamPrefs
    private lateinit var settings: HamSettings
    private lateinit var ham: HamService
    private val dir = Files.createTempDirectory("ham").toFile()
    private var clock = 1_800_000_000_000L
    private val scope = CoroutineScope(StandardTestDispatcher())

    @Before fun setUp() {
        transport = FakeHamTransport()
        prefs = MemoryHamPrefs()
        settings = HamSettings(prefs, PlainBox())
        ham = HamService(transport, settings, dir, scope)
        ham.now = { clock }
        ham.connect(HamDevice("00:11", "Test TNC", 0, false))
    }

    private fun license() { settings.callsign = "kf0abc"; settings.licensed = true; settings.aprsSsid = 7 }

    private fun hear(tnc2: String) {
        val p = Aprs.decodeTnc2(tnc2)!!
        ham.receive(Ax25Frame(Ax25Address.parse(p.destination)!!, Ax25Address.parse(p.source)!!, control = Ax25Control.UI(false),
            info = p.raw.toByteArray()).encoded())
    }

    @Test fun listenOnlyNeverTransmits() {
        assertFalse(settings.canTransmit)
        hear("KF0XYZ>APRS::KF0ABC-7 :hello{5")
        ham.sendMessage("KF0XYZ", "hi")
        ham.connectTerminal("KF0BBS", "")
        assertTrue(transport.frames.isEmpty())
        assertNotNull(ham.notice)
        settings.callsign = "KF0ABC"
        ham.sendMessage("KF0XYZ", "hi")
        assertTrue(transport.frames.isEmpty())
        hear("KF0XYZ-9>APRS:=3944.40N/10459.40W>Mobile")
        assertEquals("KF0XYZ-9", ham.stations.first().id)
    }

    @Test fun incomingMessageStoredAndAcked() {
        license()
        hear("KF0XYZ>APRS::KF0ABC-7 :Are you on the net?{17")
        assertEquals("Are you on the net?", ham.chats.last().text)
        assertEquals(1, ham.unread["KF0XYZ"])
        assertEquals(":KF0XYZ   :ack17", String(transport.frames.last().info))
        assertEquals("KF0ABC-7", transport.frames.last().source.toString())
        hear("KF0XYZ>APRS::KF0ABC-7 :Are you on the net?{17")
        assertEquals(1, ham.chats.size)
        assertEquals(2, transport.frames.size)
    }

    @Test fun outgoingRetriesUntilAcked() {
        license()
        ham.sendMessage("kf0xyz", "Meet at the trailhead")
        assertEquals(1, transport.frames.size)
        val id = ham.chats.last().msgId!!
        clock += 31_000; ham.tick()
        assertEquals(2, transport.frames.size)
        hear("KF0XYZ>APRS::KF0ABC-7 :ack$id")
        assertEquals(AprsChat.Status.ACKED, ham.chats.last().status)
        clock += 300_000; ham.tick()
        assertEquals(2, transport.frames.size)
    }

    @Test fun unansweredFailsAfterFiveTries() {
        license()
        ham.sendMessage("KF0XYZ", "anyone?")
        repeat(20) { clock += 600_000; ham.tick() }
        assertEquals(AprsChat.Status.FAILED, ham.chats.last().status)
        assertEquals(5, transport.frames.size)
    }

    @Test fun unexpectedConnectRefusedUnlessAccepted() {
        license()
        val sabm = Ax25Frame(Ax25Address.parse("KF0ABC")!!, Ax25Address.parse("KF0XYZ")!!, control = Ax25Control.SABM(true))
        ham.receive(sabm.encoded())
        assertEquals(Ax25Control.DM(true), transport.frames.last().control)
        settings.acceptIncoming = true
        ham.receive(sabm.encoded())
        assertEquals(Ax25Control.UA(true), transport.frames.last().control)
        assertEquals("KF0XYZ", ham.terminalPeer)
        ham.receive(Ax25Frame(Ax25Address.parse("KF0ABC")!!, Ax25Address.parse("KF0XYZ")!!, control = Ax25Control.I(0, 0, false),
            info = "hello\r".toByteArray()).encoded())
        assertTrue(ham.terminalText.contains("hello"))
    }

    @Test fun composeNeedsCallsignAndPersists() {
        assertFalse(ham.compose(listOf("KF0XYZ"), emptyList(), "s", "b"))
        settings.callsign = "KF0ABC"
        assertTrue(ham.compose(listOf("KF0XYZ"), emptyList(), "s", "b"))
        val again = HamService(FakeHamTransport(), settings, dir, scope)
        assertEquals(1, again.outbox.size)
        assertEquals("KF0ABC", again.outbox[0].from)
    }

    @Test fun passwordIsSealed() {
        settings.winlinkPassword = "s3cret"
        assertEquals("s3cret", settings.winlinkPassword)
        assertFalse(prefs.map.values.contains("s3cret"))
        settings.winlinkPassword = null
        assertFalse(settings.hasWinlinkPassword)
    }

    @Test fun callsignValidation() {
        for (ok in listOf("KF0ABC", "W1AW", "N0CALL", "VE3XYZ", "G4ABC", "2E0ABC", "JA1XYZ")) assertTrue(ok, HamSettings.isValidCallsign(ok))
        for (bad in listOf("", "ABC", "1234", "KF0ABCDEF", "NOCALL")) assertFalse(bad, HamSettings.isValidCallsign(bad))
    }
}
