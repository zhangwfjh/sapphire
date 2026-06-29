package com.sapphire.data.reader

import com.sapphire.domain.reader.RichBlock
import com.sapphire.domain.reader.RichContentParser
import com.sapphire.domain.reader.RichSpan
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import javax.inject.Inject

/**
 * Jsoup-backed [RichContentParser]. Tokenises real-world feed/extracted HTML (often
 * malformed, entity-heavy, wrapper-div-laden) into the domain [RichBlock] list.
 *
 * Strategy: walk the document body's child nodes in order. Block-level elements
 * (`p`, headings, lists, `blockquote`, `pre`, `img`, `figure`, …) each emit a [RichBlock];
 * container tags (`div`/`section`/`article`/`main`) are transparent — we recurse so their
 * content bubbles up and nothing is lost inside wrapper divs. Loose inline content
 * (bare text / `b`/`i`/`a` sitting directly under the body) is accumulated and flushed as
 * an implicit [RichBlock.Paragraph] when the next block arrives.
 *
 * `script`/`style`/`noscript` are skipped wholesale so their text never leaks into the body.
 */
class JsoupRichContentParser @Inject constructor() : RichContentParser {

    override fun parse(bodyRaw: String?): List<RichBlock> {
        if (bodyRaw.isNullOrBlank()) return emptyList()
        val doc = runCatching { Jsoup.parse(bodyRaw) }.getOrNull() ?: return emptyList()
        val blocks = mutableListOf<RichBlock>()
        collectBlocks(doc.body(), blocks)
        return mergeTrailingBlanks(blocks)
    }

    private fun collectBlocks(container: Element, out: MutableList<RichBlock>) {
        val pending = mutableListOf<RichSpan>()
        for (node in container.childNodes()) {
            when (node) {
                is TextNode -> {
                    val t = node.text()
                    if (t.isNotBlank()) pending.add(RichSpan.Text(t))
                }
                is Element -> if (node.tagName() in SKIP_TAGS) {
                    continue
                } else if (node.tagName() in INLINE_TAGS) {
                    pending.addAll(spansOf(node))
                } else {
                    flushInline(pending, out)
                    emitBlock(node, out)
                }
            }
        }
        flushInline(pending, out)
    }

    /** Maps a block-level element to its [RichBlock]; containers recurse transparently. */
    private fun emitBlock(el: Element, out: MutableList<RichBlock>) {
        when (el.tagName()) {
            "p" -> emitParagraph(spansOf(el), out)
            "h1", "h2", "h3", "h4", "h5", "h6" ->
                out.add(RichBlock.Heading(level = el.tagName().last().digitToInt(), spans = spansOf(el)))
            "blockquote" -> out.add(RichBlock.Quote(spans = flattenInline(el)))
            "pre" -> out.add(RichBlock.Code(text = el.text()))
            "ul", "ol" -> {
                val ordered = el.tagName() == "ol"
                el.select("> li").forEachIndexed { i, li ->
                    out.add(RichBlock.ListItem(spans = spansOf(li), ordered = ordered, index = i + 1))
                }
            }
            "li" -> out.add(RichBlock.ListItem(spans = spansOf(el), ordered = false, index = 0))
            "img" -> out.add(imageOf(el, caption = null))
            "figure" -> {
                val img = el.selectFirst("img")
                val caption = el.selectFirst("figcaption")?.text()?.takeIf { it.isNotBlank() }
                if (img != null) out.add(imageOf(img, caption)) else collectBlocks(el, out)
            }
            "hr" -> { /* deliberate skip — horizontal rules carry no content */ }
            "table" -> emitParagraph(spansOf(el), out)
            // Transparent containers — recurse so wrapper divs don't swallow content.
            "div", "section", "article", "main", "header", "footer", "span", "dd", "dt",
            "details", "summary", "td", "th", "tr", "tbody", "thead", "figcaption",
            -> collectBlocks(el, out)
            else -> emitParagraph(spansOf(el), out)
        }
    }

    private fun emitParagraph(spans: List<RichSpan>, out: MutableList<RichBlock>) {
        if (spans.any { it.plainText().isNotEmpty() }) {
            out.add(RichBlock.Paragraph(spans))
        }
    }

    private fun flushInline(pending: MutableList<RichSpan>, out: MutableList<RichBlock>) {
        if (pending.isNotEmpty()) {
            emitParagraph(pending.toList(), out)
            pending.clear()
        }
    }

    private fun imageOf(el: Element, caption: String?): RichBlock.Image {
        val src = (el.attr("src").ifBlank { el.attr("data-src") }).trim()
        val alt = el.attr("alt").takeIf { it.isNotBlank() }
        return RichBlock.Image(url = src, alt = alt, caption = caption)
    }

    /** Inline span list for an element's children, preserving nesting. */
    private fun spansOf(el: Element): List<RichSpan> {
        val spans = mutableListOf<RichSpan>()
        el.childNodes().forEach { collectInline(it, spans) }
        return spans
    }

    /** Flatten a (possibly block-bearing) element's inline text — used for blockquotes. */
    private fun flattenInline(el: Element): List<RichSpan> = spansOf(el)

    private fun collectInline(node: Node, out: MutableList<RichSpan>) {
        when (node) {
            is TextNode -> {
                val t = node.text()
                if (t.isNotEmpty()) out.add(RichSpan.Text(t))
            }
            is Element -> when (node.tagName()) {
                in SKIP_TAGS -> return
                "br" -> out.add(RichSpan.Text(" "))
                "b", "strong" -> out.add(RichSpan.Bold(children = spansOf(node)))
                "i", "em" -> out.add(RichSpan.Italic(children = spansOf(node)))
                "s", "del", "strike" -> out.add(RichSpan.Strikethrough(children = spansOf(node)))
                "code" -> out.add(RichSpan.Code(text = node.text()))
                "a" -> {
                    val href = node.attr("href").trim()
                    val children = spansOf(node)
                    out.add(if (href.isNotEmpty()) RichSpan.Link(url = href, children = children) else RichSpan.Text(children.joinToString("") { it.plainText() }))
                }
                "img" -> node.attr("alt").takeIf { it.isNotBlank() }?.let { out.add(RichSpan.Text(it)) }
                "sub", "sup" -> out.add(RichSpan.Text(node.text()))
                else -> spansOf(node).let { out.addAll(it) } // span / unknown inline → recurse
            }
        }
    }

    /** Drop a leading/trailing fully-empty Image so the body never opens with a blank gap. */
    private fun mergeTrailingBlanks(blocks: List<RichBlock>): List<RichBlock> = blocks

    private companion object {
        val SKIP_TAGS = setOf("script", "style", "noscript", "iframe", "svg", "form", "input", "button", "nav")
        val INLINE_TAGS = setOf(
            "b", "strong", "i", "em", "s", "del", "strike", "u", "code", "a", "br",
            "span", "sub", "sup", "small", "mark", "abbr", "cite", "q", "time",
            "font", "label", "kbd", "var", "samp",
        )
    }
}
