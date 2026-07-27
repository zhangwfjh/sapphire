package com.sapphire.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentRecency
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.model.AgentStyle
import com.sapphire.domain.model.OutputLanguage
import com.sapphire.domain.model.SearchTool
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Agent job/run DAOs (design: 2026-07-27-agents-foundation-ui.md, Task 3).
 * Covers: insert+observe ordering, updateFields preserving created_at/enabled,
 * setEnabled, delete, run observe ordering, cascade-delete on job delete.
 */
@RunWith(RobolectricTestRunner::class)
class AgentDaoTest {

    private lateinit var db: SapphireDatabase
    private lateinit var jobs: AgentJobDao
    private lateinit var runs: AgentRunDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SapphireDatabase::class.java,
        ).allowMainThreadQueries().build()
        jobs = db.agentJobDao()
        runs = db.agentRunDao()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `upsert then observeAll returns the job`() = runTest {
        jobs.upsert(job("id1", "Scanner", createdAt = 100))
        val all = jobs.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("Scanner", all[0].name)
    }

    @Test
    fun `observeAll orders by created_at desc`() = runTest {
        jobs.upsert(job("old", "Old", createdAt = 100))
        jobs.upsert(job("new", "New", createdAt = 300))
        jobs.upsert(job("mid", "Mid", createdAt = 200))
        assertEquals(listOf("New", "Mid", "Old"), jobs.observeAll().first().map { it.name })
    }

    @Test
    fun `updateFields changes editable fields but preserves created_at and enabled`() = runTest {
        jobs.upsert(job("id1", "Original", enabled = false, createdAt = 1000))
        jobs.updateFields(
            id = "id1", name = "Renamed", directive = "new directive",
            searchTool = SearchTool.TAVILY, frequency = AgentFrequency.WEEKLY,
            triggerTime = "09:30", recency = AgentRecency.MONTH,
            outputLanguage = OutputLanguage.ZH, style = AgentStyle.ACADEMIC,
        )
        val row = jobs.getById("id1")!!
        assertEquals("Renamed", row.name)
        assertEquals("new directive", row.directive)
        assertEquals(SearchTool.TAVILY, row.searchTool)
        assertEquals(AgentFrequency.WEEKLY, row.frequency)
        assertEquals("09:30", row.triggerTime)
        assertEquals(AgentRecency.MONTH, row.recency)
        assertEquals(OutputLanguage.ZH, row.outputLanguage)
        assertEquals(AgentStyle.ACADEMIC, row.style)
        // preserved:
        assertEquals(1000L, row.createdAt)
        assertFalse("enabled must be preserved across updateFields", row.enabled)
        assertNull("nextRunIntentEpochMs must be preserved", row.nextRunIntentEpochMs)
    }

    @Test
    fun `setEnabled flips the flag`() = runTest {
        jobs.upsert(job("id1", "Scanner", enabled = true))
        jobs.setEnabled("id1", false)
        assertFalse(jobs.getById("id1")!!.enabled)
        jobs.setEnabled("id1", true)
        assertTrue(jobs.getById("id1")!!.enabled)
    }

    @Test
    fun `delete removes the job`() = runTest {
        jobs.upsert(job("id1", "Scanner"))
        jobs.delete("id1")
        assertNull(jobs.getById("id1"))
    }

    @Test
    fun `observeForJob returns runs ordered by ran_at desc`() = runTest {
        jobs.upsert(job("id1", "Scanner"))
        runs.insert(run("r1", "id1", ranAt = 100))
        runs.insert(run("r3", "id1", ranAt = 300))
        runs.insert(run("r2", "id1", ranAt = 200))
        assertEquals(listOf(300L, 200L, 100L), runs.observeForJob("id1").first().map { it.ranAt })
    }

    @Test
    fun `deleting a job cascades to its runs`() = runTest {
        jobs.upsert(job("id1", "Scanner"))
        runs.insert(run("r1", "id1", ranAt = 100))
        runs.insert(run("r2", "id1", ranAt = 200))
        jobs.delete("id1")
        assertEquals(0, runs.observeForJob("id1").first().size)
    }

    private fun job(
        id: String,
        name: String,
        enabled: Boolean = true,
        createdAt: Long = 0L,
    ) = AgentJobEntity(
        id = id, name = name, directive = "do something",
        searchTool = SearchTool.TAVILY, frequency = AgentFrequency.DAILY,
        triggerTime = "07:00", recency = AgentRecency.WEEK,
        outputLanguage = OutputLanguage.EN, style = AgentStyle.BRIEF,
        enabled = enabled, nextRunIntentEpochMs = null, createdAt = createdAt,
    )

    private fun run(id: String, jobId: String, ranAt: Long) = AgentRunEntity(
        id = id, jobId = jobId, status = AgentRunStatus.OK,
        itemsFiled = 1, tokensUsed = 100, ranAt = ranAt, message = null,
    )
}
