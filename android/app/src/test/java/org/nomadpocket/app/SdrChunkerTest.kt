package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.sdr.SdrChunker

class SdrChunkerTest {
    @Test fun splitsToTheNegotiatedSizeAndReassembles() {
        val data = ByteArray(450) { it.toByte() }
        val parts = SdrChunker.split(data, 182)
        assertEquals(listOf(182, 182, 86), parts.map { it.size })
        assertArrayEquals(data, parts.reduce { a, b -> a + b })
        assertEquals(1, SdrChunker.split(ByteArray(5), 182).size)
        assertTrue(SdrChunker.split(data, 3).all { it.size <= 20 })   // never smaller than the 20-byte minimum
    }
}
