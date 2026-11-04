// MeshProtoWire.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// A small, hand-written Protocol Buffers wire-format encoder and decoder, ported
// from App/Sources/Mesh/MeshProtoWire.swift. It knows nothing about any schema:
// callers write and read fields by number. Supports varint, zigzag
// (sint32/sint64), fixed32/fixed64 (and their signed and float/double forms),
// and length-delimited fields. Unknown fields, including deprecated groups, can
// be skipped.
//
// Unsigned 32-bit values are carried as Long (0..0xFFFFFFFF); 64-bit scalars are
// carried as their raw Long bit pattern.

package org.nomadpocket.mesh.proto

import java.io.ByteArrayOutputStream

/** Protobuf wire types. */
enum class MeshProtoWireType(val raw: Int) {
    VARINT(0), FIXED64(1), LENGTH_DELIMITED(2), START_GROUP(3), END_GROUP(4), FIXED32(5);

    companion object {
        fun of(raw: Int): MeshProtoWireType? = entries.firstOrNull { it.raw == raw }
    }
}

sealed class MeshProtoError(message: String) : Exception(message) {
    object Truncated : MeshProtoError("truncated") { private fun readResolve(): Any = Truncated }
    object VarintTooLong : MeshProtoError("varint too long") { private fun readResolve(): Any = VarintTooLong }
    data class InvalidWireType(val raw: Int) : MeshProtoError("invalid wire type $raw")
    object InvalidFieldNumber : MeshProtoError("invalid field number") { private fun readResolve(): Any = InvalidFieldNumber }
    object LengthOutOfRange : MeshProtoError("length out of range") { private fun readResolve(): Any = LengthOutOfRange }
    object UnbalancedGroup : MeshProtoError("unbalanced group") { private fun readResolve(): Any = UnbalancedGroup }
}

/** Builds a protobuf message by appending fields. */
class MeshProtoWriter {
    private val out = ByteArrayOutputStream()
    val data: ByteArray get() = out.toByteArray()

    // Raw primitives

    fun writeRawVarint(value: Long) {
        var v = value
        while (java.lang.Long.compareUnsigned(v, 0x80) >= 0) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun writeTag(field: Int, type: MeshProtoWireType) {
        writeRawVarint((field.toLong() shl 3) or type.raw.toLong())
    }

    fun writeRawFixed32(value: Long) {
        for (i in 0 until 4) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    fun writeRawFixed64(value: Long) {
        for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    // Field writers. Proto3 semantics: callers decide whether to skip defaults;
    // the `*IfNonZero` helpers do that for the common case.

    fun writeVarint(field: Int, value: Long) {
        writeTag(field, MeshProtoWireType.VARINT)
        writeRawVarint(value)
    }

    fun writeUInt32(field: Int, value: Long) = writeVarint(field, value and 0xFFFFFFFFL)

    /** int32 negatives are sign-extended to 10 bytes, as the spec requires. */
    fun writeInt32(field: Int, value: Int) = writeVarint(field, value.toLong())

    fun writeInt64(field: Int, value: Long) = writeVarint(field, value)

    fun writeSInt32(field: Int, value: Int) = writeVarint(field, MeshProtoZigZag.encode32(value).toLong() and 0xFFFFFFFFL)

    fun writeSInt64(field: Int, value: Long) = writeVarint(field, MeshProtoZigZag.encode64(value))

    fun writeBool(field: Int, value: Boolean) = writeVarint(field, if (value) 1 else 0)

    fun writeFixed32(field: Int, value: Long) {
        writeTag(field, MeshProtoWireType.FIXED32)
        writeRawFixed32(value)
    }

    fun writeSFixed32(field: Int, value: Int) = writeFixed32(field, value.toLong() and 0xFFFFFFFFL)

    fun writeFixed64(field: Int, value: Long) {
        writeTag(field, MeshProtoWireType.FIXED64)
        writeRawFixed64(value)
    }

    fun writeFloat(field: Int, value: Float) = writeFixed32(field, value.toRawBits().toLong() and 0xFFFFFFFFL)

    fun writeDouble(field: Int, value: Double) = writeFixed64(field, value.toRawBits())

    fun writeBytes(field: Int, value: ByteArray) {
        writeTag(field, MeshProtoWireType.LENGTH_DELIMITED)
        writeRawVarint(value.size.toLong())
        out.write(value)
    }

    fun writeString(field: Int, value: String) = writeBytes(field, value.toByteArray(Charsets.UTF_8))

    fun writeMessage(field: Int, build: (MeshProtoWriter) -> Unit) {
        val sub = MeshProtoWriter()
        build(sub)
        writeBytes(field, sub.data)
    }

    // Default-skipping helpers

    fun writeUInt32IfNonZero(field: Int, value: Long) { if (value != 0L) writeUInt32(field, value) }
    fun writeFixed32IfNonZero(field: Int, value: Long) { if (value != 0L) writeFixed32(field, value) }
    fun writeBoolIfTrue(field: Int, value: Boolean) { if (value) writeBool(field, true) }
    fun writeStringIfNotEmpty(field: Int, value: String) { if (value.isNotEmpty()) writeString(field, value) }
    fun writeBytesIfNotEmpty(field: Int, value: ByteArray) { if (value.isNotEmpty()) writeBytes(field, value) }
}

object MeshProtoZigZag {
    fun encode32(n: Int): Int = (n shl 1) xor (n shr 31)
    fun decode32(n: Int): Int = (n ushr 1) xor -(n and 1)
    fun encode64(n: Long): Long = (n shl 1) xor (n shr 63)
    fun decode64(n: Long): Long = (n ushr 1) xor -(n and 1)
}

/** One decoded field. The value is kept raw; use the accessors to interpret it. */
class MeshProtoField(
    val number: Int,
    val wireType: MeshProtoWireType,
    /** For varint / fixed32 / fixed64: the raw 64-bit pattern. */
    val scalar: Long,
    /** For length-delimited. */
    val bytes: ByteArray,
) {
    val uint32: Long get() = scalar and 0xFFFFFFFFL
    val uint64: Long get() = scalar
    val int32: Int get() = scalar.toInt()
    val int64: Long get() = scalar
    val sint32: Int get() = MeshProtoZigZag.decode32(scalar.toInt())
    val sint64: Long get() = MeshProtoZigZag.decode64(scalar)
    val bool: Boolean get() = scalar != 0L
    val sfixed32: Int get() = scalar.toInt()
    val float: Float get() = Float.fromBits(scalar.toInt())
    val double: Double get() = Double.fromBits(scalar)
    /** Lossy UTF-8 decode: invalid sequences become U+FFFD rather than failing. */
    val string: String get() = String(bytes, Charsets.UTF_8)
}

/**
 * Iterates the fields of a protobuf message. Groups (wire types 3/4) are
 * skipped as a unit; every other unknown field is returned and the caller
 * simply ignores numbers it does not care about.
 */
class MeshProtoReader(private val bytes: ByteArray) {
    private var index = 0

    val isAtEnd: Boolean get() = index >= bytes.size

    fun readRawVarint(): Long {
        var result = 0L
        var shift = 0
        for (i in 0 until 10) {
            if (index >= bytes.size) throw MeshProtoError.Truncated
            val b = bytes[index].toInt() and 0xFF
            index += 1
            if (i == 9 && b > 0x01) throw MeshProtoError.VarintTooLong
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw MeshProtoError.VarintTooLong
    }

    private fun readRawFixed32(): Long {
        if (bytes.size - index < 4) throw MeshProtoError.Truncated
        var v = 0L
        for (i in 0 until 4) v = v or ((bytes[index + i].toLong() and 0xFF) shl (8 * i))
        index += 4
        return v
    }

    private fun readRawFixed64(): Long {
        if (bytes.size - index < 8) throw MeshProtoError.Truncated
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[index + i].toLong() and 0xFF) shl (8 * i))
        index += 8
        return v
    }

    private fun readTag(): Pair<Int, MeshProtoWireType> {
        val key = readRawVarint()
        val wireRaw = (key and 0x7).toInt()
        val wire = MeshProtoWireType.of(wireRaw) ?: throw MeshProtoError.InvalidWireType(wireRaw)
        val number = key ushr 3
        if (number < 1 || number > 536_870_911) throw MeshProtoError.InvalidFieldNumber
        return number.toInt() to wire
    }

    /** Skips a group body up to and including the matching end-group tag. */
    private fun skipGroup(startNumber: Int) {
        while (true) {
            if (isAtEnd) throw MeshProtoError.UnbalancedGroup
            val (number, wire) = readTag()
            when (wire) {
                MeshProtoWireType.VARINT -> readRawVarint()
                MeshProtoWireType.FIXED64 -> readRawFixed64()
                MeshProtoWireType.FIXED32 -> readRawFixed32()
                MeshProtoWireType.LENGTH_DELIMITED -> readLengthDelimited()
                MeshProtoWireType.START_GROUP -> skipGroup(number)
                MeshProtoWireType.END_GROUP -> {
                    if (number != startNumber) throw MeshProtoError.UnbalancedGroup
                    return
                }
            }
        }
    }

    private fun readLengthDelimited(): ByteArray {
        val len = readRawVarint()
        if (len < 0 || len > (bytes.size - index).toLong()) throw MeshProtoError.LengthOutOfRange
        val n = len.toInt()
        val slice = bytes.copyOfRange(index, index + n)
        index += n
        return slice
    }

    /** Returns the next field, or null at the end of the buffer. */
    fun next(): MeshProtoField? {
        while (!isAtEnd) {
            val (number, wire) = readTag()
            when (wire) {
                MeshProtoWireType.VARINT -> return MeshProtoField(number, wire, readRawVarint(), EMPTY)
                MeshProtoWireType.FIXED64 -> return MeshProtoField(number, wire, readRawFixed64(), EMPTY)
                MeshProtoWireType.FIXED32 -> return MeshProtoField(number, wire, readRawFixed32(), EMPTY)
                MeshProtoWireType.LENGTH_DELIMITED -> return MeshProtoField(number, wire, 0, readLengthDelimited())
                MeshProtoWireType.START_GROUP -> skipGroup(number) // legacy groups: never used by us, skip entirely
                MeshProtoWireType.END_GROUP -> throw MeshProtoError.UnbalancedGroup
            }
        }
        return null
    }

    companion object {
        val EMPTY = ByteArray(0)

        /** Convenience: visits every field in `data`. */
        inline fun forEachField(data: ByteArray, body: (MeshProtoField) -> Unit) {
            val reader = MeshProtoReader(data)
            while (true) {
                val field = reader.next() ?: break
                body(field)
            }
        }
    }
}
