package com.sapphire.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sapphire.domain.model.SourceKind
import kotlinx.coroutines.flow.Flow

/**
 * Post-onboarding source/category CRUD (Sources drawer). Distinct from [OnboardingDao],
 * which owns the one-shot atomic bootstrap commit; this DAO owns all mutations after that.
 *
 * Reads are [Flow]s so the drawer recomposes live. Writes that touch `(category_id, url)`
 * use `IGNORE` so a unique-index collision is detectable by the caller via the returned
 * rowid (-1 = conflict) rather than throwing.
 */
@Dao
interface SourceDao {

    // ---- Reads: tree assembly ----

    @Query("SELECT * FROM category ORDER BY sort_order, name")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM source ORDER BY category_id, title")
    fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT id FROM topic ORDER BY created_at LIMIT 1")
    suspend fun firstTopicId(): String?

    /**
     * Reactive topic existence. True when at least one topic row exists — the gate for
     * "can the user create a folder here?" A topic survives even when every category has
     * been deleted, so this is a strictly weaker (more permissive) condition than
     * "the category tree is non-empty".
     */
    @Query("SELECT EXISTS(SELECT 1 FROM topic)")
    fun observeHasTopic(): Flow<Boolean>

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM category WHERE topic_id = :topicId")
    suspend fun maxSortOrder(topicId: String): Int

    @Query("SELECT topic_id FROM category WHERE id = :id")
    suspend fun topicIdOfCategory(id: String): String?

    @Query("SELECT url FROM source WHERE id = :id")
    suspend fun sourceUrl(id: String): String?

    // ---- Category writes ----

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategory(category: CategoryEntity)

    @Query("UPDATE category SET name = :name WHERE id = :id")
    suspend fun renameCategory(id: String, name: String)

    @Query("DELETE FROM category WHERE id = :id")
    suspend fun deleteCategory(id: String)

    /** Sources of [categoryId], so batch moves can resolve conflicts row-by-row. */
    @Query("SELECT * FROM source WHERE category_id = :categoryId")
    suspend fun sourcesOf(categoryId: String): List<SourceEntity>

    // ---- Source writes ----

    /**
     * IGNORE so a `(category_id, url)` unique-index conflict returns -1 instead of throwing.
     * The caller interprets -1 as a move/add collision (PRD §3.1 duplicate guard).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: SourceEntity): Long

    @Query("""
        UPDATE source SET
            title = :title,
            url = :url,
            kind = :kind,
            enabled = :enabled
        WHERE id = :id
    """)
    suspend fun updateSource(id: String, title: String, url: String, kind: SourceKind, enabled: Boolean)

    @Query("UPDATE source SET category_id = :toCategoryId WHERE id = :id")
    suspend fun moveSource(id: String, toCategoryId: String)

    @Query("DELETE FROM source WHERE id = :id")
    suspend fun deleteSource(id: String)

    @Query("SELECT category_id FROM source WHERE id = :id")
    suspend fun sourceCategoryId(id: String): String?

    @Query("SELECT EXISTS(SELECT 1 FROM source WHERE category_id = :categoryId AND url = :url AND id != :excludeId)")
    suspend fun urlTakenInCategory(categoryId: String, url: String, excludeId: String): Boolean

    @Query("SELECT COUNT(*) FROM source")
    suspend fun countSources(): Int

    /** Insert a topic; IGNORE so import is idempotent if the topic already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTopic(topic: TopicEntity)

    // ---- OPML import support: name-merge + global URL dedup ----

    /** Find an existing category by (topic, name, parent) for same-name folder merging. */
    @Query("SELECT id FROM category WHERE topic_id = :topicId AND name = :name AND parent_id IS :parentId LIMIT 1")
    suspend fun findCategoryIdByName(topicId: String, name: String, parentId: String?): String?

    /** Global URL existence check (across all categories) for import dedup. */
    @Query("SELECT EXISTS(SELECT 1 FROM source WHERE url = :url)")
    suspend fun urlExistsAnywhere(url: String): Boolean

    /** Next sort order within a parent (null parent = top-level under the topic). */
    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM category WHERE topic_id = :topicId AND parent_id IS :parentId")
    suspend fun maxSortOrderUnder(topicId: String, parentId: String?): Int

    // ---- Folder merge ----

    /**
     * Merge [fromId] into [toId]: move each source (dropping URL conflicts against the
     * target), then delete the now-empty [fromId]. Transactional — all or nothing.
     */
    @Transaction
    suspend fun mergeCategoryInto(fromId: String, toId: String): Int {
        val moving = sourcesOf(fromId)
        var moved = 0
        for (s in moving) {
            if (!urlTakenInCategory(toId, s.url, s.id)) {
                moveSource(s.id, toId)
                moved++
            } else {
                deleteSource(s.id)
            }
        }
        deleteCategory(fromId)
        return moved
    }
}
