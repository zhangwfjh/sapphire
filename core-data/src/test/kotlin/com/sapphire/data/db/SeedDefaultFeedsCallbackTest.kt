package com.sapphire.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sapphire.domain.model.SourceKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [SeedDefaultFeedsCallback] actually seeds sources on first DB creation.
 * This is the critical link between "DB created" and "refresh has something to fetch" —
 * if the seed is broken or skipped, refreshAll() returns (0, []) with no feedback.
 */
@RunWith(RobolectricTestRunner::class)
class SeedDefaultFeedsCallbackTest {

    private lateinit var db: SapphireDatabase
    private lateinit var dao: OnboardingDao
    private lateinit var dbFileName: String

    @Before
    fun setUp() {
        dbFileName = "seed-test-${System.nanoTime()}.db"
        db = newDb()
        dao = db.onboardingDao()
    }

    private fun newDb(): SapphireDatabase = Room.databaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SapphireDatabase::class.java,
        dbFileName,
    )
        .addCallback(SeedDefaultFeedsCallback())
        .allowMainThreadQueries()
        .build()

    @After fun tearDown() { db.close() }

    @Test
    fun seeds_rss_sources_on_create() = runTest {
        val sources = dao.allSources()
        assertTrue("seed must insert sources, got ${sources.size}", sources.isNotEmpty())
        // All seeded sources must be fetchable RSS/ATOM (have a registered Fetcher).
        assertTrue(sources.all { it.kind == SourceKind.RSS || it.kind == SourceKind.ATOM })
        // BBC must be HTTPS now (cleartext fix).
        val bbc = sources.first { it.title == "BBC News" }
        assertTrue("BBC must be HTTPS: ${bbc.url}", bbc.url.startsWith("https://"))
    }

    /**
     * Reproduces the Auto-Backup-restore failure: a DB that exists but has an empty source
     * table (as happens when Android restores a pre-seed DB) must get re-seeded on the next
     * open. Without this, onCreate never fires (DB already exists) and the timeline stays
     * empty with no feedback.
     */
    @Test
    fun reseeds_empty_source_table_on_open() = runTest {
        // Wipe sources to simulate a restored-but-empty DB, then reopen.
        db.openHelper.writableDatabase.execSQL("DELETE FROM source")
        assertEquals(0, dao.countSources())
        db.close()

        db = newDb()
        dao = db.onboardingDao()

        // onOpen should have re-seeded.
        val sources = dao.allSources()
        assertTrue("onOpen must reseed empty source table, got ${sources.size}", sources.isNotEmpty())
    }

}
