package com.sapphire.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * S03 reader-op cache DAO (architecture §3 `LlmCache`). The PK `cache_key` is the
 * [com.sapphire.domain.util.LlmCacheKey] SHA-256, computed in the domain layer; here we
 * only store/lookup. [insert] uses REPLACE so a re-computed payload for the same key
 * (e.g. after a model-version bump) overwrites cleanly.
 */
@Dao
interface LlmCacheDao {

    @Query("SELECT payload_json FROM llm_cache WHERE cache_key = :key")
    suspend fun payload(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: LlmCacheEntity)

    /** Settings §3.3: clear every llm_cache row. Returns rows deleted. */
    @Query("DELETE FROM llm_cache")
    suspend fun deleteAll(): Int
}
