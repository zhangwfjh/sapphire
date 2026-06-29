package com.sapphire.domain.model

/**
 * Dispatch key for ingestion. New kinds extend the [Fetcher] interface in core-data.
 * S01 only persists RSS/ATOM/JSON rows; AGENT_* are defined now so the schema
 * is stable when S04/S05 land.
 */
enum class SourceKind {
    RSS,
    ATOM,
    JSON,
    AGENT_SEARCH,
    AGENT_PROMPT,
}

/** Health of a [Source]'s last fetch; S06 surfaces this in the UI. */
enum class HealthState { OK, DEGRADED, FAILED }

/** Read state of a [FeedItem]. UNREAD is default on ingest; transitions to READ via the
 * scroll-to-mark-read rules (PRD §3.3) or manual toggle. */
enum class ReadState { UNREAD, READ }

/** How a [FeedItem] became READ (or reverted to UNREAD). Drives the ReadLog row. */
enum class ReadMechanism { DWELL, SCROLLED_PAST, MANUAL }
