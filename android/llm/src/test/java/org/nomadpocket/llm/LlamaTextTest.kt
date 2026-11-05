package org.nomadpocket.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin side of the JNI layer. Native code itself is checked by the debug self-test on a device. */
class LlamaTextTest {
    @Test fun stripThinking() {
        assertEquals("Boil it [1].", Thinking.strip("<think>water…</think>\n\nBoil it [1]."))
        // Still thinking: show nothing yet.
        assertEquals("", Thinking.strip("<think>let me see"))
        assertEquals("Press hard [1].", Thinking.strip("Press hard [1]."))
        assertEquals("A B", Thinking.strip("A <think></think>B").replace("  ", " "))
    }

    @Test fun utf8SplitAcrossTokens() {
        val text = Utf8Text()
        val drop = "💧".toByteArray(Charsets.UTF_8)   // 4 bytes
        assertTrue(text.append("Water ".toByteArray()))
        assertFalse(text.append(drop.copyOfRange(0, 2)))
        assertEquals("Water ", text.value)
        assertTrue(text.append(drop.copyOfRange(2, 4)))
        assertEquals("Water 💧", text.value)
        val e = "é".toByteArray(Charsets.UTF_8)
        assertFalse(text.append(e.copyOfRange(0, 1)))
        assertTrue(text.append(e.copyOfRange(1, 2) + " ok".toByteArray()))
        assertEquals("Water 💧é ok", text.value)
        assertFalse(text.append(ByteArray(0)))
    }

    @Test fun completeLength() {
        assertEquals(3, Utf8Text.completeLength("abc".toByteArray()))
        val euro = "€".toByteArray(Charsets.UTF_8)
        assertEquals(1, Utf8Text.completeLength("a".toByteArray() + euro.copyOfRange(0, 2)))
        assertEquals(4, Utf8Text.completeLength("a".toByteArray() + euro))
        assertEquals(0, Utf8Text.completeLength(ByteArray(0)))
    }

    @Test fun errorMessagesMatchIOS() {
        assertEquals(LlamaException.Kind.CONTEXT_FAILED, LlamaException.Kind.fromCode(-2))
        assertEquals(LlamaException.Kind.DECODE_FAILED, LlamaException.Kind.fromCode(-99))
        assertEquals(
            "Not enough memory to run the AI model. Close other apps and try again.",
            LlamaException(LlamaException.Kind.CONTEXT_FAILED).message,
        )
    }
}
