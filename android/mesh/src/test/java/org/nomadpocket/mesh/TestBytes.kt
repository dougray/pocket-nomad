package org.nomadpocket.mesh

import org.junit.Assert.fail

/** Byte array from unsigned ints, like Swift's `Data([0x0D, 0xEF])`. */
fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

fun ByteArray.u(): List<Int> = map { it.toInt() and 0xFF }

inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        fail("expected ${T::class.simpleName}, got $e")
    }
    fail("expected ${T::class.simpleName}, nothing thrown")
    throw IllegalStateException()
}
