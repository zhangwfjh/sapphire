package com.sapphire.data.source

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * Parses an OPML 2.0 file into a flat category → source structure.
 *
 * Flattening: each top-level `<outline>` (direct child of `<body>`, no `xmlUrl`) becomes
 * a category. ALL descendant leaf outlines (any depth, with `xmlUrl`) roll up into that
 * category. This matches Sapphire's single-level category model — intermediate sub-folders
 * in the OPML are collapsed, their sources collected into the top-level parent.
 *
 * All sources are typed [SourceKind.RSS]; the fetcher auto-detects RSS/Atom/JSON from the
 * response body so the declared `type` is not load-bearing.
 */
object OpmlParser {

    data class ParsedSource(val title: String, val url: String)
    data class ParsedCategory(val name: String, val sources: List<ParsedSource>)
    data class ParsedOpml(val categories: List<ParsedCategory>) {
        val totalSources: Int get() = categories.sumOf { it.sources.size }
    }

    fun parse(stream: InputStream): ParsedOpml {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(stream, "UTF-8")

        val categories = mutableListOf<ParsedCategory>()
        val currentSources = mutableListOf<ParsedSource>()
        var currentCategoryName: String? = null
        var depth = 0
        var inBody = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "body" -> { inBody = true; depth = 0 }
                    "outline" -> if (inBody) {
                        depth++
                        val xmlUrl = parser.getAttributeValue(null, "xmlUrl")
                        val title = parser.getAttributeValue(null, "text")
                            ?: parser.getAttributeValue(null, "title")
                            ?: "Untitled"

                        if (xmlUrl.isNullOrBlank()) {
                            // Container outline. At depth 1 (direct child of body) → new category.
                            if (depth == 1) {
                                commitCategory(categories, currentCategoryName, currentSources)
                                currentCategoryName = cleanName(title)
                                currentSources.clear()
                            }
                            // Deeper containers are flattened — their leaves roll up.
                        } else {
                            currentSources.add(ParsedSource(title, xmlUrl))
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "body" -> {
                        commitCategory(categories, currentCategoryName, currentSources)
                        inBody = false
                    }
                    "outline" -> if (inBody) depth--
                }
            }
            event = parser.next()
        }

        return ParsedOpml(categories)
    }

    private fun commitCategory(
        out: MutableList<ParsedCategory>,
        name: String?,
        sources: MutableList<ParsedSource>,
    ) {
        if (name != null && sources.isNotEmpty()) {
            out.add(ParsedCategory(name, sources.toList()))
        }
    }

    /** Strip "[Backup] " prefix and trim whitespace. */
    private fun cleanName(name: String): String =
        name.removePrefix("[Backup] ").trim()
}
