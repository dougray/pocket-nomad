package org.nomadpocket.ham

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

class SameDecoderTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/audio/$name")!!.readBytes()
    private fun pcm(name: String): ShortArray { val b = ByteBuffer.wrap(res(name)).order(ByteOrder.LITTLE_ENDIAN); return ShortArray(b.remaining() / 2) { b.short } }

    @Test fun decodesTheReferenceAlerts() {
        val meta = JSONArray(String(res("same.json")))
        for (i in 0 until meta.length()) {
            val m = meta.getJSONObject(i)
            val got = ArrayList<SameAlert>()
            SameDecoder(m.getInt("rate")) { got += it }.feed(pcm(m.getString("file")))
            assertEquals(m.getString("file"), listOf(m.getJSONArray("headers").getString(0)), got.map { it.header })
        }
    }

    @Test fun parsesFields() {
        val a = SameDecoder.parse("ZCZC-WXR-TOR-048453+0030-3201530-KEWX/NWS-")!!
        assertEquals("Tornado Warning", a.event); assertEquals(listOf("048453"), a.areas); assertEquals(30, a.minutes); assertEquals("KEWX/NWS", a.station)
        assertEquals("Tornado Warning for 1 area, valid 30 min", a.text)
        val b = SameDecoder.parse("ZCZC-WXR-SVR-048021-048055+0045-3201545-KAUS/NWS-")!!
        assertEquals(2, b.areas.size); assertEquals(45, b.minutes)
        assertNull(SameDecoder.parse("ZCZC-garbage")); assertNull(SameDecoder.parse("hello"))
        assertEquals("Alert XYZ", SameDecoder.parse("ZCZC-WXR-XYZ-048453+0030-3201530-KEWX/NWS-")!!.event)
    }

    @Test fun noiseAndPacketAudioGiveNothing() {
        val r = Random(9)
        var n = 0
        SameDecoder(48000) { n++ }.feed(FloatArray(48000 * 8) { (r.nextGaussian() * 0.3).toFloat() })
        val meta = JSONArray(String(res("frames.json"))).getJSONObject(0)
        SameDecoder(meta.getInt("rate")) { n++ }.feed(pcm(meta.getString("file")))   // APRS audio is not a weather alert
        assertEquals(0, n)
    }
}
