// Port of MeshTests/MeshProtoWireTests.swift.
package org.nomadpocket.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.nomadpocket.mesh.proto.MeshPBData
import org.nomadpocket.mesh.proto.MeshProtoError
import org.nomadpocket.mesh.proto.MeshProtoField
import org.nomadpocket.mesh.proto.MeshProtoReader
import org.nomadpocket.mesh.proto.MeshProtoWireType
import org.nomadpocket.mesh.proto.MeshProtoWriter
import org.nomadpocket.mesh.proto.MeshProtoZigZag

class MeshProtoWireTest {
    private fun varintBytes(v: Long): List<Int> = MeshProtoWriter().apply { writeRawVarint(v) }.data.u()
    private fun readVarint(b: ByteArray): Long = MeshProtoReader(b).readRawVarint()

    private val u32Max = 0xFFFFFFFFL

    @Test fun varintKnownEncodings() {
        assertEquals(listOf(0x00), varintBytes(0))
        assertEquals(listOf(0x01), varintBytes(1))
        assertEquals(listOf(0x7F), varintBytes(127))
        assertEquals(listOf(0x80, 0x01), varintBytes(128))
        assertEquals(listOf(0xAC, 0x02), varintBytes(300))
        assertEquals(listOf(0xFF, 0x7F), varintBytes(16_383))
        assertEquals(listOf(0x80, 0x80, 0x01), varintBytes(16_384))
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0xFF, 0x0F), varintBytes(u32Max))
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01), varintBytes(-1L)) // UInt64.max
    }

    @Test fun varintRoundTripEdgeValues() {
        val values = listOf(0L, 1, 127, 128, 255, 256, 16_383, 16_384, 2_097_151, 2_097_152,
            u32Max - 1, u32Max, u32Max + 1, Long.MAX_VALUE, -2L /* UInt64.max - 1 */, -1L /* UInt64.max */)
        for (v in values) assertEquals("value $v", v, readVarint(MeshProtoWriter().apply { writeRawVarint(v) }.data))
    }

    @Test fun varintTooLongThrows() {
        assertThrows<MeshProtoError.VarintTooLong> { readVarint(ByteArray(10) { 0xFF.toByte() } + bytes(0x01)) }
        assertThrows<MeshProtoError.VarintTooLong> { readVarint(ByteArray(9) { 0xFF.toByte() } + bytes(0x02)) }
    }

    @Test fun varintTruncatedThrows() {
        assertThrows<MeshProtoError.Truncated> { readVarint(bytes(0x80)) }
        assertThrows<MeshProtoError.Truncated> { readVarint(bytes()) }
    }

    @Test fun negativeInt32UsesTenBytes() {
        val w = MeshProtoWriter()
        w.writeInt32(1, -1)
        assertEquals(11, w.data.size) // 1 tag + 10 varint bytes
        val f = MeshProtoReader(w.data).next()!!
        assertEquals(-1, f.int32)
        assertEquals(-1L, f.int64)
    }

    @Test fun zigZag() {
        assertEquals(0, MeshProtoZigZag.encode32(0))
        assertEquals(1, MeshProtoZigZag.encode32(-1))
        assertEquals(2, MeshProtoZigZag.encode32(1))
        assertEquals(3, MeshProtoZigZag.encode32(-2))
        assertEquals(0xFFFF_FFFEL, MeshProtoZigZag.encode32(Int.MAX_VALUE).toLong() and 0xFFFFFFFFL)
        assertEquals(0xFFFF_FFFFL, MeshProtoZigZag.encode32(Int.MIN_VALUE).toLong() and 0xFFFFFFFFL)
        for (v in listOf(0, 1, -1, 63, -64, 1000, -1000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(v, MeshProtoZigZag.decode32(MeshProtoZigZag.encode32(v)))
        }
        for (v in listOf(0L, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, -123_456_789_012)) {
            assertEquals(v, MeshProtoZigZag.decode64(MeshProtoZigZag.encode64(v)))
        }
        val w = MeshProtoWriter()
        w.writeSInt32(1, -2)
        assertEquals(listOf(0x08, 0x03), w.data.u())
    }

    @Test fun fixedAndFloatAndBytesRoundTrip() {
        val w = MeshProtoWriter()
        w.writeFixed32(1, 0xDEAD_BEEFL)
        w.writeSFixed32(2, -302_000_000)
        w.writeFixed64(3, 0x0102_0304_0506_0708L)
        w.writeFloat(4, -7.25f)
        w.writeDouble(5, 3.141592653589793)
        w.writeString(6, "héllo 📡")
        w.writeBytes(7, bytes(0, 1, 2))
        w.writeBool(8, true)
        w.writeSInt64(9, -42)

        // fixed32 is little-endian on the wire
        assertEquals(listOf(0x0D, 0xEF, 0xBE, 0xAD, 0xDE), w.data.u().take(5))

        val got = mutableMapOf<Int, MeshProtoField>()
        MeshProtoReader.forEachField(w.data) { got[it.number] = it }
        assertEquals(0xDEAD_BEEFL, got[1]!!.uint32)
        assertEquals(MeshProtoWireType.FIXED32, got[1]!!.wireType)
        assertEquals(-302_000_000, got[2]!!.sfixed32)
        assertEquals(0x0102_0304_0506_0708L, got[3]!!.uint64)
        assertEquals(-7.25f, got[4]!!.float)
        assertEquals(3.141592653589793, got[5]!!.double, 0.0)
        assertEquals("héllo 📡", got[6]!!.string)
        assertArrayEquals(bytes(0, 1, 2), got[7]!!.bytes)
        assertEquals(true, got[8]!!.bool)
        assertEquals(-42L, got[9]!!.sint64)
    }

    @Test fun unknownFieldsOfEveryWireTypeAreSkipped() {
        val w = MeshProtoWriter()
        w.writeVarint(100, 12345)
        w.writeUInt32(1, 1)
        w.writeFixed64(101, -1L)
        w.writeBytes(102, ByteArray(40) { 0xAA.toByte() })
        w.writeTag(103, MeshProtoWireType.START_GROUP)
        w.writeVarint(1, 5)
        w.writeTag(104, MeshProtoWireType.START_GROUP)
        w.writeTag(104, MeshProtoWireType.END_GROUP)
        w.writeTag(103, MeshProtoWireType.END_GROUP)
        w.writeBytes(2, "hi".toByteArray())
        w.writeFixed32(105, 7)
        w.writeFixed32(6, 0xCAFE)

        val d = MeshPBData.decode(w.data)
        assertEquals(1L, d.portnum)
        assertArrayEquals("hi".toByteArray(), d.payload)
        assertEquals(0xCAFEL, d.requestID)
    }

    @Test fun malformedInputThrows() {
        assertThrows<MeshProtoError.LengthOutOfRange> { MeshPBData.decode(bytes(0x12, 0x05, 0x01)) }
        val e = assertThrows<MeshProtoError.InvalidWireType> { MeshPBData.decode(bytes(0x0F)) }
        assertEquals(7, e.raw)
        assertThrows<MeshProtoError.InvalidFieldNumber> { MeshPBData.decode(bytes(0x00, 0x01)) }
        assertThrows<MeshProtoError.Truncated> { MeshPBData.decode(bytes(0x25, 0x01, 0x02)) }
        assertThrows<MeshProtoError.UnbalancedGroup> { MeshPBData.decode(bytes(0x0C)) }
        assertThrows<MeshProtoError.UnbalancedGroup> { MeshPBData.decode(bytes(0x0B, 0x08, 0x01)) }
    }

    @Test fun invalidUTF8DecodesLossily() {
        val w = MeshProtoWriter()
        w.writeBytes(2, bytes(0x68, 0xFF, 0x69))
        assertEquals("h�i", MeshProtoReader(w.data).next()!!.string)
    }

    @Test fun emptyBufferHasNoFields() {
        assertNull(MeshProtoReader(bytes()).next())
    }
}
