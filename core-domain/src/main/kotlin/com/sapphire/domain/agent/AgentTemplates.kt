package com.sapphire.domain.agent

import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentRecency
import com.sapphire.domain.model.AgentStyle
import com.sapphire.domain.model.OutputLanguage

/**
 * One of the four intent categories in the template gallery. The category is the
 * thing the agent *does* (track / condense / learn / signal), not the topic — each
 * teaches a distinct retrieve→synthesize pattern, per the gallery design.
 */
data class AgentTemplateCategory(val id: String, val title: String, val desc: String)

/**
 * A prebuilt agent. [directive] is achievable under PRD §3.7's constraint (search
 * APIs only, no scrapers/code-exec); `[BRACKETS]` mark the single field the user
 * customizes. All agents run on Tier-1 (the only tier surfaced in the builder).
 */
data class AgentTemplate(
    val category: String,
    val name: String,
    val tagline: String,
    val directive: String,
    val frequency: AgentFrequency,
    val triggerTime: String,
    val maxItems: Int,
    val recency: AgentRecency,
    val outputLanguage: OutputLanguage,
    val style: AgentStyle,
)

/**
 * The 12 templates behind the builder's "Browse all" gallery + 3 inline
 * quick-starts. [maxItems] defaults: single-concept templates (Learn) = 1,
 * focused tracking = 3, broad digests/signals = 5.
 */
object AgentTemplates {

    val categories = listOf(
        AgentTemplateCategory("track", "Track a topic", "Follow an evolving space — novelty-filtered summaries."),
        AgentTemplateCategory("digest", "Condense the news", "Rank a flow and format the best of it."),
        AgentTemplateCategory("learn", "Learn daily", "Recurring micro-content that teaches one thing."),
        AgentTemplateCategory("signal", "Signals & releases", "Extract structured facts and log them."),
    )

    val all: List<AgentTemplate> = listOf(
        // TRACK — search → filter for novelty → summarize (3 items)
        AgentTemplate("track", "Research field watch", "Track an evolving academic field",
            "Summarize this week's notable papers and breakthroughs on [TOPIC], with one-line takeaways and arXiv/blog links. Skip anything covered last week.",
            AgentFrequency.WEEKLY, "07:00", 3, AgentRecency.WEEK, OutputLanguage.EN, AgentStyle.ACADEMIC),
        AgentTemplate("track", "Track a technology", "Monitor a framework, language, or tool",
            "New releases, benchmarks, and notable issues for [TECHNOLOGY]. One item per meaningful change — skip patch-level noise.",
            AgentFrequency.HOURLY_4, "07:00", 3, AgentRecency.H24, OutputLanguage.EN, AgentStyle.BRIEF),
        AgentTemplate("track", "Policy & regulation tracker", "Follow rules as they land",
            "Surface new drafts, enforcement actions, and substantive analysis on [POLICY AREA, e.g. EU AI Act] from official sources and reputable commentary.",
            AgentFrequency.WEEKLY, "07:00", 3, AgentRecency.WEEK, OutputLanguage.EN, AgentStyle.BRIEF),
        // DIGEST — search → rank → format the best (5 / 3 / 1 items)
        AgentTemplate("digest", "Daily news brief", "Top stories, every morning",
            "Every morning, compile the top 5 stories on [TOPIC / REGION] with one-line summaries and links. Lead with what changed since yesterday.",
            AgentFrequency.DAILY, "07:30", 5, AgentRecency.H24, OutputLanguage.EN, AgentStyle.BRIEF),
        AgentTemplate("digest", "Competitor watch", "What rivals shipped or priced",
            "Surface product launches, feature drops, and pricing changes from [COMPANY 1, COMPANY 2, COMPANY 3] — one line each, only real changes.",
            AgentFrequency.WEEKDAY, "08:00", 3, AgentRecency.H24, OutputLanguage.EN, AgentStyle.BULLETED),
        AgentTemplate("digest", "Contrarian read", "The strongest dissent, weekly",
            "Each week, find the most persuasive dissenting or minority view on the [TOPIC] story everyone covered. Steelman it — lay out the core argument fairly.",
            AgentFrequency.WEEKLY, "07:00", 1, AgentRecency.WEEK, OutputLanguage.EN, AgentStyle.HOTTAKE),
        // LEARN — search → synthesize into a self-contained lesson (1 item each)
        AgentTemplate("learn", "Concept of the day", "One idea, explained clearly",
            "Teach one [FIELD] concept daily — a plain-language explanation, why it matters, and one concrete example. Rotate so no concept repeats within 90 days.",
            AgentFrequency.DAILY, "07:00", 1, AgentRecency.ALL, OutputLanguage.EN, AgentStyle.EXPLAINER),
        AgentTemplate("learn", "Today in history", "Depth on one anniversary",
            "One notable event from this day in history, with depth: what happened, why it mattered then, and a primary source link. Vary eras and regions.",
            AgentFrequency.DAILY, "07:00", 1, AgentRecency.ALL, OutputLanguage.EN, AgentStyle.EXPLAINER),
        AgentTemplate("learn", "Phrase of the day", "Learn a language, one idiom at a time",
            "One useful [TARGET LANGUAGE] idiom or phrase daily — literal translation, actual meaning, and a natural usage example. Tag difficulty beginner/intermediate/advanced.",
            AgentFrequency.DAILY, "07:00", 1, AgentRecency.ALL, OutputLanguage.MATCH_SOURCE, AgentStyle.CONVERSATIONAL),
        // SIGNAL — search → extract structured fields → log (5 / 5 / 3 items)
        AgentTemplate("signal", "Funding rounds", "Log every deal above a bar",
            "Every [SECTOR] funding round over [\$THRESHOLD] — amount, lead investor, stage, and a one-line thesis. Skip undisclosed-amount rounds.",
            AgentFrequency.WEEKLY, "07:00", 5, AgentRecency.WEEK, OutputLanguage.EN, AgentStyle.BRIEF),
        AgentTemplate("signal", "Job market signal", "Where a role is heading",
            "Weekly hiring trends for [ROLE] in [REGION / SECTOR] — which skills are rising, which are cooling, and any notable postings. Read the tea leaves, don't just list jobs.",
            AgentFrequency.WEEKLY, "07:00", 5, AgentRecency.WEEK, OutputLanguage.EN, AgentStyle.BRIEF),
        AgentTemplate("signal", "Release tracker", "New books, albums, films, games",
            "New [BOOKS / ALBUMS / FILMS / GAMES] releases in [GENRE] this week — one item per notable release with a one-line take and where to find it.",
            AgentFrequency.WEEKLY, "07:00", 3, AgentRecency.WEEK, OutputLanguage.EN, AgentStyle.BRIEF),
    )

    /** Indices into [all] for the builder's 3 inline quick-starts. */
    val quickStartIndices = listOf(0, 4, 2)

    /** Templates grouped by category, in category order. */
    fun byCategory(catId: String): List<AgentTemplate> = all.filter { it.category == catId }
}
