package com.sapphire.data.onboarding

import com.sapphire.data.db.OnboardingDao
import com.sapphire.domain.onboarding.OnboardingRepository
import com.sapphire.domain.review.ReviewModel
import com.sapphire.domain.util.IdGenerator
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed [OnboardingRepository]. Delegates the transactional insert to [OnboardingDao]
 * (annotated `@Transaction`); runs on IO dispatcher.
 */
class RoomOnboardingRepository @Inject constructor(
    private val dao: OnboardingDao,
    private val mapper: ReviewMapper,
) : OnboardingRepository {

    override suspend fun commitReview(review: ReviewModel): String = withContext(Dispatchers.IO) {
        val batch = mapper.map(review, now = System.currentTimeMillis())
        dao.commitOnboarding(
            topic = batch.topic,
            categories = batch.categories,
            keywords = batch.keywords,
            sources = batch.sources,
        )
        batch.topic.id
    }
}

/** Provider for Hilt — [ReviewMapper] needs an [IdGenerator]. */
class ReviewMapperProvider @Inject constructor(
    private val ids: IdGenerator,
) {
    fun provide(): ReviewMapper = ReviewMapper(ids)
}
