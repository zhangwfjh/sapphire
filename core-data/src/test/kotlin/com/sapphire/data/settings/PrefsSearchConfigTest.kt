package com.sapphire.data.settings

import com.sapphire.domain.explore.SearchRegion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PrefsSearchConfigTest {

    private lateinit var config: PrefsSearchConfig

    @Before
    fun setUp() {
        // Unique prefs name per test run via @Before so re-runs are clean.
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("test_search", 0).edit().clear().commit()
        config = PrefsSearchConfig(ctx, prefsName = "test_search")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_search", 0).edit().clear().commit()
    }

    @Test
    fun `default region is AUTO`() {
        assertEquals(SearchRegion.AUTO, config.region())
    }

    @Test
    fun `default tavily key is empty`() = runTest {
        assertEquals("", config.observeTavilyKey().first())
    }

    @Test
    fun `setRegion persists and observeRegion emits`() = runTest {
        config.setRegion(SearchRegion.CHINA)
        assertEquals(SearchRegion.CHINA, config.region())
        assertEquals(SearchRegion.CHINA, config.observeRegion().first())
    }

    @Test
    fun `setTavilyKey persists and observeTavilyKey emits`() = runTest {
        config.setTavilyKey("tvly-abc")
        assertEquals("tvly-abc", config.observeTavilyKey().first())
    }

    @Test
    fun `setRegion to WEST overrides default`() = runTest {
        config.setRegion(SearchRegion.WEST)
        config.setRegion(SearchRegion.CHINA)
        assertEquals(SearchRegion.CHINA, config.observeRegion().first())
    }
}
