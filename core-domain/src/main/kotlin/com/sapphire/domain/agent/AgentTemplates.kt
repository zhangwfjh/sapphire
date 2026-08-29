package com.sapphire.domain.agent

import com.sapphire.domain.model.AgentFrequency

/**
 * One preset in the wizard's Describe gallery — a concrete, ready-to-tune agent:
 * emoji icon + editorial name + goal preset + sensible cadence defaults, mirroring
 * the redesign demo's template grid. Tapping one preloads the form; nothing is locked.
 */
data class AgentTemplate(
    val icon: String,
    val name: String,
    val tagline: String,
    val goal: String,
    val frequency: AgentFrequency,
    val triggerTime: String,
    val maxItems: Int,
)

object AgentTemplates {
    val all: List<AgentTemplate> = listOf(
        AgentTemplate(
            icon = "🔖", name = "Release Watcher", tagline = "changelogs for Rust, Kubernetes, VS Code",
            goal = "Watch releases of Rust, Kubernetes, and VS Code; file changelog digests with breaking-change callouts",
            frequency = AgentFrequency.HOURLY_6, triggerTime = "07:00", maxItems = 3,
        ),
        AgentTemplate(
            icon = "📡", name = "Paper Trail", tagline = "new arXiv cs.CL papers, digested",
            goal = "Surface new arXiv cs.CL papers about efficient LLM inference; digest the abstract, method, and why it matters",
            frequency = AgentFrequency.DAILY, triggerTime = "07:00", maxItems = 3,
        ),
        AgentTemplate(
            icon = "🗞", name = "Industry Brief", tagline = "the last 24h of AI industry news",
            goal = "Synthesize the last 24 hours of AI industry news into a single annotated brief",
            frequency = AgentFrequency.DAILY, triggerTime = "07:30", maxItems = 5,
        ),
        AgentTemplate(
            icon = "🥊", name = "Competitor Intel", tagline = "OpenAI, Anthropic, Google product moves",
            goal = "Track product launches, pricing changes, and hiring posts from OpenAI, Anthropic, and Google DeepMind; summarize each move and its signal",
            frequency = AgentFrequency.WEEKDAY, triggerTime = "08:00", maxItems = 3,
        ),
        AgentTemplate(
            icon = "💰", name = "Funding Radar", tagline = "AI-infrastructure rounds & exits",
            goal = "Track venture rounds and acquisitions in AI infrastructure; note amount, investors, and the strategic angle",
            frequency = AgentFrequency.WEEKLY, triggerTime = "07:00", maxItems = 5,
        ),
        AgentTemplate(
            icon = "🚨", name = "Incident Feed", tagline = "CVEs, cloud outages, big breaches",
            goal = "Monitor security advisories, cloud outages, and breach disclosures affecting AWS, GCP, and widely-deployed open-source projects",
            frequency = AgentFrequency.HOURLY_2, triggerTime = "07:00", maxItems = 4,
        ),
        AgentTemplate(
            icon = "🔥", name = "Discourse Digest", tagline = "HN & Reddit debates on AI tooling",
            goal = "Summarize Hacker News and Reddit debates about AI tooling: positions, evidence, and where experts split",
            frequency = AgentFrequency.WEEKLY, triggerTime = "07:00", maxItems = 3,
        ),
        AgentTemplate(
            icon = "💼", name = "Job Signals", tagline = "ML-platform roles, comp, layoffs",
            goal = "Track ML-platform job postings, compensation shifts, and layoffs; note location, level, and team",
            frequency = AgentFrequency.WEEKLY, triggerTime = "07:00", maxItems = 5,
        ),
        AgentTemplate(
            icon = "📉", name = "Price Watch", tagline = "SaaS & cloud pricing changes",
            goal = "Watch pricing and plan changes for SaaS and cloud services; flag increases, new tiers, and hidden caps",
            frequency = AgentFrequency.HOURLY_12, triggerTime = "07:00", maxItems = 3,
        ),
        AgentTemplate(
            icon = "⚖️", name = "Policy Radar", tagline = "US & EU AI regulation moves",
            goal = "Track AI regulation, standards, and court rulings across the US and EU; summarize obligations and timelines",
            frequency = AgentFrequency.WEEKLY, triggerTime = "07:00", maxItems = 3,
        ),
        AgentTemplate(
            icon = "🗓", name = "Event Scout", tagline = "ML conferences & CFP deadlines",
            goal = "Surface machine-learning conferences, workshops, and CFP deadlines; note dates, location, and submission cutoffs",
            frequency = AgentFrequency.WEEKLY, triggerTime = "07:00", maxItems = 3,
        ),
        AgentTemplate(
            icon = "🧠", name = "Concept Lesson", tagline = "one core ML idea, explained",
            goal = "Teach one core machine-learning concept: intuition first, then mechanics, then where it bites in production",
            frequency = AgentFrequency.DAILY, triggerTime = "07:00", maxItems = 1,
        ),
    )
}
