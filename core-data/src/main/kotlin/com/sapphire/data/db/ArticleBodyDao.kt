package com.sapphire.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArticleBodyDao {
    @Query("SELECT * FROM article_body WHERE item_id = :itemId LIMIT 1")
    suspend fun findByItem(itemId: String): ArticleBodyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ArticleBodyEntity)
}
