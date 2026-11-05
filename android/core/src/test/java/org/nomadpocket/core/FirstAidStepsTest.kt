package org.nomadpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ports of the first-aid tests in AppTests/WatchTests.swift. */
class FirstAidStepsTest {
    private val guides = File(System.getProperty("guidesDir") ?: "../../App/Resources/Guides")
    private fun read(path: String): String? = File(guides, path).takeIf { it.isFile }?.readText()

    @Test fun extractsDoThisNowStepsAndCallouts() {
        val html = """
            <h2>Do this now</h2>
            <ol>
            <li>Call 911. Put on <strong>gloves</strong>.</li>
            <li>Press  hard &amp; don&#39;t   let go.
            </li>
            </ol>
            <div class="callout danger">Gasping is <em>not</em> breathing.</div>
            <h2>More</h2>
            <ol><li>Not a first step</li></ol>
            <div class="callout warn">Not this one</div>
        """.trimIndent()
        val (steps, warnings) = FirstAidSteps.extract(html)
        assertEquals(listOf("Call 911. Put on gloves.", "Press hard & don't let go."), steps)
        assertEquals(listOf("Gasping is not breathing."), warnings)
    }

    @Test fun listItemsWithoutClosingTags() {
        assertEquals(listOf("One", "Two"), FirstAidSteps.extract("<h2>Now</h2><ol><li>One<li>Two</ol>").first)
    }

    @Test fun noHeadingsUsesWholePage() {
        assertEquals(listOf("A", "B"), FirstAidSteps.extract("<ol><li>A</li><li>B</li></ol>").first)
    }

    @Test fun entityDecoding() {
        assertEquals("40° <2> — &bogus; a&b", FirstAidSteps.decodeEntities("40&deg; &lt;2&gt; &#x2014; &bogus; a&b"))
        assertEquals("&#xD800;", FirstAidSteps.decodeEntities("&#xD800;"))
    }

    @Test fun badIndexIsEmpty() {
        assertTrue(FirstAidSteps.urgentEntries("not json").isEmpty())
        assertTrue(FirstAidSteps.load { null }.isEmpty())
    }

    @Test fun urgentEntriesComeFromIndex() {
        val entries = FirstAidSteps.urgentEntries(read("index.json")!!)
        for (id in listOf("cpr", "bleeding", "choking")) assertTrue(id, entries.any { it.id == id })
        assertEquals("cpr", entries.first().id)
    }

    /**
     * Runs the watch's extraction over the real bundled guides, so a guide edit
     * that breaks the "Do this now" list fails here instead of on a wrist.
     */
    @Test fun everyUrgentGuideHasWatchSteps() {
        val entries = FirstAidSteps.urgentEntries(read("index.json")!!)
        val cards = FirstAidSteps.load(::read)
        assertEquals(entries.map { it.id }, cards.map { it.id })
        for (card in cards) {
            assertTrue(card.id, card.steps.size >= 3)
            for (step in card.steps + card.warnings) {
                assertFalse("${card.id}: $step", step.contains("<") || step.contains(">"))
                assertFalse("${card.id}: $step", step.contains("&amp;") || step.contains("&#"))
            }
        }
        assertTrue(cards.first { it.id == "cpr" }.steps.any { it.contains("100 to 120") })
    }
}
