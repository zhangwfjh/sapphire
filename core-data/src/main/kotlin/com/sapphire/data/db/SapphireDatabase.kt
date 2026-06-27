package com.sapphire.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Sapphire local store. Schema version starts at 1; bumped as entities change.
 * Uses destructive migration (see DataModule), so schema export is disabled.
 */
@Database(
    entities = [
        TopicEntity::class,
        CategoryEntity::class,
        KeywordEntity::class,
        SourceEntity::class,
        FeedItemEntity::class,
        ReadLogEntity::class,
        LlmCacheEntity::class,
        SavedItemEntity::class,
        DiscoveredFeedEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
@TypeConverters(EnumTypeConverter::class)
abstract class SapphireDatabase : RoomDatabase() {
    abstract fun onboardingDao(): OnboardingDao
    abstract fun feedDao(): FeedDao
    abstract fun sourceDao(): SourceDao
    abstract fun llmCacheDao(): LlmCacheDao
    abstract fun savedItemDao(): SavedItemDao
    abstract fun discoveredFeedDao(): DiscoveredFeedDao
}
