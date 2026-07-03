package com.sapphire.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireFonts
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.reader.RichBlock
import com.sapphire.domain.reader.RichSpan
import com.sapphire.domain.reader.isTextBlock

private const val URL_TAG = "url"

/**
 * Renders an ordered list of [RichBlock]s — the rich article body (PRD §3.4). Each block
 * type maps to its own Compose primitive: paragraphs/headings/quotes via an
 * [AnnotatedString] (so inline bold/italic/strike/code/links survive), list items as marker
 * rows, blockquotes as an accent-ruled indented block, code as a mono slab, and images as
 * inline [AsyncImage]s. Links open through the platform [LocalUriHandler].
 *
 * [translateTargets] — when non-null, the renderer interleaves each text-bearing block with
 * its translation (paragraph-aligned: text-block *i* ↔ [translateTargets][i]). A block
 * consumes a slot iff [RichBlock.isTextBlock]: paragraphs, headings, list items, quotes, and
 * captioned/alt-text images do; [RichBlock.Code] and media-only images render standalone and
 * consume no slot. The slot counter consults the same predicate the LLM input path uses, so
 * the two cannot drift.
 */
@Composable
fun RichBlockList(
    blocks: List<RichBlock>,
    modifier: Modifier = Modifier,
    translateTargets: List<String>? = null,
    hideOriginals: Boolean = false,
) {
    val palette = LocalSapphirePalette.current
    var textIndex = 0
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(blockGap(blocks[index - 1], block)))
            // Only text-bearing blocks may consume a translate slot. A non-text block (e.g. an
            // empty/decorative blockquote, a media-only image) must neither read nor advance
            // the index — otherwise it steals the next paragraph's translation and renders it
            // in the wrong place (e.g. inside an empty quote box, above the real original).
            val translated = if (block.isTextBlock()) translateTargets?.getOrNull(textIndex) else null
            RichBlockView(block, translated = translated, hideOriginals = hideOriginals)
            if (block.isTextBlock()) textIndex++
        }
    }
}

@Composable
private fun RichBlockView(block: RichBlock, translated: String? = null, hideOriginals: Boolean = false) {
    val palette = LocalSapphirePalette.current
    // In TRANSLATION mode with a translation available, show only the translation as the
    // primary text; otherwise show the original (with translation appended in bilingual mode).
    val showTranslationAsPrimary = hideOriginals && !translated.isNullOrEmpty()
    when (block) {
        is RichBlock.Paragraph -> Column {
            if (showTranslationAsPrimary) {
                RichSpanText(listOf(RichSpan.Text(translated!!)), color = palette.ReaderInk)
            } else {
                RichSpanText(block.spans, color = palette.ReaderInk)
                TranslatedText(translated)
            }
        }
        is RichBlock.Heading -> Column {
            if (showTranslationAsPrimary) {
                RichSpanText(
                    spans = listOf(RichSpan.Text(translated!!)),
                    color = palette.OnInk,
                    base = headingStyle(block.level),
                )
            } else {
                RichSpanText(
                    spans = block.spans,
                    color = palette.OnInk,
                    base = headingStyle(block.level),
                )
                TranslatedText(translated)
            }
        }
        is RichBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (block.ordered) "${block.index}." else "•",
                style = SapphireMono.Label,
                color = palette.Accent,
                modifier = Modifier.width(18.dp),
            )
            Column(Modifier.weight(1f)) {
                if (showTranslationAsPrimary) {
                    RichSpanText(listOf(RichSpan.Text(translated!!)), color = palette.ReaderInk)
                } else {
                    RichSpanText(block.spans, color = palette.ReaderInk)
                    TranslatedText(translated)
                }
            }
        }
        is RichBlock.Quote -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.Accent.copy(alpha = 0.06f))
                .drawBehind {
                    // Accent bar drawn directly so the Column sizes to its real content height
                    // (IntrinsicSize.Min under-reports when the translation text is appended,
                    // clipping it inside the rounded box).
                    drawRoundRect(
                        color = palette.Accent.copy(alpha = 0.6f),
                        topLeft = Offset(14.dp.toPx(), 12.dp.toPx()),
                        size = Size(3.dp.toPx(), size.height - 24.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
                .padding(start = 29.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Text(
                "\u201C",
                color = palette.Accent.copy(alpha = 0.5f),
                style = TextStyle(
                    fontFamily = SapphireFonts.display,
                    fontSize = 28.sp,
                    lineHeight = 28.sp,
                ),
            )
            if (showTranslationAsPrimary) {
                // TRANSLATION mode: show only the translated text in the quote.
                Text(
                    translated!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.AccentBright,
                )
            } else {
                RichSpanText(
                    block.spans,
                    color = palette.OnInkMuted,
                    base = TextStyle(
                        fontFamily = SapphireFonts.display,
                        fontStyle = FontStyle.Italic,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                    ),
                )
                // Translation renders INSIDE the quote box, after the original — no quote
                // mark, no italic, so it reads as a plain gloss rather than a second quote.
                if (!translated.isNullOrEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        translated,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.AccentBright,
                    )
                }
            }
        }
        is RichBlock.Code -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.InkRaised),
        ) {
            Text(
                "CODE",
                style = SapphireMono.Label,
                color = palette.OnInkFaint,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp),
            )
            Text(
                block.text,
                style = SapphireMono.Body,
                color = palette.ReaderInk,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        is RichBlock.Image -> Column {
            if (block.url.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.InkRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = block.url,
                        contentDescription = block.alt,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            block.caption?.takeIf { it.isNotBlank() }?.let { cap ->
                Spacer(Modifier.height(6.dp))
                Text(cap, style = SapphireMono.Label, color = palette.OnInkFaint)
            }
            // Images always show their alt-text translation if present (captions are part of
            // the translate stream); hideOriginals doesn't suppress media captions.
            TranslatedText(translated)
        }
    }
}

/** The translation of a text block — rendered after the original as a plain accent line. */
@Composable
private fun TranslatedText(translated: String?) {
    if (translated.isNullOrEmpty()) return
    val palette = LocalSapphirePalette.current
    Spacer(Modifier.height(4.dp))
    Text(
        translated,
        style = MaterialTheme.typography.bodyLarge,
        color = palette.AccentBright,
    )
}

/** Serif heading scale keyed to level; falls back to bodyLarge beyond h3. */
private fun headingStyle(level: Int) = when (level) {
    1 -> TextStyle(fontFamily = SapphireFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 33.sp)
    2 -> TextStyle(fontFamily = SapphireFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp)
    else -> TextStyle(fontFamily = SapphireFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp)
}

/**
 * Editorial block rhythm: the vertical gap before [cur] given the preceding [prev] block.
 * Headings open new sections (large gap above, tight below); list items cluster tightly;
 * everything else breathes at a comfortable paragraph gap.
 */
private fun blockGap(prev: RichBlock, cur: RichBlock): Dp = when {
    cur is RichBlock.Heading -> 24.dp
    prev is RichBlock.Heading -> 8.dp
    prev is RichBlock.ListItem && cur is RichBlock.ListItem -> 6.dp
    prev is RichBlock.ListItem || cur is RichBlock.ListItem -> 12.dp
    prev is RichBlock.Quote || cur is RichBlock.Quote -> 16.dp
    prev is RichBlock.Image || cur is RichBlock.Image -> 16.dp
    prev is RichBlock.Code || cur is RichBlock.Code -> 16.dp
    else -> 14.dp
}

/**
 * Clickable rich-text line. Builds an [AnnotatedString] from [spans] (bold/italic/strike/
 * inline-code/links) and routes link taps through the platform [LocalUriHandler]. Uses
 * [ClickableText] so per-span link clicks resolve by offset.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
private fun RichSpanText(
    spans: List<RichSpan>,
    color: Color,
    base: TextStyle = TextStyle(
        fontFamily = SapphireFonts.sans,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp,
    ),
) {
    val palette = LocalSapphirePalette.current
    val uriHandler = LocalUriHandler.current
    val spanBase = base.toSpanStyle().merge(SpanStyle(color = color))
    val annotated = remember(spans, spanBase) {
        buildRichString(spans, spanBase, palette.AccentBright)
    }
    ClickableText(
        text = annotated,
        style = base.copy(color = color),
        onClick = { offset ->
            annotated.getStringAnnotations(URL_TAG, offset, offset)
                .firstOrNull()?.item?.let { uriHandler.openUri(it) }
        },
    )
}

private fun buildRichString(
    spans: List<RichSpan>,
    base: SpanStyle,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    withStyle(base) {
        spans.forEach { appendSpan(it, base, linkColor) }
    }
}

@OptIn(ExperimentalTextApi::class)
private fun AnnotatedString.Builder.appendSpan(
    span: RichSpan,
    base: SpanStyle,
    linkColor: Color,
) {
    when (span) {
        is RichSpan.Text -> append(span.text)
        is RichSpan.Bold -> withStyle(base.merge(SpanStyle(fontWeight = FontWeight.Bold))) {
            span.children.forEach { appendSpan(it, base.merge(SpanStyle(fontWeight = FontWeight.Bold)), linkColor) }
        }
        is RichSpan.Italic -> withStyle(base.merge(SpanStyle(fontStyle = FontStyle.Italic))) {
            span.children.forEach { appendSpan(it, base, linkColor) }
        }
        is RichSpan.Strikethrough -> withStyle(base.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))) {
            span.children.forEach { appendSpan(it, base, linkColor) }
        }
        is RichSpan.Code -> withStyle(base.merge(SpanStyle(fontFamily = SapphireFonts.mono))) {
            append(span.text)
        }
        is RichSpan.Link -> withAnnotation(URL_TAG, span.url) {
            val linkBase = base.merge(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            withStyle(linkBase) {
                span.children.forEach { appendSpan(it, linkBase, linkColor) }
            }
        }
    }
}
