package com.sapphire.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sapphire.domain.settings.TranslateViewMode
import com.sapphire.domain.settings.UiPrefsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedPrefsUiPrefsStoreTest {
    private lateinit var store: SharedPrefsUiPrefsStore

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        store = SharedPrefsUiPrefsStore(ctx, "test_ui_prefs_${System.nanoTime()}")
    }

    @Test
    fun density_default_isDense() = runTest {
        assertEquals(UiPrefsStore.FeedDensity.DEFAULT, store.observeDensity().first())
    }

    @Test
    fun density_roundTrip() = runTest {
        store.setDensity(UiPrefsStore.FeedDensity(false))
        assertEquals(false, store.observeDensity().first().isDense)
    }

    @Test
    fun translateView_default_bilingual() = runTest {
        assertEquals(TranslateViewMode.BILINGUAL, store.observeTranslateView().first())
    }

    @Test
    fun translateView_roundTrip() = runTest {
        store.setTranslateView(TranslateViewMode.TRANSLATION)
        assertEquals(TranslateViewMode.TRANSLATION, store.observeTranslateView().first())
    }

    @Test
    fun translateView_persistsAcrossInstances() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefsName = "test_ui_persist_${System.nanoTime()}"
        val s1 = SharedPrefsUiPrefsStore(ctx, prefsName)
        s1.setTranslateView(TranslateViewMode.ORIGIN)
        val s2 = SharedPrefsUiPrefsStore(ctx, prefsName)
        assertEquals(TranslateViewMode.ORIGIN, s2.observeTranslateView().first())
    }
}
