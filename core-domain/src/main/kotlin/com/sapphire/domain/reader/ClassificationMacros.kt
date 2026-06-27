package com.sapphire.domain.reader

/**
 * PRD §3.5 dynamic macro injection. A content [classification] (from the Tier-1 call)
 * maps to a fixed set of macro actions surfaced at the top of the reader sheet. Each
 * macro carries the label rendered on its chip and a stable [id] the ViewModel uses to
 * route the Tier-2 call.
 *
 * The mapping is intentionally hardcoded rather than LLM-generated: macros are a finite,
 * curated set per class, and the spec table lists exactly these. Unknown classifications
 * fall back to an empty macro list (the fallback chat input is always present regardless).
 *
 * Pure data — no Android deps — so it's unit-testable.
 */
enum class ReaderMacro(val id: String, val label: String) {
    TRACE_STORY_TIMELINE("trace_story_timeline", "Trace Story Timeline"),
    ANALYZE_EVENT_COMMENTARY("analyze_event_commentary", "Analyze Event Commentary"),
    EXPLAIN_THEORETICAL_BACKGROUND("explain_theoretical_background", "Explain Theoretical Background"),
    FETCH_CITATION_GRAPH("fetch_citation_graph", "Fetch Citation Graph"),
    COMPARE_COMPETITOR_PRODUCTS("compare_competitor_products", "Compare Competitor Products"),
    MAP_SYSTEM_ARCHITECTURE("map_system_architecture", "Map System Architecture"),
    ;

    companion object {
        /** PRD §3.5 macro table. */
        fun forClassification(classification: String?): List<ReaderMacro> {
            if (classification.isNullOrBlank()) return emptyList()
            return when (classification.trim()) {
                ClassificationLabels.NEWS_ARTICLE -> listOf(TRACE_STORY_TIMELINE, ANALYZE_EVENT_COMMENTARY)
                ClassificationLabels.ACADEMIC_PAPER -> listOf(EXPLAIN_THEORETICAL_BACKGROUND, FETCH_CITATION_GRAPH)
                ClassificationLabels.TECH_BLOG -> listOf(COMPARE_COMPETITOR_PRODUCTS, MAP_SYSTEM_ARCHITECTURE)
                else -> emptyList()
            }
        }
    }
}

/**
 * The canonical classification label set. Must stay in sync with the model prompt in
 * [com.sapphire.domain.llm.ClassificationResponse]. Top-level so it's reachable from
 * both [ReaderMacro] and [com.sapphire.domain.reader.ReaderOpsUseCase].
 */
object ClassificationLabels {
    const val NEWS_ARTICLE = "News Article"
    const val ACADEMIC_PAPER = "Academic Paper"
    const val TECH_BLOG = "Tech Blog"
    const val OPINION_ESSAY = "Opinion / Essay"
    const val TUTORIAL_GUIDE = "Tutorial / Guide"
    const val OTHER = "Other"
}
