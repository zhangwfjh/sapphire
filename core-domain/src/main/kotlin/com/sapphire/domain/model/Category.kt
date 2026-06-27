package com.sapphire.domain.model

/**
 * Two-level taxonomy node. [level] == 1 is a top folder (parent == null);
 * [level] == 2 is a sub-folder whose [parentId] points at its L1.
 */
data class Category(
    val id: String,
    val topicId: String,
    val level: Int,
    val parentId: String?,
    val name: String,
    val sortOrder: Int,
)
