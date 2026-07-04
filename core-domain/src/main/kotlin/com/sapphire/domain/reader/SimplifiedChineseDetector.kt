package com.sapphire.domain.reader

/**
 * Decides whether the reader should *skip* the Tier-2 translate op because the article
 * is already in the user's target language.
 *
 * Scope is deliberately narrow: only **Simplified Chinese** targets (`zh`, `zh-CN`,
 * `zh-Hans`) are ever skipped. Traditional Chinese, Japanese, Korean, and all Latin
 * scripts still translate — per product decision "skip only if Simplified Chinese, not
 * even Traditional".
 *
 * The check is a deterministic Unicode script/glyph scan — no LLM call, no locale
 * metadata dependency (feed `language` is optional and unreliable). Two signals:
 *  1. **CJK dominance** — CJK ideographs must outnumber Latin letters, so a stray
 *     Chinese phrase inside an English article never triggers a skip.
 *  2. **Absence of Traditional-only glyphs** — a curated set of characters whose
 *     Simplified form differs and which essentially never appear in Simplified text
 *     (e.g. 國/語/學 → 国/语/学). If even one is present, the article is treated as
 *     Traditional (or mixed) and is NOT skipped — conservative in the direction the
 *     product decision demands.
 *
 * Failure modes are benign: a false "not simplified" verdict translates text that was
 * already Simplified (wastes one Tier-2 call); a false "simplified" verdict would skip
 * translation of Traditional text. The glyph set is sized so the latter is exceedingly
 * unlikely for real-world Chinese news/articles.
 */
object SimplifiedChineseDetector {

    /**
     * Returns true iff translate-to-[targetLanguage] should be skipped because the article
     * text is already (predominantly) Simplified Chinese.
     *
     * Empty or Latin-dominant text never skips — the caller should still translate those.
     */
    fun shouldSkipTranslate(text: String, targetLanguage: String): Boolean {
        if (!isSimplifiedChineseTarget(targetLanguage)) return false
        val cjk = countCjkIdeographs(text)
        if (cjk == 0) return false
        // CJK must dominate Latin letters; otherwise this isn't a Chinese article.
        if (cjk < countLatinLetters(text)) return false
        // Any Traditional-only glyph → treat as Traditional/mixed, do not skip.
        if (containsTraditionalOnlyGlyph(text)) return false
        return true
    }

    /**
     * Targets that map to Simplified Chinese. `zh` with no region defaults to Simplified
     * (matches [ReaderOpsUseCase.translateLanguageName]). Underscores tolerated.
     */
    private fun isSimplifiedChineseTarget(tag: String): Boolean =
        when (tag.lowercase().replace('_', '-')) {
            "zh", "zh-cn", "zh-hans", "zh-sg" -> true
            else -> false
        }

    private fun countCjkIdeographs(text: String): Int {
        var n = 0
        for (ch in text) {
            // CJK Unified Ideograph (U+4E00–U+9FFF) + Ext A (U+3400–U+4DBF).
            // Covers virtually all modern Chinese text; rare Ext B+ are intentionally
            // excluded — they'd add noise without changing the verdict for real articles.
            if (ch.code in 0x3400..0x9FFF) n++
        }
        return n
    }

    private fun countLatinLetters(text: String): Int {
        var n = 0
        for (ch in text) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') n++
        }
        return n
    }

    /**
     * True if [text] contains any character from [TRADITIONAL_ONLY_GLYPHS].
     * Single-char set membership is O(text × set) but the set is tiny and this runs
     * once per translate tap — perf is a non-issue.
     */
    private fun containsTraditionalOnlyGlyph(text: String): Boolean {
        for (ch in text) if (ch in TRADITIONAL_ONLY_GLYPHS) return true
        return false
    }

    /**
     * Glyphs that have a distinct Simplified form and are essentially never used in
     * Simplified Chinese writing. Presence of any one is a strong Traditional signal.
     * Curated from the common Simplified↔Traditional mapping; biased toward characters
     * frequent in news, tech, and general prose.
     */
    private val TRADITIONAL_ONLY_GLYPHS: Set<Char> = setOf(
        // 高频通用
        '國', '語', '華', '學', '業', '書', '畫', '醫', '專', '頁', '廠', '廣', '長', '軍',
        '飛', '馬', '鳥', '魚', '蟲', '龍', '網', '統', '經', '結', '構', '號', '產', '際',
        '參', '區', '場', '達', '發', '為', '還', '進', '連', '過', '運', '環', '營', '總',
        '戰', '單', '們', '鐘', '銀', '鐵', '鋼', '錢', '買', '賣', '費', '貨', '資', '觀',
        '親', '見', '現', '規', '視', '覺', '認', '識', '議', '論', '講', '說', '話', '試',
        '詳', '請', '課', '讀', '繁', '體', '會', '時', '間', '邊', '這', '廳', '聽', '聯',
        '腦', '滿', '漢', '濟', '無', '點', '爺', '牆', '狀', '獨', '獄', '獲', '獻', '樹',
        '樓', '權', '歐', '氣', '潔', '燈', '報', '導', '帥', '帶', '歸', '歷', '歲', '藝',
        '護', '負', '責', '貴', '賞', '賜', '顧', '館', '驗', '驟', '驚', '髮', '鬥', '鬧',
        '麥', '麵', '黨', '齊', '齒', '龜', '雙', '壇', '離', '難', '雲', '電', '靈', '動',
        '務', '勝', '勞', '卻', '從', '復', '應', '廢', '開', '閉', '問', '關', '門', '寫',
        '對', '塊', '塵', '蝦', '懷', '憂', '憤', '懶', '擊', '據', '擁', '險', '隱', '雜',
        '霧', '濕', '濃', '濱', '滬', '層', '屬', '遲', '遼', '邁', '還', '鄰', '鄭', '鄉',
        '陰', '陽', '際', '陸', '陳', '陣', '陡', '隊', '階', '阿', '陽',
    )
}
