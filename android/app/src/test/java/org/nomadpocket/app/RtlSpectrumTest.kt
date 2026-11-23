package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.sdr.Fft
import org.nomadpocket.app.sdr.RtlSpectrum
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RtlSpectrumTest {
    @Test fun fftFindsATone() {
        val n = 64; val re = DoubleArray(n) { cos(2 * PI * 5 * it / n) }; val im = DoubleArray(n) { sin(2 * PI * 5 * it / n) }
        Fft.transform(re, im)
        val mag = DoubleArray(n) { re[it] * re[it] + im[it] * im[it] }
        assertEquals(5, mag.indices.maxByOrNull { mag[it] })
    }

    private fun tone(offsetHz: Double, bytes: Int, startSample: Long): ByteArray {
        val b = ByteArray(bytes)
        for (i in 0 until bytes / 2) {
            val t = (startSample + i).toDouble() / RtlSpectrum.SAMPLE_RATE
            b[2 * i] = (127.5 + 100 * cos(2 * PI * offsetHz * t)).toInt().toByte()
            b[2 * i + 1] = (127.5 + 100 * sin(2 * PI * offsetHz * t)).toInt().toByte()
        }
        return b
    }

    @Test fun sweepFindsTheToneAtTheRightFrequencyThroughAFakeRtlTcp() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val commands = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Long>>())
        val carrierHz = 144_800_000.0                     // a carrier on the air; the fake dongle shows it relative to its tuned centre
        val centre = java.util.concurrent.atomic.AtomicLong(144_800_000L)
        val t = Thread {
            try {
                server.accept().use { s ->
                    s.sendBufferSize = 16384
                    s.getOutputStream().write("RTL0".toByteArray() + ByteArray(8)); s.getOutputStream().flush()
                    val inp = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
                    var sample = 0L
                    while (!s.isClosed) {
                        while (inp.available() >= 5) {
                            val c = inp.readUnsignedByte(); val v = inp.readInt().toLong() and 0xFFFFFFFFL
                            commands += c to v
                            if (c == 0x01) centre.set(v)
                        }
                        out.write(tone(carrierHz - centre.get(), 4096, sample)); sample += 2048
                    }
                }
            } catch (_: java.io.IOException) { }
        }
        t.isDaemon = true; t.start()
        val sock = Socket("127.0.0.1", server.localPort); sock.soTimeout = 20000; sock.receiveBufferSize = 16384
        DataInputStream(sock.getInputStream()).readFully(ByteArray(12))
        val frame = RtlSpectrum.sweepOnce(sock, 144.0, 148.0, null)!!
        sock.close(); server.close()
        assertTrue(commands.any { it.first == 0x03 && it.second == 0L })
        assertEquals(3, commands.count { it.first == 0x01 })   // 4 MHz at 1.6 MHz per hop
        assertEquals(144_000_000, frame.lowHz)
        assertTrue(frame.db.size <= 400)
        val peakIdx = frame.db.indices.maxByOrNull { frame.db[it] }!!
        assertEquals(144.8, frame.hzAt(peakIdx) / 1e6, 0.02)
        assertTrue("carrier clearly above the floor", frame.db[peakIdx] - frame.db.sorted()[frame.db.size / 2] > 25)
    }
}
