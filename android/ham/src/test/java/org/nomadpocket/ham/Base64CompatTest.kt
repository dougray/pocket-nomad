package org.nomadpocket.ham

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class Base64CompatTest {
    @Test fun matchesJavaBase64OnRandomData() {
        val rnd = Random(7)
        for (n in 0..300) {
            val data = ByteArray(n).also { rnd.nextBytes(it) }
            val expected = java.util.Base64.getEncoder().encodeToString(data)
            assertEquals(expected, Base64Compat.encode(data))
            assertArrayEquals(data, Base64Compat.decode(expected))
        }
    }

    @Test fun knownVectorsAndLeniency() {
        assertEquals("TWFu", Base64Compat.encode("Man".toByteArray())); assertEquals("TWE=", Base64Compat.encode("Ma".toByteArray())); assertEquals("TQ==", Base64Compat.encode("M".toByteArray()))
        assertEquals("Man", String(Base64Compat.decode("TW\nFu ")))
        assertEquals("M", String(Base64Compat.decode("TQ")))   // unpadded is accepted
    }

    @Test fun rejectsGarbage() {
        for (bad in listOf("T", "TWF*", "é")) assertThrows(IllegalArgumentException::class.java) { Base64Compat.decode(bad) }
    }
}
