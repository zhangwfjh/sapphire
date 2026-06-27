package com.sapphire.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD §3.5 dynamic macro table — classification → injected macros. The mapping is the
 * hardcoded spec table; unknown/blank classifications fall back to an empty list (the
 * fallback chat input is always present regardless).
 */
class ClassificationMacrosTest {

    @Test
    fun `News Article injects timeline and commentary macros`() {
        val macros = ReaderMacro.forClassification(ClassificationLabels.NEWS_ARTICLE)
        assertEquals(2, macros.size)
        assertEquals(ReaderMacro.TRACE_STORY_TIMELINE, macros[0])
        assertEquals(ReaderMacro.ANALYZE_EVENT_COMMENTARY, macros[1])
    }

    @Test
    fun `Academic Paper injects background and citation macros`() {
        val macros = ReaderMacro.forClassification(ClassificationLabels.ACADEMIC_PAPER)
        assertEquals(listOf(ReaderMacro.EXPLAIN_THEORETICAL_BACKGROUND, ReaderMacro.FETCH_CITATION_GRAPH), macros)
    }

    @Test
    fun `Tech Blog injects competitor and architecture macros`() {
        val macros = ReaderMacro.forClassification(ClassificationLabels.TECH_BLOG)
        assertEquals(listOf(ReaderMacro.COMPARE_COMPETITOR_PRODUCTS, ReaderMacro.MAP_SYSTEM_ARCHITECTURE), macros)
    }

    @Test
    fun `unmapped classifications return empty macro list`() {
        assertTrue(ReaderMacro.forClassification(ClassificationLabels.OPINION_ESSAY).isEmpty())
        assertTrue(ReaderMacro.forClassification(ClassificationLabels.TUTORIAL_GUIDE).isEmpty())
        assertTrue(ReaderMacro.forClassification(ClassificationLabels.OTHER).isEmpty())
    }

    @Test
    fun `blank or null classification returns empty macro list`() {
        assertTrue(ReaderMacro.forClassification(null).isEmpty())
        assertTrue(ReaderMacro.forClassification("").isEmpty())
        assertTrue(ReaderMacro.forClassification("   ").isEmpty())
    }

    @Test
    fun `classification label is trimmed before lookup`() {
        val macros = ReaderMacro.forClassification("  ${ClassificationLabels.NEWS_ARTICLE}  ")
        assertEquals(2, macros.size)
    }
}
