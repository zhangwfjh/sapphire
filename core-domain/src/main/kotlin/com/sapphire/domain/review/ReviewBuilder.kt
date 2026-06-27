package com.sapphire.domain.review

import com.sapphire.domain.llm.TaxonomyResponse
import com.sapphire.domain.util.IdGenerator
import com.sapphire.domain.util.parseSourceKind

/**
 * Pure function: build an editable [ReviewModel] from a raw [TaxonomyResponse].
 *
 * Flattens the LLM's two-level taxonomy into a single-level folder list. Each Level-1
 * category becomes one folder; its Level-2 sub-categories' keywords and feeds are merged
 * into that folder (feeds de-duplicated by URL across the whole L1). Blank-named
 * categories/feeds are skipped.
 *
 * Assigns stable string ids (via [IdGenerator]) so Compose recomposition and edit
 * tracking (delete/toggle/rename) are keyed correctly.
 */
class ReviewBuilder(private val ids: IdGenerator) {

    fun build(phrase: String, response: TaxonomyResponse): ReviewModel {
        val folders = response.categories.mapIndexed { l1Index, raw ->
            ReviewFolder(
                id = ids.uuid(),
                name = raw.name.trim().ifBlank { defaultFolderName(l1Index) },
                keywords = raw.level2
                    .flatMap { it.keywords }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { ReviewKeyword(id = ids.uuid(), text = it, userAdded = false) }
                    .toMutableList(),
                feeds = raw.level2
                    .flatMap { it.feeds }
                    .filter { it.url.isNotBlank() && it.title.isNotBlank() }
                    .map { it.copy(url = it.url.trim()) }
                    .distinctBy { it.url }
                    .map { feed ->
                        ReviewFeed(
                            id = ids.uuid(),
                            title = feed.title.trim(),
                            url = feed.url,
                            kind = parseSourceKind(feed.kind),
                            enabled = true,
                            userAdded = false,
                        )
                    }
                    .toMutableList(),
            )
        }
        return ReviewModel(topicPhrase = phrase.trim(), folders = folders)
    }

    private fun defaultFolderName(index: Int) = "Folder ${index + 1}"
}
