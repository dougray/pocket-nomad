package org.nomadpocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.app.guides.GuideLibrary
import java.io.File

/** Loads the shared guides straight from the iOS app's resources, the one source for both apps. */
class GuideIndexTest {
    private val root = File("../../App/Resources/Guides").let { if (it.exists()) it else File("../App/Resources/Guides") }
    private val library = GuideLibrary(
        GuideLibrary.parseIndex(File(root, "index.json").readText()),
        GuideLibrary.parseChecklists(File(root, "checklists.json").readText()),
    )

    @Test fun guidesLoadAndFilesExist() {
        assertFalse(library.categories.isEmpty())
        assertFalse(library.urgent.isEmpty())
        assertEquals(69, library.allGuides.size)
        assertEquals(8, library.checklists.size)
        for (guide in library.allGuides) assertTrue(guide.file, File(root, guide.file).exists())
        assertTrue(library.checklists.all { it.items.isNotEmpty() })
    }

    @Test fun emergenciesAreUrgentFirstAidOnScreen() {
        val e = library.emergencies
        assertTrue(e.size in 1..10)
        assertTrue(e.all { it.urgent && library.categories.first().guides.contains(it) })
    }

    @Test fun searchMatchesKeywordsAndPutsUrgentFirst() {
        assertEquals("choking", library.search("heimlich").first().id)
        val bleeding = library.search("bleeding")
        assertTrue(bleeding.isNotEmpty())
        val firstNonUrgent = bleeding.indexOfFirst { !it.urgent }
        if (firstNonUrgent >= 0) assertTrue(bleeding.drop(firstNonUrgent).none { it.urgent })
        assertTrue(library.search("zzzz-not-a-word").isEmpty())
        assertTrue(library.search("   ").isEmpty())
        // Every word must match somewhere.
        assertTrue(library.search("cpr infant").all { g -> (g.title + g.summary + g.keywords.joinToString()).lowercase().let { "cpr" in it && "infant" in it } })
    }
}
