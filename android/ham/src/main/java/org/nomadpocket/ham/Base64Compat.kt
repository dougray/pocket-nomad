// Base64Compat.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// Standard Base64 (padded, RFC 4648) in plain Kotlin. java.util.Base64 needs Android 8.0, and this app also
// runs on Android 7. Same output as java.util.Base64's basic encoder and decoder.

package org.nomadpocket.ham

object Base64Compat {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val INDEX = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            out.append(ALPHABET[b0 shr 2]).append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            out.append(if (i + 1 < data.size) ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            out.append(if (i + 2 < data.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return out.toString()
    }

    /** Decodes padded or unpadded Base64, ignoring whitespace. Throws IllegalArgumentException on anything else. */
    fun decode(text: String): ByteArray {
        val clean = text.filterNot { it == ' ' || it == '\n' || it == '\r' || it == '\t' }.trimEnd('=')
        if (clean.length % 4 == 1) throw IllegalArgumentException("bad Base64 length")
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var acc = 0; var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) INDEX[c.code] else -1
            if (v < 0) throw IllegalArgumentException("bad Base64 character")
            acc = (acc shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; out.write((acc shr bits) and 0xFF) }
        }
        return out.toByteArray()
    }
}
