package com.sapphire.domain.agent

import com.sapphire.domain.browser.BrowserClient
import com.sapphire.domain.browser.RenderRequest
import com.sapphire.domain.browser.RenderResult
import com.sapphire.domain.explore.WebSearchClient
import com.sapphire.domain.explore.WebSearchHit
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmError
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.llm.ToolCall
import com.sapphire.domain.llm.ToolDefinition
import com.sapphire.domain.llm.ToolMessage
import com.sapphire.domain.llm.ToolTurn
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRun
import com.sapphire.domain.model.AgentRunStatus
import com.sapphire.domain.reader.ArticleExtractor
import com.sapphire.domain.reader.ExtractionOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentRunService — the run module around [AgentLoopService]. Exercises the mode ×
 * outcome matrix (FILE/TEST/DRY × items/empty/error) plus the exclusion wiring, with a
 * REAL loop over hand-rolled fakes (house pattern). These defend the contracts the
 * worker and both ViewModels rely on: who files, who records history, and the single
 * owner of status/token/message derivation.
 */
class AgentRunServiceTest {

    private fun service(llm: FakeLlm, repo: FakeAgentRepository): AgentRunService {
        val loop = AgentLoopService(llm, searchOk(), extractorFail(), browserNotConfigured())
        return AgentRunService(loop, repo, AgentRerankService(llm))
    }

    // ---- FILE ----

    @Test
    fun `FILE files items and records OK`() = runTest {
        val repo = FakeAgentRepository(filedResult = 2)
        val svc = service(llmWith(finalize(twoItems())), repo)

        val outcome = svc.run(job(), AgentRunService.RunMode.FILE)

        assertEquals(AgentRunStatus.OK, outcome.status)
        assertEquals(2, outcome.itemsFiled)
        assertEquals(2400, outcome.tokensUsed) // 2 × 1200 — single owner of the estimate
        assertEquals("2 items filed", outcome.message)
        assertNull(outcome.error)
        assertEquals(1, repo.filedCalls.size)
        assertEquals(jobId, repo.filedCalls.single().first)
        assertEquals(1, repo.recordedRuns.size)
        repo.recordedRuns.single().let {
            assertEquals(jobId, it.jobId)
            assertEquals(AgentRunStatus.OK, it.status)
            assertEquals(2, it.itemsFiled)
            assertEquals(2400, it.tokensUsed)
            assertEquals("2 items filed", it.message)
        }
    }

    @Test
    fun `FILE with partial dedup records OK with dedup message`() = runTest {
        val repo = FakeAgentRepository(filedResult = 2)
        val svc = service(llmWith(finalize(threeItems())), repo)

        val outcome = svc.run(job(maxItems = 3), AgentRunService.RunMode.FILE)

        assertEquals(AgentRunStatus.OK, outcome.status)
        assertEquals("2/3 filed (some deduped)", outcome.message)
        assertEquals(2, outcome.itemsFiled)
    }

    @Test
    fun `FILE with everything deduped records EMPTY`() = runTest {
        val repo = FakeAgentRepository(filedResult = 0)
        val svc = service(llmWith(finalize(twoItems())), repo)

        val outcome = svc.run(job(), AgentRunService.RunMode.FILE)

        assertEquals(AgentRunStatus.EMPTY, outcome.status)
        assertEquals("0/2 filed (some deduped)", outcome.message)
        assertEquals(AgentRunStatus.EMPTY, repo.recordedRuns.single().status)
    }

    @Test
    fun `empty result records EMPTY and never files`() = runTest {
        val repo = FakeAgentRepository(filedResult = 5)
        val svc = service(llmWith(finalizeEmpty()), repo)

        val outcome = svc.run(job(), AgentRunService.RunMode.FILE)

        assertEquals(AgentRunStatus.EMPTY, outcome.status)
        assertEquals("No items worth filing", outcome.message)
        assertTrue(repo.filedCalls.isEmpty())
        assertEquals(1, repo.recordedRuns.size)
    }

    // ---- TEST ----

    @Test
    fun `TEST records history but never files`() = runTest {
        val repo = FakeAgentRepository(filedResult = 2)
        val svc = service(llmWith(finalize(twoItems())), repo)

        val outcome = svc.run(job(), AgentRunService.RunMode.TEST)

        assertEquals(AgentRunStatus.OK, outcome.status)
        assertTrue(repo.filedCalls.isEmpty())
        assertEquals(1, repo.recordedRuns.size)
        repo.recordedRuns.single().let {
            assertEquals(AgentRunStatus.OK, it.status)
            assertEquals(2, it.itemsFiled) // test runs count produced items
            assertEquals(2400, it.tokensUsed)
            assertTrue(it.message!!.startsWith("Test run: 2 items in "))
        }
    }

    // ---- DRY ----

    @Test
    fun `DRY files nothing and records nothing`() = runTest {
        val repo = FakeAgentRepository(filedResult = 2)
        val svc = service(llmWith(finalize(twoItems())), repo)

        val outcome = svc.run(job(), AgentRunService.RunMode.DRY)

        assertEquals(AgentRunStatus.OK, outcome.status)
        assertNull(outcome.message)
        assertEquals(2, outcome.items.size)
        assertEquals(0, outcome.itemsFiled)
        assertTrue(repo.filedCalls.isEmpty())
        assertTrue(repo.recordedRuns.isEmpty())
    }

    // ---- failure paths ----

    @Test
    fun `LLM error records FAILED and carries the error`() = runTest {
        val repo = FakeAgentRepository()
        val svc = service(FakeLlm(error = LlmError.Timeout), repo)

        val outcome = svc.run(job(), AgentRunService.RunMode.FILE)

        assertEquals(AgentRunStatus.FAILED, outcome.status)
        assertEquals(LlmError.Timeout, outcome.error)
        assertEquals(LlmError.Timeout.userMessage(), outcome.message)
        assertEquals(1, repo.recordedRuns.size)
        assertEquals(AgentRunStatus.FAILED, repo.recordedRuns.single().status)
    }

    @Test
    fun `DRY on LLM error records nothing`() = runTest {
        val repo = FakeAgentRepository()
        val svc = service(FakeLlm(error = LlmError.NotConfigured), repo)

        val outcome = svc.run(job(), AgentRunService.RunMode.DRY)

        assertEquals(AgentRunStatus.FAILED, outcome.status)
        assertTrue(repo.recordedRuns.isEmpty())
    }

    @Test
    fun `unexpected exception records FAILED instead of propagating`() = runTest {
        val repo = FakeAgentRepository()
        repo.recentlyFiledUrlsError = IllegalStateException("db exploded")
        val svc = service(llmWith(finalize(twoItems())), repo)

        val outcome = svc.run(job(), AgentRunService.RunMode.TEST)

        assertEquals(AgentRunStatus.FAILED, outcome.status)
        assertEquals("db exploded", outcome.message)
        assertNull(outcome.error)
        assertEquals(AgentRunStatus.FAILED, repo.recordedRuns.single().status)
    }

    // ---- exclusion wiring ----

    @Test
    fun `recently filed urls flow into the loop prompt`() = runTest {
        val repo = FakeAgentRepository(filedResult = 0)
        repo.recentUrls = listOf("https://old.example/a", "https://old.example/b")
        val llm = llmWith(finalize(twoItems()))
        val svc = service(llm, repo)

        svc.run(job(), AgentRunService.RunMode.FILE)

        assertTrue(
            "exclusion URLs must reach the loop's user prompt",
            llm.capturedUserPrompt!!.contains("https://old.example/a"),
        )
    }

    // ---- fixtures ----

    private val jobId = "j1"

    private fun job(maxItems: Int = 2) = AgentJob(
        id = jobId, name = "TestAgent", goal = "summarize the top news about AI",
        task = "", format = "", rules = "",
        maxItems = maxItems, frequency = AgentFrequency.DAILY, triggerTime = "07:00",
        enabled = true, nextRunIntentEpochMs = null, createdAt = 0L,
    )

    private fun item(title: String) = AgentSynthesisItem(
        title = title, summary = "s", body = "body of $title", url = "https://u/$title",
    )

    private fun twoItems() = listOf(item("A"), item("B"))
    private fun threeItems() = listOf(item("A"), item("B"), item("C"))

    private fun llmWith(call: ToolCall) = FakeLlm(
        listOf(ToolTurn(content = null, toolCalls = listOf(call))),
    )

    /** Scripted finalize tool call carrying [items] as the loop's structured result. */
    private fun finalize(items: List<AgentSynthesisItem>): ToolCall {
        val json = items.joinToString(",") { i ->
            """{"title":"${i.title}","summary":"${i.summary}","body":"${i.body}","url":"${i.url}"}"""
        }
        return ToolCall("f1", "finalize", """{"items":[$json]}""")
    }

    private fun finalizeEmpty() = ToolCall("f1", "finalize", """{"items":[]}""")

    // ---- fakes (house pattern) ----

    /** Minimal scripted LLM: one turn; captures the user prompt for exclusion asserts. */
    private class FakeLlm(
        private val turns: List<ToolTurn> = emptyList(),
        private val error: LlmError? = null,
    ) : LlmClient {
        var capturedUserPrompt: String? = null

        override suspend fun <T> completeStructured(
            tier: LlmTier, systemPrompt: String, userPrompt: String, outputSerializer: KSerializer<T>,
        ): LlmOutcome<T> = throw NotImplementedError("not used by the loop")

        override fun streamText(tier: LlmTier, systemPrompt: String, userPrompt: String): Flow<LlmOutcome<String>> =
            flow { throw NotImplementedError("not used by the loop") }

        override suspend fun completeWithTools(
            tier: LlmTier, systemPrompt: String, conversation: List<ToolMessage>, tools: List<ToolDefinition>,
        ): LlmOutcome<ToolTurn> {
            capturedUserPrompt = conversation.filterIsInstance<ToolMessage.User>().firstOrNull()?.content
            return if (error != null) LlmOutcome.Err(error) else LlmOutcome.Ok(turns.first())
        }
    }

    /** In-memory AgentRepository recording every write the service performs. */
    private class FakeAgentRepository(
        var filedResult: Int = 0,
    ) : AgentRepository {
        data class RecordedRun(
            val jobId: String, val status: AgentRunStatus,
            val itemsFiled: Int, val tokensUsed: Int, val message: String?,
        )

        val recordedRuns = mutableListOf<RecordedRun>()
        val filedCalls = mutableListOf<Pair<String, List<AgentSynthesisItem>>>()
        var recentUrls: List<String> = emptyList()
        var recentlyFiledUrlsError: Exception? = null

        override fun observeJobs(): Flow<List<AgentJob>> = emptyFlow()
        override fun observeJob(id: String): Flow<AgentJob?> = emptyFlow()
        override fun observeRuns(jobId: String): Flow<List<AgentRun>> = emptyFlow()
        override suspend fun create(input: AgentJobInput): String = "new"
        override suspend fun update(id: String, input: AgentJobInput) {}
        override suspend fun setEnabled(id: String, enabled: Boolean) {}
        override suspend fun delete(id: String) {}
        override suspend fun recordRun(
            jobId: String, status: AgentRunStatus, itemsFiled: Int, tokensUsed: Int, message: String?,
        ) {
            recordedRuns += RecordedRun(jobId, status, itemsFiled, tokensUsed, message)
        }

        override suspend fun fileAgentItems(jobId: String, items: List<AgentSynthesisItem>, agentName: String): Int {
            filedCalls += jobId to items
            return filedResult
        }

        override suspend fun recentlyFiledUrls(jobId: String, limit: Int): List<String> {
            recentlyFiledUrlsError?.let { throw it }
            return recentUrls
        }
    }

    private fun searchOk() = object : WebSearchClient {
        override suspend fun search(query: String) = listOf(WebSearchHit("Title", "https://r", "snippet"))
    }

    private fun extractorFail() = object : ArticleExtractor {
        override suspend fun extract(url: String) = ExtractionOutcome.Err.Unreachable
    }

    private fun browserNotConfigured() = object : BrowserClient {
        override suspend fun render(request: RenderRequest) = RenderResult.notConfigured()
    }
}
