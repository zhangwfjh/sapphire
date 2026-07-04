package com.sapphire.domain.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SimplifiedChineseDetector] — skip-translate guard for the reader.
 *
 * Contract under test:
 * - Skip ONLY when target is Simplified Chinese (zh / zh-CN / zh-Hans) AND the article
 *   is CJK-dominant with no Traditional-only glyphs.
 * - Never skip for Traditional Chinese, Japanese, Korean, English, or any non-zh target.
 * - Never skip Latin-dominant text even when a Chinese phrase appears.
 * - Any Traditional-only glyph (國, 語, 學, …) disqualifies the skip even in CJK-dominant text.
 */
class SimplifiedChineseDetectorTest {

    // ---------- target language gating ----------

    @Test
    fun `english target never skips`() {
        assertFalse(detector("Hello world article body.", "en"))
    }

    @Test
    fun `japanese target never skips even on kanji-heavy text`() {
        // Japanese shares kanji with Chinese; we must not skip it.
        assertFalse(detector("今日は良い天気ですね。", "ja"))
    }

    @Test
    fun `traditional chinese target never skips`() {
        assertFalse(detector("這是一篇繁體中文文章。", "zh-TW"))
        assertFalse(detector("這是一篇繁體中文文章。", "zh-Hant"))
    }

    @Test
    fun `unsupported zh variant never skips`() {
        assertFalse(detector("你好世界", "zh-XX"))
    }

    // ---------- simplified chinese detection ----------

    @Test
    fun `simplified chinese article skips for zh`() {
        assertTrue(detector("这是一篇简体中文新闻文章，讨论科技和经济发展。", "zh"))
    }

    @Test
    fun `simplified chinese article skips for zh-CN and zh-Hans`() {
        val text = "我们今天讨论人工智能在国内的发展。"
        assertTrue(detector(text, "zh-CN"))
        assertTrue(detector(text, "zh-Hans"))
    }

    @Test
    fun `underscore locale tag accepted`() {
        assertTrue(detector("简体中文内容", "zh_CN"))
        assertFalse(detector("繁體中文", "zh_TW")) // traditional → never skip
    }

    @Test
    fun `case-insensitive locale tag`() {
        assertTrue(detector("简体中文内容", "ZH"))
        assertTrue(detector("简体中文内容", "zh-cn"))
    }

    // ---------- traditional disqualification ----------

    @Test
    fun `traditional glyph in otherwise cjk text disqualifies skip`() {
        // 體, 國, 語 are Traditional-only glyphs.
        assertFalse(detector("這是一篇繁體中文文章，討論國家與語言。", "zh"))
    }

    @Test
    fun `single traditional glyph among simplified disqualifies`() {
        // Mostly simplified but one traditional glyph (國) → do not skip.
        assertFalse(detector("这是一个测试，讨论国家发展和国語。", "zh"))
    }

    // ---------- dominance & edge cases ----------

    @Test
    fun `latin-dominant text never skips`() {
        // A Chinese phrase inside an English article — translate it.
        assertFalse(detector("Breaking news: 你好 world today reported.", "zh"))
    }

    @Test
    fun `empty text never skips`() {
        assertFalse(detector("", "zh"))
    }

    @Test
    fun `no-cjk text never skips`() {
        assertFalse(detector("Just a plain English headline.", "zh"))
    }

    @Test
    fun `cjk equal to latin does not skip (strict dominance)`() {
        // 3 CJK + 3 latin → not strictly dominant → translate.
        assertFalse(detector("abc你好de", "zh"))
    }

    @Test
    fun `mixed simplified across title brief article skips`() {
        val title = "人工智能新进展"
        val brief = "本周科技领域有多项突破。"
        val article = "各大公司发布了新的模型，推动了行业的发展。"
        assertTrue(detector("$title $brief $article", "zh"))
    }

    private fun detector(text: String, targetLanguage: String): Boolean =
        SimplifiedChineseDetector.shouldSkipTranslate(text, targetLanguage)
}
