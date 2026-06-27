package com.sapphire.domain.onboarding

import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.llm.TaxonomyResponse
import com.sapphire.domain.review.ReviewBuilder
import com.sapphire.domain.review.ReviewModel

/**
 * PRD §3.1 step 1 — turn a phrase into an editable taxonomy + recommended feeds.
 *
 * Returns a [ReviewModel] (staging state), not persisted entities. Persistence happens
 * on approve via [OnboardingRepository.commitReview]. Tier-1 fast model per PRD §4.2.
 *
 * Failures are typed outcomes, not exceptions: an empty category list maps to
 * [LlmError.Empty] which the UI renders as the PRD §3.1 "try a broader topic" modal.
 */
class CurateTaxonomyUseCase(
    private val llm: LlmClient,
    private val reviewBuilder: ReviewBuilder,
) {

    suspend operator fun invoke(phrase: String): LlmOutcome<ReviewModel> {
        val cleaned = phrase.trim()
        if (cleaned.isEmpty()) return LlmOutcome.Err(LlmError.Empty("Type a topic to begin."))

        val outcome = llm.completeStructured(
            tier = LlmTier.TIER1_FAST,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = userPrompt(cleaned),
            outputSerializer = TaxonomyResponse.serializer(),
        )

        return when (outcome) {
            is LlmOutcome.Err -> outcome
            is LlmOutcome.Ok -> {
                if (outcome.value.categories.isEmpty()) {
                    LlmOutcome.Err(LlmError.Empty("No categories found. Try a broader topic."))
                } else {
                    LlmOutcome.Ok(reviewBuilder.build(cleaned, outcome.value))
                }
            }
        }
    }

    private fun userPrompt(topic: String) = "Topic: $topic"

    companion object {
        // Kept short for cost. The JSON schema is the real contract — see LlmClient impl.
        internal val SYSTEM_PROMPT = """
You are Sapphire's feed curator. For the user's topic, produce a two-level content taxonomy
with recommended high-signal feeds.

Rules:
- Return 2-4 Level-1 categories. Each has 2-4 Level-2 sub-categories.
- Every Level-2 sub-category MUST include 3-6 context keywords and 1-3 recommended feeds.
- Prefer feeds the user can actually subscribe to: RSS/Atom/JSON Feed URLs or RSSHub routes.
- If you cannot find high-signal feeds for a sub-category, return that sub-category with an
  empty feeds array — the client seeds an autonomous web-search agent for it.
- feed.kind is one of: rss, atom, json, rsshub.
- Output STRICT JSON matching this shape:
  {"categories":[{"name":string,"level2":[{"name":string,"keywords":[string],"feeds":[{"title":string,"url":string,"kind":string}]}]}]}
- No prose outside the JSON object.
""".trimIndent()
    }
}
