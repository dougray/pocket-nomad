package org.nomadpocket.ham

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

class AfskDemodulatorTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/audio/$name")!!.readBytes()
    private fun pcm(name: String): ShortArray {
        val b = ByteBuffer.wrap(res(name)).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(b.remaining() / 2) { b.short }
    }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun decodesTheReferenceAudioFiles() {
        val meta = JSONArray(String(res("frames.json")))
        for (i in 0 until meta.length()) {
            val m = meta.getJSONObject(i)
            val got = ArrayList<String>()
            AfskDemodulator(m.getInt("rate")) { got += hex(it) }.also { it.feed(pcm(m.getString("file"))) }
            val want = (0 until m.getJSONArray("frames").length()).map { m.getJSONArray("frames").getString(it) }
            assertEquals(m.getString("file"), want, got)
        }
    }

    @Test fun framesParseAsAprs() {
        val meta = JSONArray(String(res("frames.json"))).getJSONObject(0)
        val frames = ArrayList<ByteArray>()
        AfskDemodulator(meta.getInt("rate")) { frames += it }.feed(pcm(meta.getString("file")))
        val packets = frames.map { Ax25Frame.decode(it) }
        assertTrue(packets.all { it != null })
        assertEquals("W5SIM-9", packets[0]!!.source.toString())
        assertNotNull(Aprs.decode(packets[0]!!))
    }

    @Test fun differentChunkSizesGiveTheSameFrames() {
        val meta = JSONArray(String(res("frames.json"))).getJSONObject(1)
        val samples = pcm(meta.getString("file"))
        val got = ArrayList<String>()
        val d = AfskDemodulator(meta.getInt("rate")) { got += hex(it) }
        var i = 0
        while (i < samples.size) { val n = minOf(777, samples.size - i); d.feed(samples.copyOfRange(i, i + n)); i += n }
        assertEquals(3, got.size)
    }

    @Test fun noiseAndMusicGiveNothing() {
        val r = Random(5)
        val noise = FloatArray(48000 * 6) { (r.nextGaussian() * 0.3).toFloat() }
        var frames = 0
        AfskDemodulator(48000) { frames++ }.feed(noise)
        val tones = FloatArray(48000 * 3) { i -> listOf(440, 880, 1200, 1800, 2200, 3000).sumOf { f -> 0.2 * Math.sin(2 * Math.PI * f * i / 48000.0 + f) }.toFloat() }
        AfskDemodulator(48000) { frames++ }.feed(tones)
        assertEquals(0, frames)
    }

    @Test fun aWrongSampleRateWithinTwoPercentStillWorks() {
        val meta = JSONArray(String(res("frames.json"))).getJSONObject(0)
        var n = 0
        AfskDemodulator((meta.getInt("rate") * 0.98).toInt()) { n++ }.feed(pcm(meta.getString("file")))
        assertEquals(3, n)
    }
}
