package com.sapphire.data.agent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sapphire.data.db.SapphireDatabase
import com.sapphire.domain.agent.AgentJobInput
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentRecency
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.model.AgentStyle
import com.sapphire.domain.model.OutputLanguage
import com.sapphire.domain.model.SearchTool
import com.sapphire.domain.util.IdGenerator
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
 * RoomAgentRepository (design: 2026-07-27-agents-foundation-ui.md, Task 4).
 * Covers: create→observe, update preserves created_at/enabled, setEnabled, delete,
 * recordRun ordering, and the seeded "created" run. Uses a deterministic IdGenerator.
 */
@RunWith(RobolectricTestRunner::class)
class RoomAgentRepositoryTest {

    private lateinit var db: SapphireDatabase
    private lateinit var repo: RoomAgentRepository
    private val ids = DeterministicIds()

    private class DeterministicIds : IdGenerator {
        var n = 0
        override fun uuid(): String = "id-${n++}"
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SapphireDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = RoomAgentRepository(db.agentJobDao(), db.agentRunDao(), ids)
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `create persists and observes the job`() = runTest {
        val id = repo.create(input("Scanner"))
        val jobs = repo.observeJobs().first()
        assertEquals(1, jobs.size)
        assertEquals(id, jobs[0].id)
        assertEquals("Scanner", jobs[0].name)
        assertTrue(jobs[0].enabled)
    }

    @Test
    fun `create seeds a created-run in the history`() = runTest {
        val id = repo.create(input("Scanner"))
        val runs = repo.observeRuns(id).first()
        assertEquals(1, runs.size)
        assertEquals(AgentRunStatus.EMPTY, runs[0].status)
        assertTrue("seeded message", runs[0].message?.contains("created") == true)
    }

    @Test
    fun `update changes fields but preserves created_at and enabled`() = runTest {
        val id = repo.create(input("Original"))
        val createdAtBefore = repo.observeJob(id).first()!!.createdAt
        repo.update(id, input("Renamed", style = AgentStyle.ACADEMIC))
        val job = repo.observeJob(id).first()!!
        assertEquals("Renamed", job.name)
        assertEquals(AgentStyle.ACADEMIC, job.style)
        assertEquals("created_at must be preserved", createdAtBefore, job.createdAt)
        assertTrue("enabled must be preserved", job.enabled)
    }

    @Test
    fun `setEnabled toggles and is observable`() = runTest {
        val id = repo.create(input("Scanner"))
        assertTrue(repo.observeJob(id).first()!!.enabled)
        repo.setEnabled(id, false)
        assertFalse(repo.observeJob(id).first()!!.enabled)
    }

    @Test
    fun `delete removes job and cascades runs`() = runTest {
        val id = repo.create(input("Scanner"))
        repo.recordRun(id, AgentRunStatus.OK, 3, 1200, "ok")
        repo.delete(id)
        assertNull(repo.observeJob(id).first())
        assertEquals(0, repo.observeRuns(id).first().size)
    }

    @Test
    fun `recordRun appends to history ordered newest first`() = runTest {
        val id = repo.create(input("Scanner"))
        Thread.sleep(2) // ensure ran_at advances past the seeded run
        repo.recordRun(id, AgentRunStatus.OK, 2, 800, "first")
        Thread.sleep(2)
        repo.recordRun(id, AgentRunStatus.FAILED, 0, 100, "boom")
        val runs = repo.observeRuns(id).first()
        assertEquals(3, runs.size)
        assertEquals("boom", runs[0].message) // newest first
        assertEquals("first", runs[1].message)
        assertTrue(runs[2].message?.contains("created") == true) // seeded
    }

    private fun input(
        name: String,
        style: AgentStyle = AgentStyle.BRIEF,
    ) = AgentJobInput(
        name = name,
        directive = "directive for $name",
        searchTool = SearchTool.TAVILY,
        frequency = AgentFrequency.DAILY,
        triggerTime = "07:00",
        recency = AgentRecency.WEEK,
        outputLanguage = OutputLanguage.EN,
        style = style,
    )
}
