package com.sapphire.domain.agent

/**
 * Offline blueprint fallback — the deterministic twin of the LLM questionnaire. Mirrors
 * the redesign demo's `tuneData()`: keyword-driven task variants, three format shapes,
 * three rule sets, and archetype-matched requirement tags. Used when ✨Generate is
 * unavailable (no key / error / skipped) so Tune always has candidates; never networked.
 */
object AgentBlueprintHeuristics {

    private val STOP = setOf(
        "track", "tracking", "monitor", "about", "with", "from", "that", "this",
        "summarize", "into", "report", "reports", "news", "feed", "using", "when",
        "where", "what", "which", "then", "their", "have", "will", "also", "more",
        "most", "like", "just", "only", "every", "while", "during", "before",
        "after", "open", "source", "follow", "surface", "flag", "real", "changes",
        "week", "daily", "weekly", "bring", "the", "and", "for", "its", "new",
    )

    private data class Archetype(val regex: Regex, val tags: List<String>)

    private val ARCHETYPES = listOf(
        Archetype(
            regex = Regex("secur|advis|breach|cve|incident|outage|vulnerab"),
            tags = listOf("include CVE IDs", "only high/critical severity", "affected products first", "skip vendor marketing"),
        ),
        Archetype(
            regex = Regex("paper|arxiv|research|academ|study|survey"),
            tags = listOf("link the PDF", "note the method novelty", "skip preprints without code", "include author affiliations"),
        ),
        Archetype(
            regex = Regex("release|version|changelog|launch|ship"),
            tags = listOf("link changelogs", "flag breaking changes", "note license changes", "skip patch releases"),
        ),
        Archetype(
            regex = Regex("pric|cost|plan|subscription|billing"),
            tags = listOf("note plan limits", "include unit prices", "flag grandfather clauses", "skip marketing pages"),
        ),
        Archetype(
            regex = Regex("job|hiring|career|salary|layoff"),
            tags = listOf("note comp ranges", "remote only", "skip recruiter spam", "include team names"),
        ),
        Archetype(
            regex = Regex("funding|round|acquisition|invest|ipo|exit"),
            tags = listOf("include round size", "name the investors", "flag strategic shifts", "skip rumors"),
        ),
        Archetype(
            regex = Regex("forum|debate|discourse|reddit|argument"),
            tags = listOf("quote the strongest takes", "note where experts split", "link the threads", "skip evidence-free hot takes"),
        ),
        Archetype(
            regex = Regex("conference|webinar|cfp|event|meetup"),
            tags = listOf("include CFP deadlines", "note early-bird pricing", "tag virtual vs in-person", "skip vendor webinars"),
        ),
    )

    /** Deterministic questionnaire for [goal]; [maxItems] feeds the sweep variant. */
    fun blueprint(goal: String, maxItems: Int, extraNotes: String = ""): AgentBlueprint {
        val keywords = keywords(goal + " " + extraNotes)
        val k1 = keywords.firstOrNull()?.lowercase() ?: "the topic"
        val k2 = keywords.getOrNull(1)?.lowercase() ?: "the field"
        val arch = ARCHETYPES.firstOrNull { it.regex.containsMatchIn((goal + " " + extraNotes).lowercase()) }
        return AgentBlueprint(
            taskVariants = listOf(
                "Sweep the whole $k1 landscape each run; file the strongest $maxItems signals even if unrelated.",
                "Go deep on the 1-2 biggest $k1 stories: full context, the numbers behind the claims, and what they mean for $k2.",
                "Cover only what changed in $k1 since the last run — new entries, breaking changes, reversed decisions. Silence is fine.",
            ),
            formatVariants = listOf(
                "Brief cards: a title under 80 chars, a 2-sentence summary, then 3-5 source links.",
                "Editorial digest: one flowing paragraph per item — what happened, why it matters, who claims otherwise — then the sources.",
                "Bullet deck: a title plus up to 3 bullets and the full source list; attach a cover image when sources provide one.",
            ),
            ruleVariants = listOf(
                "Should cite at least 2 independent sources per item\nShould not file press releases or SEO content\nShould prefer primary sources (repos, papers, filings)",
                "Should file only high-signal items — fewer is better\nShould not repeat near-identical coverage\nShould note disagreements between sources",
                "Should not file anything older than 48 hours\nShould mark updates to earlier stories\nShould write in English only",
            ),
            extraTags = buildList {
                add("only $k1 coverage")
                add("weight $k2 higher")
                addAll(arch?.tags ?: emptyList())
                add("primary sources only")
                add("English only")
            }.take(8),
        )
    }

    /** Shared keyword extraction (module-internal) — [AgentSampleSynthesizer] reuses it. */
    internal fun blueprintKeywords(text: String): List<String> = keywords(text)

    private fun keywords(text: String): List<String> =
        text.split(Regex("[^A-Za-z0-9+#.]+"))
            .filter { it.length > 2 && it.lowercase() !in STOP }
            .distinctBy { it.lowercase() }
}
