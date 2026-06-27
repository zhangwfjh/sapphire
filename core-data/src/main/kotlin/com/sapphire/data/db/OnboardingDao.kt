package com.sapphire.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sapphire.domain.model.HealthState

@Dao
interface OnboardingDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTopic(topic: TopicEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKeywords(keywords: List<KeywordEntity>)

    /** IGNORE so re-onboarding the same topic is idempotent — no crash, no duplicate row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSources(sources: List<SourceEntity>)

    @Query("SELECT COUNT(*) FROM source")
    suspend fun countSources(): Int

    @Query("SELECT COUNT(*) FROM source WHERE category_id = :categoryId AND url = :url")
    suspend fun countByCategoryAndUrl(categoryId: String, url: String): Int

    // ---- S02 feed-ingest support ----

    /** Enabled sources the [FeedRefreshService] pulls. */
    @Query("SELECT * FROM source WHERE enabled = 1")
    suspend fun enabledSources(): List<SourceEntity>

    /** Stamp a source's last fetch + health (S06 surfaces FAILED/DEGRADED in UI). */
    @Query("UPDATE source SET health_state = :health, last_fetched_at = :now, last_error_at = :errorAt WHERE id = :id")
    suspend fun updateFetchState(id: String, health: HealthState, now: Long, errorAt: Long?)

    /**
     * Atomic commit of a full onboarding review. All-or-nothing: if any insert throws,
     * Room rolls back the transaction — the partial topic never lands.
     */
    @Transaction
    suspend fun commitOnboarding(
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
