package com.sapphire.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Seeds a baseline of sources so the timeline has content out of the box (the feed is the
 * home screen): a small hand-picked set (Technology / World) under the seed topic.
 *
 * The user can still run the LLM onboarding ("Curate my feed") or OPML import/export for
 * more control.
 * Single-level: each seeded category is a flat folder with sources attached directly.
 *
 * Rows use fixed string ids so seeding is idempotent (INSERT OR IGNORE): running it on a
 * DB that already has the seed rows (or user rows) is a no-op.
 *
 * Seeding fires from BOTH [onCreate] (first DB creation) and [onOpen] (every open, guarded
 * by an empty-source check). The onOpen path is essential because Android Auto Backup
 * (`android:allowBackup="true"`) restores the DB file across reinstalls; a restored DB is
 * already at the current schema version so [onCreate] never runs, and an empty restore (or
 * one from a pre-seed version) leaves the source table empty with no feedback. The onOpen
 * guard ensures a seeded baseline exists regardless of restore state.
 */
class SeedDefaultFeedsCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        seed(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        // Reseed only if the source table is empty — a populated DB (user-curated or
        // already seeded) is left untouched. INSERT OR IGNORE makes this idempotent.
        val cursor = db.query("SELECT EXISTS(SELECT 1 FROM source LIMIT 1)")
        val hasSource = cursor.use { it.moveToFirst() && it.getInt(0) == 1 }
        if (!hasSource) seed(db)
    }

    private fun seed(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()

        db.execSQL(
            "INSERT OR IGNORE INTO topic (id, phrase, created_at) VALUES (?, ?, ?)",
            arrayOf<Any?>(SEED_TOPIC_ID, "Curated", now),
        )

        // Technology folder
        seedCategory(db, "seed-cat-tech", SEED_TOPIC_ID, "Technology", 0, now)
        seedSource(db, "seed-src-hn", "seed-cat-tech", SEED_TOPIC_ID, "RSS",
            "https://hnrss.org/frontpage", "Hacker News", now)
        seedSource(db, "seed-src-verge", "seed-cat-tech", SEED_TOPIC_ID, "RSS",
            "https://www.theverge.com/rss/index.xml", "The Verge", now)
        seedSource(db, "seed-src-ars", "seed-cat-tech", SEED_TOPIC_ID, "RSS",
            "https://feeds.arstechnica.com/arstechnica/index", "Ars Technica", now)
        seedSource(db, "seed-src-mit", "seed-cat-tech", SEED_TOPIC_ID, "RSS",
            "https://www.technologyreview.com/feed/", "MIT Tech Review", now)

        // World folder
        seedCategory(db, "seed-cat-world", SEED_TOPIC_ID, "World", 1, now)
        seedSource(db, "seed-src-bbc", "seed-cat-world", SEED_TOPIC_ID, "RSS",
            "https://feeds.bbci.co.uk/news/rss.xml", "BBC News", now)

        // (PKB starter catalog removed — hand-picked seed only.)
    }

    private fun seedCategory(
        db: SupportSQLiteDatabase,
        id: String,
        topicId: String,
        name: String,
        sortOrder: Int,
        now: Long,
    ) {
        db.execSQL(
            """INSERT OR IGNORE INTO category
               (id, topic_id, level, parent_id, name, sort_order)
               VALUES (?, ?, 1, NULL, ?, ?)""",
            arrayOf<Any?>(id, topicId, name, sortOrder),
        )
    }

    @Suppress("SameParameterValue")
    private fun seedSource(
        db: SupportSQLiteDatabase,
        id: String,
        categoryId: String,
        topicId: String,
        kind: String,
        url: String,
        title: String,
        now: Long,
    ) {
        db.execSQL(
            """INSERT OR IGNORE INTO source
               (id, category_id, topic_id, kind, url, title, config_json,
                health_state, last_fetched_at, last_error_at)
               VALUES (?, ?, ?, ?, ?, ?, NULL, 'OK', NULL, NULL)""",
            arrayOf<Any?>(id, categoryId, topicId, kind, url, title),
        )
    }

    private companion object {
        const val SEED_TOPIC_ID = "seed-topic-curated"
    }
}
