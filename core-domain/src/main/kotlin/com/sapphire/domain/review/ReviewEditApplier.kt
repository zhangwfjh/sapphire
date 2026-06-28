package com.sapphire.domain.review

import com.sapphire.domain.model.SourceKind

/** Reversible edit operations applied to a [ReviewModel] by the review wizard. */
sealed interface ReviewEdit {
    data class RenameFolder(val folderId: String, val name: String) : ReviewEdit
    data class DeleteFolder(val folderId: String) : ReviewEdit
    data class AddKeyword(val folderId: String, val text: String) : ReviewEdit
    data class RemoveKeyword(val keywordId: String) : ReviewEdit
    data class AddManualFeed(
        val folderId: String,
        val url: String,
        val title: String,
        val kind: SourceKind,
    ) : ReviewEdit
}

/**
 * Applies a single edit to a copied [ReviewModel]. Immutable at the top level: returns a
 * new [ReviewModel] so Compose state stays predictable. Inner lists are mutated on the
 * copy for simplicity; callers must not retain references across edits.
 */
object ReviewEditApplier {

    fun apply(model: ReviewModel, edit: ReviewEdit): ReviewModel {
        return when (edit) {
            is ReviewEdit.RenameFolder -> model.copy(
                folders = model.folders.map { f ->
                    if (f.id == edit.folderId) f.copy(name = edit.name.trim()) else f
                },
            )

            is ReviewEdit.DeleteFolder -> model.copy(
                folders = model.folders.filter { it.id != edit.folderId },
            )

            is ReviewEdit.AddKeyword -> {
                val text = edit.text.trim()
                if (text.isBlank()) return model
                model.copy(
                    folders = model.folders.map { f ->
                        if (f.id != edit.folderId) f else f.copy(
                            keywords = (f.keywords + ReviewKeyword(
                                id = f.keywords.nextId(),
                                text = text,
                                userAdded = true,
                            )).toMutableList(),
                        )
                    },
                )
            }

            is ReviewEdit.RemoveKeyword -> model.copy(
                folders = model.folders.map { f ->
                    f.copy(
                        keywords = f.keywords.filter { it.id != edit.keywordId }.toMutableList(),
                    )
                },
            )

            is ReviewEdit.AddManualFeed -> model.copy(
                folders = model.folders.map { f ->
                    if (f.id != edit.folderId) f else f.copy(
                        feeds = (f.feeds + ReviewFeed(
                            id = f.feeds.nextId(),
                            title = edit.title.trim().ifBlank { edit.url },
                            url = edit.url.trim(),
                            kind = edit.kind,
                            userAdded = true,
                        )).toMutableList(),
                    )
                },
            )
        }
    }

    /** Deterministic id for inline-added items; review state is short-lived so collisions are fine. */
    private fun <T> List<T>.nextId(): String = "local-${System.nanoTime()}-${size}"
}
