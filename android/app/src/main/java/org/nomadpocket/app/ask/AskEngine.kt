package org.nomadpocket.app.ask

/** Where a passage came from, so the app can open it. */
sealed interface SourceRef {
    /** A built-in guide, by its file, e.g. "first-aid/bleeding.html". */
    data class Guide(val path: String) : SourceRef
    /** An article in a downloaded book. */
    data class Book(val bookId: String, val path: String) : SourceRef
}

/** One passage that can answer a question: a piece of a guide or of a book article. */
data class AskPassage(
    val ref: SourceRef,
    /** "Survival Guide" or the book's title. */
    val sourceName: String,
    val title: String,
    val body: String,
    /** Higher is better. Comparable across sources only after [AskSearch.normalized]. */
    val score: Double,
) {
    companion object {
        /** A passage from a book, with the fields of the Library module's BookPassage. */
        fun fromBook(bookId: String, bookTitle: String, articleTitle: String, path: String, text: String, score: Double) =
            AskPassage(SourceRef.Book(bookId, path), bookTitle, articleTitle, text, score)
    }
}

/**
 * The parts of Ask that don't need a model: which passages go in the prompt, the
 * prompt itself, and the clean-up of the answer. Retrieval comes first: the model
 * only sees passages from the guides and the user's books and is told to cite them.
 * Small on-device models make things up when asked from memory; grounding them is
 * what makes this safe-ish. A port of AskEngine in App/Sources/Ask/AskView.swift.
 */
object AskEngine {
    const val NOT_FOUND = "I couldn't find that in your offline library"

    val INSTRUCTIONS = listOf(
        "You are Pocket Nomad, an offline emergency and survival assistant on someone's phone.",
        "They may have no internet and no help nearby. Answer ONLY from the numbered sources you are given.",
        "Cite every fact with its source number in brackets, like [1] or [2].",
        "Only if no source helps at all, say \"$NOT_FOUND\" and suggest a better search; never say it before an answer.",
        "Put urgent actions first as short numbered steps. Use plain words.",
        "Only when the question is about an injury or medical emergency, start by saying to call 911 or local emergency services if possible; otherwise don't mention 911.",
        "Use only sources about the exact situation asked (for example, drinking water versus cleaning surfaces) and ignore numbers from sources about something else.",
        "Don't calculate new amounts; quote the source's own rows and numbers.",
        "Copy every number exactly as the source gives it, together with its conditions (such as bleach strength, amount of water, or age), and never mix numbers from different conditions.",
        "Sources marked \"Survival Guide\" were written for this app and checked; when sources disagree on an amount or a step, follow the Survival Guide.",
        "Never invent medicine doses, numbers or facts that are not in the sources.",
    ).joinToString(" ")

    /** Most passages put in one prompt. */
    const val MAX_SOURCES = 5

    /** Longest piece of one passage put in the prompt. */
    const val MAX_PASSAGE_CHARS = 2_000

    /**
     * Passages scoring under this share of the best hit are left out of the prompt. A
     * small model blends whatever it is given: asked how to stop severe bleeding with
     * "Survival Priorities" (0.33 of the top score) among the sources, Qwen3 1.7B turned
     * its STOP acronym into "1. Stop – do not touch the wound". Weak passages still show
     * under Best matches when there is no model.
     */
    const val MIN_RELATIVE_SCORE = 0.5

    /**
     * Takes the best passages that fit [budget] characters, at most two from the same
     * article, and none far weaker than the best ([MIN_RELATIVE_SCORE]). [hits] are best first.
     */
    fun pick(hits: List<AskPassage>, budget: Int): List<AskPassage> {
        val out = mutableListOf<AskPassage>()
        var used = 0
        val perTitle = mutableMapOf<String, Int>()
        val floor = (hits.firstOrNull()?.score ?: 0.0) * MIN_RELATIVE_SCORE
        for (hit in hits) {
            if (hit.score < floor) break
            val cost = hit.body.length + hit.title.length + 20
            // One oversized passage still gets through when nothing else fits.
            if (!(used + cost <= budget || out.isEmpty()) || (perTitle[hit.title] ?: 0) >= 2) continue
            out += hit
            used += minOf(cost, budget)
            perTitle[hit.title] = (perTitle[hit.title] ?: 0) + 1
            if (out.size == MAX_SOURCES) break
        }
        return out
    }

    /** The numbered sources and the question, as the model sees them. */
    fun prompt(question: String, sources: List<AskPassage>): String {
        val blocks = sources.mapIndexed { i, s -> "[${i + 1}] ${s.title} — ${s.sourceName}\n${s.body.take(MAX_PASSAGE_CHARS)}" }
        return "Sources:\n\n" + blocks.joinToString("\n\n") + "\n\nQuestion: $question"
    }

    private val citationOnlyLine = Regex("""^(\[\d+][\s,;.]*)+$""")
    private val citation = Regex("""\[\d+]""")

    /**
     * Small models sometimes open with the not-found sentence and then answer from
     * the sources anyway. Drop that sentence when the rest cites a source. Lines that
     * are nothing but citations ("[1], [2], [3]") add nothing and go too.
     */
    fun tidy(answer: String): String {
        val kept = answer.split("\n").filter { line ->
            val t = line.trim(' ', '\t')
            t.isEmpty() || !citationOnlyLine.matches(t)
        }.joinToString("\n")
        val trimmed = kept.trim()
        if (!trimmed.lowercase().startsWith(NOT_FOUND.lowercase())) return kept
        var rest = trimmed.substring(NOT_FOUND.length)
        // Skip the rest of that sentence ("… library, but here is what I found:").
        val end = rest.indexOfFirst { it in ".!:\n" }
        if (end >= 0) rest = rest.substring(end + 1)
        val remainder = rest.trim()
        return if (citation.containsMatchIn(remainder)) remainder else kept
    }

    /** The citation numbers in an answer, in order of first use, e.g. "[2] … [1]" -> [2, 1]. */
    fun citations(answer: String): List<Int> =
        citation.findAll(answer).map { it.value.trim('[', ']').toInt() }.distinct().toList()
}
