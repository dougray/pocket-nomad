// Kiss.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// KISS framing between the phone and a TNC. Port of KISS.swift.
// One frame: FEND, command byte (port << 4 | command), escaped payload, FEND.

package org.nomadpocket.ham

object Kiss {
    const val FEND = 0xC0
    const val FESC = 0xDB
    const val TFEND = 0xDC
    const val TFESC = 0xDD

    const val DATA = 0x00
    const val TX_DELAY = 0x01
    const val PERSISTENCE = 0x02
    const val SLOT_TIME = 0x03

    fun encode(payload: ByteArray, command: Int = DATA, port: Int = 0): ByteArray {
        val out = ArrayList<Byte>(payload.size + 4)
        out.add(FEND.toByte())
        out.add((((port and 0x0F) shl 4) or command).toByte())
        for (b in payload) {
            when (b.toInt() and 0xFF) {
                FEND -> { out.add(FESC.toByte()); out.add(TFEND.toByte()) }
                FESC -> { out.add(FESC.toByte()); out.add(TFESC.toByte()) }
                else -> out.add(b)
            }
        }
        out.add(FEND.toByte())
        return out.toByteArray()
    }

    fun parameterFrames(txDelay: Int, persistence: Int, slotTime: Int): ByteArray =
        encode(byteArrayOf(txDelay.toByte()), TX_DELAY) +
            encode(byteArrayOf(persistence.toByte()), PERSISTENCE) +
            encode(byteArrayOf(slotTime.toByte()), SLOT_TIME)
}

/** Reassembles KISS frames from bytes that arrive in arbitrary chunks. */
class KissDecoder {
    data class Frame(val port: Int, val command: Int, val payload: ByteArray)

    private val buffer = java.io.ByteArrayOutputStream()
    private var inFrame = false
    private var escaped = false
    var maxFrame = 4096

    fun feed(bytes: ByteArray): List<Frame> {
        val frames = ArrayList<Frame>()
        for (raw in bytes) {
            val b = raw.toInt() and 0xFF
            if (b == Kiss.FEND) {
                val data = buffer.toByteArray()
                if (inFrame && data.isNotEmpty()) {
                    val first = data[0].toInt() and 0xFF
                    frames.add(Frame(first shr 4, first and 0x0F, data.copyOfRange(1, data.size)))
                }
                buffer.reset()
                inFrame = true
                escaped = false
                continue
            }
            if (!inFrame) continue
            if (escaped) {
                escaped = false
                buffer.write(when (b) { Kiss.TFEND -> Kiss.FEND; Kiss.TFESC -> Kiss.FESC; else -> b })
            } else if (b == Kiss.FESC) {
                escaped = true
            } else {
                buffer.write(b)
            }
            if (buffer.size() > maxFrame) { buffer.reset(); inFrame = false }
        }
        return frames
    }
}
