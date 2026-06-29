package com.sapphire.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Readability side-cache DAO (PRD §3.4 reader; architecture §3). PK = the FeedItem hash,
 * 1:1 with its parent item. `upsert` uses REPLACE so a re-extract overwrites the prior
 * body cleanly. Rows cascade-delete with their FeedItem, so retention purge sweeps them.
 */
@Dao
interface ArticleBodyDao {
    @Query("SELECT * FROM article_body WHERE item_id = :itemId")
    suspend fun findByItem(itemId: String): ArticleBodyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArticleBodyEntity)

    /** Settings §3.3: clear every article_body row. Returns rows deleted. */
    @Query("DELETE FROM article_body")
    suspend fun deleteAll(): Int
}
