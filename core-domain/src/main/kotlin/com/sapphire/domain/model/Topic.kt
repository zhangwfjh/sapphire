package com.sapphire.domain.model

/** A user-created topic; the root of the taxonomy tree (folders + sources). Anonymous (no account). */
data class Topic(
    val id: String,
    val phrase: String,
    val createdAt: Long,
)
