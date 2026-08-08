package com.sapphire.domain.agent

import com.sapphire.domain.model.AgentFrequency

data class AgentTemplateCategory(val id: String, val title: String, val desc: String)

data class AgentTemplate(
    val category: String,
    val name: String,
    val tagline: String,
    val goal: String,
    val frequency: AgentFrequency,
    val triggerTime: String,
    val maxItems: Int,
)

object AgentTemplates {
    val categories = listOf(
        AgentTemplateCategory("track", "Track a topic", "Follow an evolving space."),
        AgentTemplateCategory("digest", "Condense the news", "Rank a flow and format the best of it."),
        AgentTemplateCategory("learn", "Learn daily", "Recurring micro-content."),
        AgentTemplateCategory("signal", "Signal extract", "Pull structured fields from a flow."),
    )
    val all: List<AgentTemplate> = listOf(
        AgentTemplate("track", "Research tracker", "Papers and breakthroughs, weekly", "Summarize this week notable papers and breakthroughs on [TOPIC].", AgentFrequency.WEEKLY, "07:00", 3),
        AgentTemplate("track", "Track a technology", "Monitor a framework or tool", "New releases, benchmarks, and notable issues for [TECHNOLOGY].", AgentFrequency.HOURLY_4, "07:00", 3),
        AgentTemplate("track", "Policy tracker", "Follow rules as they land", "Surface new drafts and enforcement actions on [POLICY AREA].", AgentFrequency.WEEKLY, "07:00", 3),
        AgentTemplate("digest", "Daily news brief", "Top stories, every morning", "Compile the top 5 stories on [TOPIC] with one-line summaries.", AgentFrequency.DAILY, "07:30", 5),
        AgentTemplate("digest", "Competitor watch", "What rivals shipped", "Surface product launches and pricing changes from [COMPANIES].", AgentFrequency.WEEKDAY, "08:00", 3),
        AgentTemplate("digest", "Contrarian read", "Strongest dissent, weekly", "Find the most persuasive dissenting view on [TOPIC].", AgentFrequency.WEEKLY, "07:00", 1),
        AgentTemplate("learn", "Concept of the day", "One idea, explained", "Teach one [FIELD] concept daily.", AgentFrequency.DAILY, "07:00", 1),
        AgentTemplate("learn", "Today in history", "Depth on one anniversary", "One notable event from this day in history.", AgentFrequency.DAILY, "07:00", 1),
        AgentTemplate("learn", "Phrase of the day", "Learn a language", "One useful [LANGUAGE] idiom daily.", AgentFrequency.DAILY, "07:00", 1),
        AgentTemplate("signal", "Funding rounds", "Log every deal", "Every [SECTOR] funding round over threshold.", AgentFrequency.WEEKLY, "07:00", 5),
        AgentTemplate("signal", "Job market signal", "Where a role is heading", "Weekly hiring trends for [ROLE].", AgentFrequency.WEEKLY, "07:00", 5),
        AgentTemplate("signal", "Release tracker", "New media releases", "New [MEDIA] releases this week.", AgentFrequency.WEEKLY, "07:00", 3),
    )
    val quickStartIndices = listOf(0, 4, 7)
    fun byCategory(catId: String): List<AgentTemplate> = all.filter { it.category == catId }
}
