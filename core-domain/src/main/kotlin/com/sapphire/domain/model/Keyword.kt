package com.sapphire.domain.model

/** Context keyword attached to an L2 category. [userAdded] distinguishes AI vs manual. */
data class Keyword(
    val id: String,
    val categoryId: String,
    val text: String,
    val userAdded: Boolean,
)
