package com.sapphire.domain.reader

/**
 * Structured rich content for the reader body (PRD §3.4).
 *
 * Replaces the old flat `List<String>` render path, which stripped all HTML. The body is
 * parsed once into an ordered list of [RichBlock]s; the UI renders each block with the
 * right Compose primitive (paragraph text, list rows, quote box, inline image, …) and the
 * inline [RichSpan]s map onto an [androidx.compose.ui.text.AnnotatedString] in the UI layer.
 *
 * **Translate/summary alignment contract.** The Tier-2 translate op is paragraph-aligned:
 * the model is instructed not to merge or split paragraphs, so paragraph *i* in the input
 * maps to paragraph *i* in the output. To keep that contract, the LLM path consumes
 * [toPlainParagraphs] — a plain-text view of the same blocks — and the UI re-indexes into
 * the translate response by counting text-bearing blocks (see
 * [textBlocks]). Non-text blocks (e.g. [RichBlock.Image] without a caption) are dropped
 * from the plain view, exactly mirroring the legacy "drop empties" behaviour the model
 * relies on.
 *
 * Pure Kotlin (no Android/Compose deps) so it is unit-testable in core-domain.
 */
sealed interface RichBlock {

    /** Flattens this block to plain text (no markup). Empty for media-only blocks. */
    fun plainText(): String

    /** A paragraph composed of styled [RichSpan] segments. */
    data class Paragraph(val spans: List<RichSpan>) : RichBlock {
        override fun plainText(): String = spans.joinToString("") { it.plainText() }
    }

    /** A heading; [level] is 1..6 (h1..h6). */
    data class Heading(val level: Int, val spans: List<RichSpan>) : RichBlock {
        override fun plainText(): String = spans.joinToString("") { it.plainText() }
    }

    /** A list item. [ordered] distinguishes `ol` (numbered) from `ul` (bulleted); [index]
     *  is the 1-based position within an ordered list (0 for unordered). */
    data class ListItem(
        val spans: List<RichSpan>,
        val ordered: Boolean,
        val index: Int,
    ) : RichBlock {
        override fun plainText(): String = spans.joinToString("") { it.plainText() }
    }

    /** A block quotation. */
    data class Quote(val spans: List<RichSpan>) : RichBlock {
        override fun plainText(): String = spans.joinToString("") { it.plainText() }
    }

    /** An embedded image. [alt]/[caption] feed the plain view when present. */
    data class Image(val url: String, val alt: String?, val caption: String?) : RichBlock {
        override fun plainText(): String = listOfNotNull(alt, caption)
            .joinToString(" ")
            .ifBlank { "" }
    }

    /** A preformatted / code block. */
    data class Code(val text: String) : RichBlock {
        override fun plainText(): String = text
    }
}

/** Inline styled text. Nesting (e.g. bold-italic) is expressed via [children]. */
sealed interface RichSpan {
    fun plainText(): String

    data class Text(val text: String) : RichSpan {
        override fun plainText(): String = text
    }

    data class Bold(val children: List<RichSpan>) : RichSpan {
        override fun plainText(): String = children.joinToString("") { it.plainText() }
    }

    data class Italic(val children: List<RichSpan>) : RichSpan {
        override fun plainText(): String = children.joinToString("") { it.plainText() }
    }

    data class Strikethrough(val children: List<RichSpan>) : RichSpan {
        override fun plainText(): String = children.joinToString("") { it.plainText() }
    }

    /** Inline code span (distinct from a [RichBlock.Code] block). */
    data class Code(val text: String) : RichSpan {
        override fun plainText(): String = text
    }

    data class Link(val url: String, val children: List<RichSpan>) : RichSpan {
        override fun plainText(): String = children.joinToString("") { it.plainText() }
    }
}

/** The text-bearing blocks, in order — the LLM-aligned view. Non-text blocks are dropped. */
val List<RichBlock>.textBlocks: List<RichBlock>
    get() = filter { it.plainText().isNotEmpty() }

/**
 * Plain-text paragraph view of [this] for the Tier-2 LLM ops (translate/summary/classify).
 * One non-empty string per text-bearing block, in document order — so paragraph *i* here
 * maps 1:1 to paragraph *i* of a translate response. Mirrors the legacy
 * `BodyParagraphParser` "drop empties" contract.
 */
fun List<RichBlock>.toPlainParagraphs(): List<String> =
    textBlocks.map { it.plainText() }
