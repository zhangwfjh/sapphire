package com.sapphire.data.onboarding

import com.sapphire.data.db.CategoryEntity
import com.sapphire.data.db.KeywordEntity
import com.sapphire.data.db.SourceEntity
import com.sapphire.data.db.TopicEntity
import com.sapphire.domain.model.HealthState
import com.sapphire.domain.review.ReviewModel
import com.sapphire.domain.util.IdGenerator

/**
 * Maps the committed [ReviewModel] to the Room entity batch.
 *
 * Single-level: each review folder becomes one Level-1 category (parent_id = null) with
 * its keywords and sources attached directly. IDs are minted via [IdGenerator] for
 * testability.
 */
class ReviewMapper(private val ids: IdGenerator) {

    fun map(review: ReviewModel, now: Long): OnboardingBatch {
        val topicId = ids.uuid()
        val topic = TopicEntity(id = topicId, phrase = review.topicPhrase, createdAt = now)

        val categories = mutableListOf<CategoryEntity>()
        val keywords = mutableListOf<KeywordEntity>()
        val sources = mutableListOf<SourceEntity>()

        review.folders.forEachIndexed { index, folder ->
            val categoryId = ids.uuid()
            categories += CategoryEntity(
                id = categoryId,
                topicId = topicId,
                level = 1,
                parentId = null,
                name = folder.name,
                sortOrder = index,
            )
            keywords += folder.keywords.map { kw ->
                KeywordEntity(
                    id = ids.uuid(),
                    categoryId = categoryId,
                    text = kw.text,
                    userAdded = kw.userAdded,
                )
            }
            sources += folder.feeds.map { feed ->
                SourceEntity(
                    id = ids.uuid(),
                    categoryId = categoryId,
                    topicId = topicId,
                    kind = feed.kind,
                    url = feed.url,
                    title = feed.title,
                    healthState = HealthState.OK,
                )
            }
        }

        return OnboardingBatch(topic, categories, keywords, sources)
    }
}

/** A complete, transactional onboarding insert batch. */
data class OnboardingBatch(
    val topic: TopicEntity,
    val categories: List<CategoryEntity>,
    val keywords: List<KeywordEntity>,
    val sources: List<SourceEntity>,
)
