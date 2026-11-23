package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.sdr.AdsbTracker
import org.nomadpocket.app.sdr.ModeS
import org.nomadpocket.app.sdr.RtlAdsb
import org.nomadpocket.app.sdr.SdrAircraft
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Random

class ModeSTest {
    // Well-known example messages from the public ADS-B literature; their parity (CRC) checks to zero, which the test asserts.
    private val IDENT = "8D4840D6202CC371C32CE0576098"
    private val EVEN = "8D40621D58C382D690C8AC2863A7"
    private val ODD = "8D40621D58C386435CC412692AD6"
    private val VEL = "8D485020994409940838175B284F"

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Pulse-position modulation at 2 samples per microsecond, as unsigned I/Q bytes (signal in I, a little noise on both). */
    private fun modulate(hex: String, lead: Int, tail: Int, amp: Int = 90, rnd: Random = Random(1)): ByteArray {
        val m = bytes(hex)
        val level = IntArray(lead + 16 + 224 + tail)
        listOf(0, 2, 7, 9).forEach { level[lead + it] = amp }
        for (b in 0 until 112) {
            val bit = (m[b shr 3].toInt() shr (7 - (b and 7))) and 1
            level[lead + 16 + 2 * b + (if (bit == 1) 0 else 1)] = amp
        }
        val out = ByteArray(level.size * 2)
        for (i in level.indices) {
            out[2 * i] = (127.5 + level[i] + rnd.nextGaussian() * 2).toInt().coerceIn(0, 255).toByte()
            out[2 * i + 1] = (127.5 + rnd.nextGaussian() * 2).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    @Test fun referenceMessagesPassParity() {
        listOf(IDENT, EVEN, ODD, VEL).forEach { assertEquals(it, 0, ModeS.crc(bytes(it))) }
        val bad = bytes(IDENT); bad[5] = (bad[5].toInt() xor 0x10).toByte()
        assertNotEquals(0, ModeS.crc(bad))
    }

    @Test fun decodesIdentAltitudeVelocityAndPosition() {
        assertEquals("4840D6", ModeS.icao(bytes(IDENT)))
        assertEquals("KLM1023", ModeS.callsign(bytes(IDENT)))
        assertEquals(38000, ModeS.altitudeFeet(bytes(EVEN)))
        val v = ModeS.velocity(bytes(VEL))!!
        assertEquals(159, v.knots); assertEquals(182.88, v.track, 0.01); assertEquals(-832, v.climbFpm)
        val p = ModeS.globalPosition(ModeS.cpr(bytes(EVEN))!!, ModeS.cpr(bytes(ODD))!!, newestIsOdd = true)!!
        assertEquals(52.26578, p.first, 1e-4); assertEquals(3.93891, p.second, 1e-4)
        val q = ModeS.globalPosition(ModeS.cpr(bytes(EVEN))!!, ModeS.cpr(bytes(ODD))!!, newestIsOdd = false)!!
        assertEquals(52.25720, q.first, 1e-4); assertEquals(3.91937, q.second, 1e-4)
    }

    @Test fun demodulatesSamplesAcrossChunkBoundariesAndIgnoresNoise() {
        val rnd = Random(7)
        val noise = ByteArray(200_000) { (127 + rnd.nextInt(5) - 2).toByte() }
        val stream = noise + modulate(IDENT, 50, 50) + modulate(EVEN, 80, 40) + modulate(ODD, 60, 60) + noise
        for (chunk in listOf(stream.size, 4096, 999)) {
            val d = ModeS.Demodulator()
            val got = ArrayList<String>()
            var i = 0
            while (i < stream.size) { val n = minOf(chunk, stream.size - i); d.feed(stream.copyOfRange(i, i + n), n).forEach { got += ModeS.icao(it) + ":" + ModeS.typeCode(it) }; i += n }
            assertEquals("chunk $chunk", listOf("4840D6:4", "40621D:11", "40621D:11"), got)
        }
        val d = ModeS.Demodulator()
        assertTrue("pure noise gives nothing", d.feed(ByteArray(1_000_000) { (rnd.nextInt(256)).toByte() }).isEmpty())
    }

    @Test fun trackerNeedsAnEvenAndOddPairWithinTenSeconds() {
        val seen = ArrayList<SdrAircraft>()
        val t = AdsbTracker { seen += it }
        t.feed(bytes(IDENT), 0); t.feed(bytes(VEL), 1); t.feed(bytes(EVEN), 2)
        assertTrue(seen.isEmpty())
        t.feed(bytes(ODD), 400_000)           // too late to pair with the even one
        assertTrue(seen.isEmpty())
        t.feed(bytes(EVEN), 401_000)
        assertEquals(1, seen.size)
        val a = seen[0]
        assertEquals("40621D", a.icao); assertEquals(38000, a.altitudeFeet)
        assertEquals(52.2, a.latitude, 0.1)
    }

    @Test fun receivesThroughAFakeRtlTcp() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val cmds = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())
        val stream = modulate(EVEN, 100, 100) + modulate(ODD, 100, 100)
        val th = Thread {
            try {
                server.accept().use { s ->
                    s.getOutputStream().write("RTL0".toByteArray() + ByteArray(8)); s.getOutputStream().flush()
                    val inp = DataInputStream(s.getInputStream())
                    Thread.sleep(200)
                    while (inp.available() >= 5) { val c = inp.readUnsignedByte(); cmds += c to (inp.readInt().toLong() and 0xFFFFFFFFL) }
                    s.getOutputStream().write(stream); s.getOutputStream().flush()
                }
            } catch (_: java.io.IOException) { }
        }
        th.isDaemon = true; th.start()
        val sock = Socket("127.0.0.1", server.localPort); sock.soTimeout = 5000
        val seen = ArrayList<SdrAircraft>()
        RtlAdsb.receive(sock, { true }, now = { 1000L }, onAircraft = { seen += it })
        sock.close(); server.close()
        assertTrue(cmds.contains(0x02 to 2_000_000L) && cmds.contains(0x01 to 1_090_000_000L))
        assertEquals(1, seen.size); assertEquals("40621D", seen[0].icao)
    }
}
