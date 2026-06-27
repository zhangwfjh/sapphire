package com.sapphire.domain.onboarding

import com.sapphire.domain.review.ReviewModel

/**
 * Persistence boundary for onboarding. Implementations live in core-data (Room).
 *
 * [commitReview] is transactional: a Topic, its Categories (L1+L2), Keywords, and Sources
 * are inserted together or not at all. Returns the committed [Topic.id].
 */
interface OnboardingRepository {
    suspend fun commitReview(review: ReviewModel): String
}
