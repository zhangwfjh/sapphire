package com.sapphire.domain.source

import com.sapphire.domain.model.Category
import com.sapphire.domain.model.Source
import com.sapphire.domain.model.SourceKind
import kotlinx.coroutines.flow.Flow

/** Per-source item counts. [unread] and [total] over the feed items from that source. */
data class SourceCounts(val unread: Int, val total: Int)

/** A source in the tree, paired with its item counts. */
data class SourceNode(
    val source: Source,
    val counts: SourceCounts,
)

/**
 * A single node in the ordered contents of a folder — either a direct source ([Leaf]) or a
 * virtual domain-group sub-folder ([Group]). Sorted by name so groups interleave with
 * singletons rather than lumping at the end.
 */
sealed interface SourceTreeNode {
    val name: String
    data class Leaf(val node: SourceNode) : SourceTreeNode {
        override val name: String get() = node.source.title ?: node.source.url
    }
    data class Group(val folder: SourceFolderNode) : SourceTreeNode {
        override val name: String get() = folder.category.name
    }
}

/**
 * A folder in the sources tree. [entries] is the name-sorted, interleaved view of direct
 * sources ([Leaf]) and virtual domain sub-folders ([Group]); [sources] and [children] keep
 * the split for serialization/merge dialogs.
 */
data class SourceFolderNode(
    val category: Category,
    val sources: List<SourceNode>,
    val children: List<SourceFolderNode> = emptyList(),
    val entries: List<SourceTreeNode> = emptyList(),
)

/**
 * CRUD + move for post-onboarding source/category management (PRD §3.1 — the Sources drawer
 * is the always-available edit surface, where the Review wizard was the one-shot bootstrap).
 *
 * Reads are a cold [Flow] so Compose recomposes only on real change. Writes are suspend;
 * [moveSource] reassigns a source to a different category. [Outcome] surfaces unique-index
 * conflicts (the `(category_id, url)` index on SourceEntity) so the UI can warn the user
 * instead of silently dropping the move.
 */
interface SourceRepository {

    fun observeTree(): Flow<List<SourceFolderNode>>

    /**
     * Reactive "does any topic exist?" — the gate for folder creation. A topic outlives
     * its categories, so this stays true even when the user has deleted every folder;
     * the tree-based check (`tree.firstOrNull()?.category?.topicId`) wrongly returned
     * null in that state and blocked the first folder from being created.
     */
    fun observeHasTopic(): Flow<Boolean>

    /** The id of the first (oldest) topic, or null if none exists yet. */
    suspend fun currentTopicId(): String?

    suspend fun addSource(categoryId: String, url: String, title: String, kind: SourceKind): Outcome

    suspend fun updateSource(
        id: String,
        title: String,
        url: String,
        kind: SourceKind,
    ): Outcome

    suspend fun moveSource(id: String, toCategoryId: String): Outcome

    suspend fun deleteSource(id: String)

    suspend fun addCategory(topicId: String, name: String): String

    suspend fun renameCategory(id: String, name: String)

    /**
     * Merge folder [fromId] into [toId]: move all sources (globally deduping by URL —
     * conflicts are dropped from the source), then delete the now-empty [fromId].
     * Both must be categories; levels may differ. Returns the count of moved sources.
     */
    suspend fun mergeCategory(fromId: String, toId: String): Int

    /** Delete a category; FK CASCADE sweeps its sources/items. */
    suspend fun deleteCategory(id: String)

    /**
     * Bulk-import categories + sources from an OPML 2.0 stream. Each top-level outline
     * becomes a category under a new topic; all leaf xmlUrl outlines become sources.
     * Idempotent across re-runs: sources use IGNORE on the (category_id, url) index.
     *
     * @return number of sources inserted (duplicates within a category are skipped).
     */
    suspend fun importFromOpml(stream: java.io.InputStream): Int

    /**
     * Exports the full sources tree (all topics → categories → sources) as an OPML 2.0
     * XML string. Each category becomes a folder outline; each source a leaf with xmlUrl.
     */
    suspend fun exportToOpml(): String

    /** Result of a write that can collide with the `(category_id, url)` unique index. */
    sealed interface Outcome {
        data object Ok : Outcome
        data class Conflict(val existingCategoryId: String) : Outcome
    }
}
