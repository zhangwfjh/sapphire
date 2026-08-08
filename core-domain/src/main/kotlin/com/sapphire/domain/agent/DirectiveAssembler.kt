package com.sapphire.domain.agent

/**
 * Assembles the four structured prompt fields into the final directive string consumed by
 * [AgentLoopService]. Pure function, heavily unit-tested.
 *
 * Fields that are blank are omitted. Goal-only (everything else blank) collapses to just
 * the goal text -- graceful partial for users who skip Generate.
 *
 * An implicit RSS-style base instruction is always appended so every agent produces
 * feed-readable output regardless of whether the user wrote a Format field: titles must
 * be card-suitable (under 80 chars), bodies must be scannable, each item must have a URL.
 */
object DirectiveAssembler {

    fun assemble(goal: String, task: String, format: String, rules: String, maxItems: Int): String {
        val sections = mutableListOf<String>()
        goal.trim().takeIf { it.isNotBlank() }?.let { sections.add("GOAL: " + it) }
        task.trim().takeIf { it.isNotBlank() }?.let { sections.add("TASK: " + it) }
        format.trim().takeIf { it.isNotBlank() }?.let { sections.add("FORMAT: " + it) }
        rules.trim().takeIf { it.isNotBlank() }?.let { sections.add("RULES: " + it) }
        if (sections.isEmpty()) return ""
        return sections.joinToString("\n\n") + "\n\n" + baseInstruction(maxItems)
    }

    private fun baseInstruction(maxItems: Int): String =
        "OUTPUT REQUIREMENTS (always apply):\n" +
        "- Each item MUST have: a title (under 80 chars, plain text), a one-line summary (plain text), a body (HTML), and a source URL.\n" +
        "- The body must be valid HTML: use <h2> for headings, <p> for paragraphs, <ul><li>/<ol><li> for lists, <blockquote> for quotes, <strong>/<em> for emphasis. No wrapper tags (<html>/<body>) — just content tags.\n" +
        "- Write rich, substantive content: full paragraphs explaining key ideas in depth. Aim for at least 300-500 words for single-item digests.\n" +
        "- Default to prose paragraphs. Use bullet lists only when the FORMAT field explicitly requests them.\n" +
        "- Produce at most " + maxItems + " item(s) per run."
}
