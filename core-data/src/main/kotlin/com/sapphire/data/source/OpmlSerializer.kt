package com.sapphire.data.source

import com.sapphire.domain.source.SourceFolderNode
import com.sapphire.domain.source.SourceNode

/**
 * Serializes the sources tree ([List]<[SourceFolderNode]>) to an OPML 2.0 document.
 *
 * Each top-level category becomes an `<outline>` (folder); each source becomes a leaf
 * `<outline>` with `type="rss"`, `xmlUrl`, `htmlUrl`, and `text`/`title`. XML-escapes all
 * attribute values so ampersands/quotes in titles don't break the document.
 *
 * Round-trips with [OpmlParser]: an export re-imported yields the same category → source
 * structure (the `[Backup] ` prefix is stripped on import, so re-export is clean).
 */
object OpmlSerializer {

    fun serialize(tree: List<SourceFolderNode>, title: String = "Sapphire Sources"): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<opml version=\"2.0\">\n")
        sb.append("  <head>\n")
        sb.append("    <title>").append(escape(title)).append("</title>\n")
        sb.append("    <dateCreated>").append(rfc822Now()).append("</dateCreated>\n")
        sb.append("  </head>\n")
        sb.append("  <body>\n")

        for (folder in tree) {
            sb.append("    <outline text=\"")
            sb.append(escape(folder.category.name))
            sb.append("\" title=\"")
            sb.append(escape(folder.category.name))
            sb.append("\">\n")

            for (node in folder.sources) {
                appendSource(sb, node)
            }

            sb.append("    </outline>\n")
        }

        sb.append("  </body>\n")
        sb.append("</opml>\n")
        return sb.toString()
    }

    private fun appendSource(sb: StringBuilder, node: SourceNode) {
        val src = node.source
        val name = src.title ?: src.url
        sb.append("      <outline type=\"rss\"")
        sb.append(" text=\"").append(escape(name)).append("\"")
        sb.append(" title=\"").append(escape(name)).append("\"")
        sb.append(" xmlUrl=\"").append(escape(src.url)).append("\"")
        sb.append(" htmlUrl=\"").append(escape(src.url)).append("\"")
        sb.append("/>\n")
    }

    private fun escape(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    private fun rfc822Now(): String {
        val sdf = java.text.SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            java.util.Locale.US,
        ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return sdf.format(java.util.Date())
    }
}
