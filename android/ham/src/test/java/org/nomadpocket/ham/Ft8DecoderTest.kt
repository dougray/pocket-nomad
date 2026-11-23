package org.nomadpocket.ham

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compares the port with ft8_lib's own decoder (the expected files are its output for the same recordings). */
class Ft8DecoderTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/$name")!!.readBytes()

    /** 12 kHz, 16-bit mono WAV -> floats. */
    private fun wav(name: String): FloatArray {
        val b = ByteBuffer.wrap(res(name)).order(ByteOrder.LITTLE_ENDIAN)
        b.position(44)
        return FloatArray(b.remaining() / 2) { b.short / 32768f }
    }

    private fun expected(name: String) = String(res("$name.expected")).lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun decode(name: String) = Ft8Decoder().decode(wav("$name.wav")).map { it.text }

    @Test fun generatedMessagesDecodeExactly() {
        assertEquals(listOf("CQ K1ABC FN42"), decode("gen_cq"))
        assertEquals(listOf("TNX BOB 73 GL"), decode("gen_free"))
    }

    @Test fun realRecordingsMatchTheReferenceDecoder() {
        for (name in listOf("websdr_test4", "websdr_test6")) {
            val want = expected(name)
            val got = decode(name)
            assertTrue("$name: no messages", got.isNotEmpty())
            val missing = want.filterNot { it in got }
            val extra = got.filterNot { it in want }
            assertTrue("$name missing $missing extra $extra (ref ${want.size}, got ${got.size})", missing.isEmpty() && extra.isEmpty())
        }
    }

    @Test fun silenceAndNoiseGiveNothing() {
        val r = java.util.Random(4)
        assertEquals(0, Ft8Decoder().decode(FloatArray(180000)).size)
        assertEquals(0, Ft8Decoder().decode(FloatArray(180000) { (r.nextGaussian() * 0.1).toFloat() }).size)
    }
}

class Ft8SlotsTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/$name")!!.readBytes()
    private fun wavShorts(name: String): ShortArray {
        val b = ByteBuffer.wrap(res(name)).order(ByteOrder.LITTLE_ENDIAN); b.position(44)
        return ShortArray(b.remaining() / 2) { b.short }
    }

    /** Linear-interpolated upsampling, as a stand-in for a phone's 48 or 44.1 kHz microphone. */
    private fun upsample(x: ShortArray, rate: Int): ShortArray {
        val n = (x.size.toLong() * rate / 12000).toInt()
        return ShortArray(n) { i -> val p = i * 12000.0 / rate; val i0 = p.toInt().coerceAtMost(x.size - 2); (x[i0] + (x[i0 + 1] - x[i0]) * (p - i0)).toInt().toShort() }
    }

    @Test fun liveAudioAtPhoneRatesDecodesAtTheSlotBoundary() {
        for (rate in listOf(48000, 44100)) {
            val audio = upsample(wavShorts("gen_free.wav"), rate)
            var clock = 15000L * 1000 + 100       // 100 ms into a slot
            val got = ArrayList<List<Ft8Message>>()
            val runner = Ft8SlotRunner(rate, now = { clock }) { got += it }
            var i = 0
            while (i < audio.size) {
                val n = minOf(rate / 10, audio.size - i)    // 100 ms chunks
                runner.feed(audio.copyOfRange(i, i + n), n)
                i += n; clock += 100
            }
            clock += 3000                                    // the next slot begins: the last one is decoded
            runner.feed(ShortArray(rate / 10), rate / 10)
            assertEquals("rate $rate", listOf("TNX BOB 73 GL"), got.flatten().map { it.text })
        }
    }

    @Test fun aSlotHeardOnlyBrieflyIsNotDecoded() {
        var clock = 15000L * 1000 + 100
        val got = ArrayList<List<Ft8Message>>()
        val runner = Ft8SlotRunner(48000, now = { clock }) { got += it }
        runner.feed(ShortArray(4800), 4800)
        clock += 15000
        runner.feed(ShortArray(4800), 4800)
        assertTrue(got.isEmpty())
    }
}

class Ft8SpotTest {
    @Test fun spotsComeFromCqCallsAndFirstReplies() {
        assertEquals("K1ABC" to "FN42", ft8Spot("CQ K1ABC FN42"))
        assertEquals("R6WA" to "LN32", ft8Spot("CQ DX R6WA LN32"))
        assertEquals("ZS6S" to "KG33", ft8Spot("OH3NIV ZS6S KG33"))
        assertNull(ft8Spot("K1ABC W9XYZ -11")); assertNull(ft8Spot("K1ABC W9XYZ RR73")); assertNull(ft8Spot("TNX BOB 73 GL"))
    }
}
