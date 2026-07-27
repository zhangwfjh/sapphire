package com.sapphire.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sapphire.domain.model.HealthState

@Dao
interface SeedDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTopic(topic: TopicEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKeywords(keywords: List<KeywordEntity>)

    /** IGNORE so re-importing the same source is idempotent — no crash, no duplicate row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSources(sources: List<SourceEntity>)

    /** Idempotent variants for the agent source seeder — topic/category already exist on 2nd+ agent. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTopicIgnore(topic: TopicEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategoriesIgnore(categories: List<CategoryEntity>)

    @Query("SELECT COUNT(*) FROM source")
    suspend fun countSources(): Int

    @Query("SELECT COUNT(*) FROM source WHERE category_id = :categoryId AND url = :url")
    suspend fun countByCategoryAndUrl(categoryId: String, url: String): Int

    // ---- feed-ingest support ----

    /** All sources the [FeedRefreshService] pulls. */
    @Query("SELECT * FROM source")
    suspend fun allSources(): List<SourceEntity>

    /** Stamp a source's last fetch + health (surfaces FAILED/DEGRADED in UI). */
    @Query("UPDATE source SET health_state = :health, last_fetched_at = :now, last_error_at = :errorAt WHERE id = :id")
    suspend fun updateFetchState(id: String, health: HealthState, now: Long, errorAt: Long?)

    /**
     * Atomic commit of a full topic tree (topic + categories + keywords + sources).
     * All-or-nothing: if any insert throws, Room rolls back the transaction — the partial
     * topic never lands.
     */
    @Transaction
    suspend fun commitSeed(
        topic: TopicEntity,
        categories: List<CategoryEntity>,
        keywords: List<KeywordEntity>,
        sources: List<SourceEntity>,
    ) {
        insertTopic(topic)
        insertCategories(categories)
        insertKeywords(keywords)
        insertSources(sources)
    }
}
