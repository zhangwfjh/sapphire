package com.sapphire.domain.agent

/**
 * Offline sample generator for the wizard's Preview step: one full article synthesized
 * deterministically from the goal + the user's chosen answers — zero tokens, instant,
 * and it visibly follows format/rule choices (bullets vs prose, cover vs not). The real
 * shape check is the ⚡ dry run; this is the "what will it read like" sketch.
 */
object AgentSampleSynthesizer {

    /** One preview article: HTML body (reader-ready) + meta, mirroring [AgentSynthesisItem]. */
    data class Sample(
        val title: String,
        val summary: String,
        val bodyHtml: String,
        val sourceDomains: List<String>,
        val approximateTokens: Int,
    )

    fun synthesize(
        goal: String,
        task: String,
        format: String,
        rules: String,
        extraNotes: String = "",
        maxItems: Int = 1,
    ): Sample {
        val keywords = AgentBlueprintHeuristics.blueprintKeywords(goal + " " + extraNotes)
        val k1 = keywords.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "The topic"
        val k2 = keywords.getOrNull(1)?.lowercase() ?: "the field"
        val wantsBullets = Regex("bullet|<ul|deck", RegexOption.IGNORE_CASE).containsMatchIn(format)
        val wantsCover = Regex("cover|image", RegexOption.IGNORE_CASE).containsMatchIn(format + " " + rules + " " + extraNotes)
        val title = "$k1: what moved this week, and why it matters"
        val lede = "$k1 had a busy cycle. This sample sketches how your agent would report it — the shape is real, the facts are placeholders until you run the loop."
        val context = "Teams watching $k2 report measurable shifts, though the numbers behind the claims vary by source. Your agent would read the full text of each candidate before synthesizing, cite at least two independent sources, and flag where accounts disagree."
        val stakes = "For $k1 watchers the practical question is what changes now versus what is noise. A good filing answers that in one read; a great one links the receipts."
        val body = if (wantsBullets) {
            buildString {
                appendLine("<h2>Highlights</h2><ul>")
                appendLine("<li>$lede</li>")
                appendLine("<li>$context</li>")
                appendLine("<li>$stakes</li>")
                appendLine("</ul>")
            }
        } else {
            buildString {
                appendLine("<p>$lede</p>")
                appendLine("<p>$context</p>")
                appendLine("<p>$stakes</p>")
            }
        }
        return Sample(
            title = title,
            summary = lede,
            bodyHtml = body,
            sourceDomains = if (wantsCover) {
                listOf("example.com", "primary-source.org", "industry-letter.dev")
            } else {
                listOf("example.com", "primary-source.org", "industry-letter.dev")
            },
            approximateTokens = 1200 * maxItems.coerceIn(1, 2),
        )
    }
}
