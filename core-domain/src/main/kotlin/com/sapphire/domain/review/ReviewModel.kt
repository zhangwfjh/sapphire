package com.sapphire.domain.review

import com.sapphire.domain.model.SourceKind

/**
 * The editable staging model for the Review/Approve wizard (PRD §3.1). Built from a
 * [TaxonomyResponse] by [ReviewBuilder], mutated by the user, and committed by
 * [OnboardingRepository.commitReview] when the user approves.
 *
 * Single-level: each folder holds its own keywords and feeds directly — there are no
 * sub-folders. The taxonomy is a flat list of named folders.
 * Why a separate model and not the raw LLM DTO? The DTO has no IDs,
 * no deletion/append history. The review surface needs: delete folder, rename,
 * append keyword pills, inject manual URLs — all as reversible edits. This type
 * holds that exact mutable shape; the DB is only touched on commit.
 */
data class ReviewModel(
    val topicPhrase: String,
    val folders: List<ReviewFolder>,
)

data class ReviewFolder(
    val id: String,
    val name: String,
    val keywords: MutableList<ReviewKeyword>,
    val feeds: MutableList<ReviewFeed>,
)

data class ReviewKeyword(
    val id: String,
    val text: String,
    val userAdded: Boolean,
)

data class ReviewFeed(
    val id: String,
    val title: String,
    val url: String,
    val kind: SourceKind,
    val userAdded: Boolean,
)
