package org.nomadpocket.app

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.sdr.RtlAprs
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RtlAprsTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/audio/$name")!!.readBytes()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Narrow FM at 250 kS/s carrying the reference APRS audio (48 kHz, resampled), +-3 kHz deviation, with a little noise and a carrier offset. */
    private fun fmIq(audio: ShortArray, noise: Double, offsetHz: Double): ByteArray {
        val rate = RtlAprs.SAMPLE_RATE
        val seconds = audio.size / 48000.0
        val n = (seconds * rate).toInt()
        val out = ByteArray(n * 2)
        val r = Random(3)
        var ph = 0.0
        for (i in 0 until n) {
            val pos = i * 48000.0 / rate
            val i0 = pos.toInt().coerceAtMost(audio.size - 2); val frac = pos - i0
            val a = (audio[i0] * (1 - frac) + audio[i0 + 1] * frac) / 32768.0 / 0.5   // fixture peaks at 0.5
            ph += 2 * PI * (3000.0 * a + offsetHz) / rate
            out[2 * i] = (127.5 + 90 * cos(ph) + r.nextGaussian() * noise).toInt().coerceIn(0, 255).toByte()
            out[2 * i + 1] = (127.5 + 90 * sin(ph) + r.nextGaussian() * noise).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    private fun decodeThroughFakeDriver(iq: ByteArray): List<String> {
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
        val got = ArrayList<String>()
        RtlAprs.receive(sock, 144.39, { true }) { got += hex(it) }
        sock.close(); server.close()
        assertTrue(cmds.contains(0x02 to 250_000L) && cmds.contains(0x01 to 144_390_000L))
        return got
    }

    private fun expected(): List<String> {
        val m = JSONArray(String(res("frames.json"))).getJSONObject(0).getJSONArray("frames")
        return (0 until m.length()).map { m.getString(it) }
    }

    private fun audio(): ShortArray { val b = ByteBuffer.wrap(res("afsk-48000-clean.pcm")).order(ByteOrder.LITTLE_ENDIAN); return ShortArray(b.remaining() / 2) { b.short } }

    @Test fun decodesAprsFromFmSamplesThroughAFakeRtlTcp() {
        assertEquals(expected(), decodeThroughFakeDriver(fmIq(audio(), noise = 2.0, offsetHz = 0.0)))
    }

    @Test fun aMistunedReceiverStillDecodes() {
        assertEquals(expected(), decodeThroughFakeDriver(fmIq(audio(), noise = 2.0, offsetHz = 2500.0)))
    }
}
