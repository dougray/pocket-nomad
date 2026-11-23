package org.nomadpocket.app

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.sdr.AisDemodulator
import org.nomadpocket.app.sdr.AisTracker
import org.nomadpocket.app.sdr.RtlAis
import org.nomadpocket.app.sdr.SdrShip
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Random

class AisTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/ais/$name")!!.readBytes()
    private val expected = JSONArray(String(res("ais.json")))

    private fun viaFakeDriver(iq: ByteArray, channelMillis: Long = 1_000_000): Pair<List<SdrShip>, List<Pair<Int, Long>>> {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val cmds = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())
        val th = Thread {
            try {
                server.accept().use { s ->
                    s.getOutputStream().write("RTL0".toByteArray() + ByteArray(8)); s.getOutputStream().flush()
                    val inp = DataInputStream(s.getInputStream())
                    Thread.sleep(200)
                    while (inp.available() >= 5) { val c = inp.readUnsignedByte(); cmds += c to (inp.readInt().toLong() and 0xFFFFFFFFL) }
                    s.getOutputStream().write(iq); s.getOutputStream().flush()
                }
            } catch (_: java.io.IOException) { }
        }
        th.isDaemon = true; th.start()
        val sock = Socket("127.0.0.1", server.localPort); sock.soTimeout = 20000
        val ships = ArrayList<SdrShip>()
        RtlAis.receive(sock, { true }, channelMillis) { ships += it }
        sock.close(); server.close()
        return ships to cmds
    }

    @Test fun decodesShipReportsFromFmSamplesThroughAFakeRtlTcp() {
        val (ships, cmds) = viaFakeDriver(res("ais-250k.iq8"))
        assertTrue(cmds.contains(0x02 to 250_000L) && cmds.contains(0x01 to 161_975_000L))
        // the two position reports (the third message is a static one with the name)
        val byMmsi = ships.associateBy { it.mmsi }
        assertEquals(setOf(366999000, 367123456), byMmsi.keys)
        val a = byMmsi[366999000]!!
        assertEquals(30.2672, a.latitude, 1e-4); assertEquals(-97.7431, a.longitude, 1e-4)
        assertEquals(12.3, a.speedKnots!!, 0.05); assertEquals(90.0, a.courseDegrees!!, 0.05)
        val b = byMmsi[367123456]!!
        assertEquals(29.3013, b.latitude, 1e-4); assertEquals(-94.7977, b.longitude, 1e-4)
        assertEquals(5.5, b.speedKnots!!, 0.05); assertEquals(270.0, b.courseDegrees!!, 0.05)
    }

    @Test fun staticMessagesGiveNamesAndTypes() {
        val iq = res("ais-250k.iq8")
        val ships = ArrayList<SdrShip>()
        val tracker = AisTracker { ships += it }
        val demod = AisDemodulator(RtlAis.AUDIO_RATE) { tracker.feed(it, 0) }
        val fm = org.nomadpocket.app.sdr.FmChannel(4) { a, n -> demod.feed(a, n) }
        fm.feed(iq, iq.size)
        assertEquals(3, tracker.messages)
        assertEquals("SIM FERRY", tracker.nameOf(338087471))
    }

    @Test fun aMistunedNoisyReceiverStillGetsMostReports() {
        val (ships, _) = viaFakeDriver(res("ais-mistuned-noisy.iq8"))
        val got = ships.map { it.mmsi }.toSet()
        assertTrue("got $got", got.containsAll(setOf(366999000, 367123456)))   // the two short reports; the long static message may be lost
    }

    @Test fun noiseGivesNoShips() {
        val r = Random(2)
        val (ships, _) = viaFakeDriver(ByteArray(400_000) { (127 + r.nextGaussian() * 12).toInt().coerceIn(0, 255).toByte() })
        assertTrue(ships.isEmpty())
    }

    @Test fun switchesChannelsOnATimer() {
        var t = 0L
        val iq = res("ais-250k.iq8")
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val cmds = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())
        val th = Thread {
            try {
                server.accept().use { s ->
                    s.getOutputStream().write("RTL0".toByteArray() + ByteArray(8)); s.getOutputStream().flush()
                    val inp = DataInputStream(s.getInputStream())
                    var off = 0
                    while (off < iq.size) {
                        s.getOutputStream().write(iq, off, minOf(20_000, iq.size - off)); s.getOutputStream().flush(); off += 20_000
                        Thread.sleep(30)
                        while (inp.available() >= 5) { val c = inp.readUnsignedByte(); cmds += c to (inp.readInt().toLong() and 0xFFFFFFFFL) }
                    }
                    Thread.sleep(100)
                    while (inp.available() >= 5) { val c = inp.readUnsignedByte(); cmds += c to (inp.readInt().toLong() and 0xFFFFFFFFL) }
                }
            } catch (_: java.io.IOException) { }
        }
        th.isDaemon = true; th.start()
        val sock = Socket("127.0.0.1", server.localPort); sock.soTimeout = 20000
        RtlAis.receive(sock, { true }, channelMillis = 5, now = { t += 3; t }) { }
        sock.close(); server.close()
        val freqs = cmds.filter { it.first == 0x01 }.map { it.second }
        assertTrue(freqs.size >= 3 && freqs.toSet() == setOf(161_975_000L, 162_025_000L))
    }
}
