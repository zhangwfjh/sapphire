package com.sapphire.domain.model

/** A user-created topic; the root of the onboarding taxonomy tree. Anonymous (no account). */
data class Topic(
    val id: String,
    val phrase: String,
    val createdAt: Long,
)
