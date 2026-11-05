package org.nomadpocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.app.ask.AskSearch
import org.nomadpocket.app.ask.BookPassageSource
import org.nomadpocket.app.ask.GuideIndex
import org.nomadpocket.app.ask.GuideText
import org.nomadpocket.app.ask.ModelCatalog
import org.nomadpocket.app.ask.ModelChooser
import org.nomadpocket.app.ask.ModelFile
import org.nomadpocket.app.ask.PassageSplitter
import org.nomadpocket.app.ask.SourceRef
import org.nomadpocket.app.guides.GuideLibrary
import java.io.File

/** Guide passages, search over the real guides, and which model answers. */
class AskRetrievalTest {
    private val root = File("../../App/Resources/Guides").let { if (it.exists()) it else File("../App/Resources/Guides") }
    private val library = GuideLibrary(GuideLibrary.parseIndex(File(root, "index.json").readText()), emptyList())
    private val index = GuideIndex.build(library.allGuides) { File(root, it.file).takeIf(File::exists)?.readText() }
    private val search = AskSearch(index, BookPassageSource.NONE)

    @Test fun guideTextKeepsTableHeadersAndDropsSources() {
        val text = GuideText.extract(
            "<h2>Do this now</h2><ol><li>Wear gloves.</li><li>Mix <strong>5&nbsp;tbsp</strong> bleach.</li></ol>" +
                "<table><tr><th>Job</th><th>Mix</th></tr><tr><td>Counters</td><td>5 tbsp per gallon</td></tr></table>" +
                "<h3>Sources</h3><ul><li>CDC</li></ul>",
        )
        assertEquals("Do this now\n\nWear gloves.\nMix 5 tbsp bleach.\n\nJob; Mix\nJob: Counters; Mix: 5 tbsp per gallon", text)
    }

    @Test fun splitterKeepsPassagesNearTarget() {
        val para = "This sentence is about forty characters. ".repeat(20).trim()   // ~800 chars
        val passages = PassageSplitter.split(List(5) { para }.joinToString("\n\n"))
        assertTrue(passages.size in 4..5)
        assertTrue(passages.all { it.length <= PassageSplitter.MAX_LENGTH + 300 })
        val long = PassageSplitter.split("Word. ".repeat(600))
        assertTrue(long.size >= 3 && long.all { it.length <= PassageSplitter.MAX_LENGTH })
        assertTrue(PassageSplitter.split("  \n\n ").isEmpty())
    }

    @Test fun stem() {
        assertEquals("bleed", GuideIndex.stem("bleeding"))
        assertEquals("bleed", GuideIndex.stem("bleed"))
        assertEquals("burn", GuideIndex.stem("burns"))
        assertEquals("stop", GuideIndex.stem("stopping"))
        assertEquals("purifi", GuideIndex.stem("purifies"))
        assertEquals("dress", GuideIndex.stem("dressing"))
        assertEquals("8.25", GuideIndex.stem("8.25"))
    }

    @Test fun everyGuideIsIndexed() {
        assertTrue(index.size >= library.allGuides.size)
        assertTrue(index.search("zzzzqqq", 8).isEmpty())
        assertTrue(index.search("", 8).isEmpty())
    }

    private fun top(q: String) = (search.search(q).first().ref as SourceRef.Guide).path

    @Test fun findsTheRightGuide() {
        assertTrue(top("How do I stop severe bleeding?").contains("bleeding"))
        assertEquals("water-sanitation/making-water-safe.html", top("How do I purify drinking water?"))
        assertTrue(top("Signs of heat stroke vs heat exhaustion").contains("heat"))
        assertTrue(top("someone is choking").contains("chok"))
        val hits = search.search("How much bleach purifies a gallon of water?")
        assertTrue(hits.isNotEmpty() && hits.all { it.sourceName == GuideIndex.GUIDE_SOURCE })
        // Best first, at most the limit.
        assertEquals(hits.sortedByDescending { it.score }, hits)
        assertTrue(search.search("water", 3).size <= 3)
    }

    private val small = ModelFile(ModelCatalog.small.id, "/m/s", ModelCatalog.small.size)
    private val large = ModelFile(ModelCatalog.large.id, "/m/l", ModelCatalog.large.size)
    private val gb = 1_000_000_000L

    @Test fun autoPicksTheBiggestModelThatFits() {
        assertEquals(large, ModelChooser.choose("auto", listOf(small, large), 4 * gb).model)
        assertEquals(small, ModelChooser.choose("auto", listOf(small, large), 2 * gb).model)
        val none = ModelChooser.choose("auto", listOf(small, large), gb)
        assertNull(none.model)
        assertTrue(none.note!!.contains("1.0 GB"))
        assertNull(ModelChooser.choose("auto", emptyList(), 8 * gb).model)
        assertNull(ModelChooser.choose("auto", emptyList(), 8 * gb).note)
    }

    @Test fun namedModelFallsBackWhenMemoryIsShort() {
        assertEquals(large, ModelChooser.choose(large.name, listOf(small, large), 4 * gb).model)
        val fallback = ModelChooser.choose(large.name, listOf(small, large), 2 * gb)
        assertEquals(small, fallback.model)
        assertTrue(fallback.note!!.contains("Qwen3-1.7B-Q4_K_M answers instead"))
        val passages = ModelChooser.choose(small.name, listOf(small, large), gb)
        assertNull(passages.model)
        assertTrue(passages.note!!.startsWith("Only 1.0 GB of memory is free, not enough for Qwen3-1.7B-Q4_K_M."))
        assertNull(ModelChooser.choose("none", listOf(small, large), 8 * gb).model)
        // A model that was deleted falls back to whatever fits.
        assertEquals(small, ModelChooser.choose("gone.gguf", listOf(small), 4 * gb).model)
    }

    @Test fun checksumAndMagic() {
        val f = File.createTempFile("model", ".gguf")
        f.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", org.nomadpocket.app.ask.ModelStore.sha256(f))
        assertFalse(org.nomadpocket.app.ask.ModelStore.isGguf(f))
        f.writeBytes("GGUF".toByteArray() + ByteArray(8))
        assertTrue(org.nomadpocket.app.ask.ModelStore.isGguf(f))
        f.delete()
    }

    @Test fun catalogMatchesIOS() {
        assertEquals(listOf("Qwen3-1.7B-Q4_K_M.gguf", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf"), ModelCatalog.items.map { it.id })
        for (m in ModelCatalog.items) {
            assertTrue(m.url.startsWith("https://huggingface.co/unsloth/") && m.url.endsWith("/" + m.id))
            assertEquals(64, m.sha256.length)
            assertFalse(m.url.contains("/main/"))
        }
        assertEquals(3_500_000_000L, ModelCatalog.large.minFreeBytes)
        // An unknown file needs about its size plus working room.
        assertTrue(ModelCatalog.neededBytes("other.gguf", 2 * gb) > 2 * gb)
    }
}
