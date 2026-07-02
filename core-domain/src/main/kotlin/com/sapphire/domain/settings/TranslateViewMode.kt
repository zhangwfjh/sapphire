package com.sapphire.domain.settings

/** How translated paragraphs render in the reader. */
enum class TranslateViewMode {
    BILINGUAL,     // origin paragraph then translation
    ORIGIN,        // origin text only
    TRANSLATION,   // translation only
}
