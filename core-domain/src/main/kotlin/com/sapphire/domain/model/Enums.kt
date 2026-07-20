package com.sapphire.domain.model

/**
 * Dispatch key for ingestion. New kinds extend the [Fetcher] interface in core-data.
 * RSS/ATOM/JSON rows are persisted today; AGENT_* kinds are defined now so the schema
 * is stable when agent ingestion lands.
 */
enum class SourceKind {
    RSS,
    ATOM,
    JSON,
    AGENT_SEARCH,
    AGENT_PROMPT,
}

/** Health of a [Source]'s last fetch; surfaced in the UI. */
enum class HealthState { OK, DEGRADED, FAILED }

/** Read state of a [FeedItem]. UNREAD is default on ingest; transitions to READ via the
 * scroll-to-mark-read rules or manual toggle. */
enum class ReadState { UNREAD, READ }

/** How a [FeedItem] became READ (or reverted to UNREAD). Drives the ReadLog row. */
enum class ReadMechanism { DWELL, SCROLLED_PAST, MANUAL }
