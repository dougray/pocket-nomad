package org.nomadpocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.app.ask.AskEngine
import org.nomadpocket.app.ask.AskPassage
import org.nomadpocket.app.ask.AskSearch
import org.nomadpocket.app.ask.BookPassageSource
import org.nomadpocket.app.ask.SourceRef
import org.nomadpocket.app.tools.ToolKind
import org.nomadpocket.app.tools.WaterCalculator

/** AskEngine and SearchService tests from AppTests/ToolMathTests.swift, plus the Android-only parts. */
class AskEngineTest {
    private fun hit(title: String, len: Int, score: Double, source: String = "x") =
        AskPassage(SourceRef.Guide(title), source, title, "a".repeat(len), score)

    @Test fun tidyDropsNotFoundBeforeAnAnswer() {
        assertEquals(
            "Use fine sand at least 26 inches deep [2].",
            AskEngine.tidy("I couldn't find that in your offline library.\n\nUse fine sand at least 26 inches deep [2]."),
        )
        assertEquals("boil it [1].", AskEngine.tidy("I couldn't find that in your offline library, but sources say: boil it [1]."))
        val honest = "I couldn't find that in your offline library. Try searching for \"sand filter\"."
        assertEquals(honest, AskEngine.tidy(honest))
        assertEquals("Boil water for one minute [1].", AskEngine.tidy("Boil water for one minute [1]."))
        assertEquals("1. Press hard [1].\n", AskEngine.tidy("1. Press hard [1].\n\n[1], [2], [3], [4]"))
    }

    @Test fun pickRespectsBudgetAndDedupes() {
        val hits = listOf(hit("A", 1000, 9.0), hit("A", 1000, 8.0), hit("A", 1000, 7.0), hit("B", 3000, 6.0), hit("C", 500, 5.0))
        assertEquals(listOf("A", "A", "C"), AskEngine.pick(hits, budget = 3000).map { it.title })
        // One oversized passage still gets through when nothing else fits.
        assertEquals(1, AskEngine.pick(listOf(hit("Z", 9000, 1.0)), budget = 1000).size)
        // At most five sources, and none with no budget at all beyond the first.
        assertEquals(5, AskEngine.pick((1..9).map { hit("T$it", 10, 1.0) }, budget = 10_000).size)
        assertTrue(AskEngine.pick(emptyList(), budget = 7000).isEmpty())
        // Passages far weaker than the best stay out of the prompt.
        val weak = listOf(hit("Severe Bleeding", 500, 1.3), hit("Severe Bleeding", 500, 1.2), hit("Nosebleeds", 500, 0.5), hit("Survival Priorities", 500, 0.43))
        assertEquals(listOf("Severe Bleeding", "Severe Bleeding"), AskEngine.pick(weak, budget = 7000).map { it.title })
    }

    @Test fun promptNumbersSourcesAndCapsLength() {
        val p = AskEngine.prompt("How?", listOf(hit("Bleeding", 2500, 1.0, "Survival Guide"), hit("Burns", 10, 1.0, "WikiMed")))
        assertTrue(p.startsWith("Sources:\n\n[1] Bleeding — Survival Guide\n"))
        assertTrue(p.contains("\n\n[2] Burns — WikiMed\naaaaaaaaaa"))
        assertTrue(p.endsWith("\n\nQuestion: How?"))
        assertTrue(p.length < 2_200)
    }

    @Test fun instructionsMatchIOS() {
        val i = AskEngine.INSTRUCTIONS
        assertTrue(i.startsWith("You are Pocket Nomad, an offline emergency and survival assistant on someone's phone. They may"))
        assertTrue(i.contains("say \"I couldn't find that in your offline library\" and suggest"))
        assertTrue(i.endsWith("that are not in the sources."))
        assertTrue(!i.contains("\n") && !i.contains("  "))
    }

    @Test fun citations() {
        assertEquals(listOf(2, 1), AskEngine.citations("Press [2]. Call [1] and [2]."))
        assertTrue(AskEngine.citations("none").isEmpty())
    }

    @Test fun keyTermsAndCoverage() {
        val terms = AskSearch.keyTerms("How do I stop severe bleeding on a leg?")
        assertEquals(setOf("stop", "sever", "bleed", "leg"), terms.toSet())
        assertEquals(0.25, AskSearch.coverage(terms, "Just Stop Oil campaigns"), 1e-9)
        assertEquals(1.0, AskSearch.coverage(terms, "To stop severe bleeding on an arm or leg, press hard"), 1e-9)
        assertEquals(1.0, AskSearch.coverage(emptyList(), "anything"), 1e-9)
    }

    @Test fun normalizedScalesPerIndexAndPenalisesPartialMatches() {
        val q = "stop severe bleeding"
        val book = listOf(
            AskPassage(SourceRef.Book("b", "1"), "Book", "Just Stop Oil", "campaign", 50.0),
            AskPassage(SourceRef.Book("b", "2"), "Book", "Haemorrhage", "stop severe bleeding with pressure", 25.0),
        )
        val n = AskSearch.normalized(book, 1.0, q)
        // Raw bm25 put the protest group first; a third of the words, squared, puts it last.
        assertEquals(1.0 / 9, n[0].score, 1e-9)
        assertEquals(0.5, n[1].score, 1e-9)
        assertTrue(AskSearch.normalized(listOf(hit("A", 1, 0.0)), 1.0, q).all { it.score == 0.0 })
    }

    @Test fun bookScoresArriveNormalisedAndMergeWithGuides() {
        // BookSearch has already scaled each book against its own best hit, so the
        // scores are used as they are and sorted together with the guides.
        val books = BookPassageSource { _, _ ->
            listOf(
                AskPassage(SourceRef.Book("wiki", "a"), "Wiki", "Water", "boil water", 0.4),
                AskPassage(SourceRef.Book("med", "b"), "Med", "Boiling", "boil water", 1.0),
            )
        }
        val hits = AskSearch(null, books).search("boil water", 8)
        assertEquals(listOf(1.0, 0.4), hits.map { it.score })
        assertEquals(1, AskSearch(null, books).search("boil water", 1).size)
        val failing = AskSearch(null) { _, _ -> error("library not ready") }
        assertTrue(failing.search("boil water").isEmpty())
    }

    @Test fun bleachQuestionsGoToTheCalculator() {
        // AskViewModel skips the model whenever this returns a dose.
        assertEquals(
            "5 gallons of cloudy water with 8.25% bleach: 60 drops (about 1 teaspoon). Stir, wait 30 minutes.",
            WaterCalculator.dose("How much bleach for 5 gallons of cloudy water with 8.25% bleach?"),
        )
        assertTrue(WaterCalculator.dose("How much bleach purifies a gallon of water?") != null)
        assertNull(WaterCalculator.dose("How do I stop severe bleeding?"))
        assertEquals(ToolKind.WATER, ToolKind.related("How much bleach purifies a gallon of water?"))
    }

    @Test fun bookPassageFields() {
        val p = AskPassage.fromBook("wikimed", "WikiMed", "Burn", "A/Burn", "Cool the burn.", 2.0)
        assertEquals(SourceRef.Book("wikimed", "A/Burn"), p.ref)
        assertEquals("WikiMed", p.sourceName)
        assertEquals("Burn", p.title)
    }
}
