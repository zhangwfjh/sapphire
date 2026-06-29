package com.sapphire.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireFonts
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.reader.RichBlock
import com.sapphire.domain.reader.RichSpan

private const val URL_TAG = "url"

/**
 * Renders an ordered list of [RichBlock]s — the rich article body (PRD §3.4). Each block
 * type maps to its own Compose primitive: paragraphs/headings/quotes via an
 * [AnnotatedString] (so inline bold/italic/strike/code/links survive), list items as marker
 * rows, blockquotes as an accent-ruled indented block, code as a mono slab, and images as
 * inline [AsyncImage]s. Links open through the platform [LocalUriHandler].
 *
 * [translateTargets] — when non-null, the renderer interleaves each text-bearing block with
 * its translation (paragraph-aligned: text-block *i* ↔ [translateTargets][i]). Non-text
 * blocks (images without caption) render standalone and do not consume a translate slot.
 */
@Composable
fun RichBlockList(
    blocks: List<RichBlock>,
    modifier: Modifier = Modifier,
    translateTargets: List<String>? = null,
) {
    val palette = LocalSapphirePalette.current
    var textIndex = 0
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        blocks.forEach { block ->
            val translated = translateTargets?.getOrNull(textIndex)
            RichBlockView(block)
            if (block.plainText().isNotEmpty()) {
                if (translated != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        translated,
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = FontStyle.Italic,
                        color = palette.AccentBright,
                    )
                }
                textIndex++
            }
        }
    }
}

@Composable
private fun RichBlockView(block: RichBlock) {
    val palette = LocalSapphirePalette.current
    when (block) {
        is RichBlock.Paragraph -> RichSpanText(block.spans, color = palette.ReaderInk)
        is RichBlock.Heading -> RichSpanText(
            spans = block.spans,
            color = palette.OnInk,
            base = headingStyle(block.level),
        )
        is RichBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (block.ordered) "${block.index}." else "•",
                style = SapphireMono.Label,
                color = palette.Accent,
                modifier = Modifier.width(18.dp),
            )
            Column(Modifier.weight(1f)) {
                RichSpanText(block.spans, color = palette.ReaderInk)
            }
        }
        is RichBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.Accent.copy(alpha = 0.6f)),
            )
            Column(Modifier.padding(start = 12.dp)) {
                RichSpanText(
                    block.spans,
                    color = palette.OnInkMuted,
                    base = TextStyle(
                        fontFamily = SapphireFonts.display,
                        fontStyle = FontStyle.Italic,
                        fontSize = 16.sp,
                        lineHeight = 25.sp,
                    ),
                )
            }
        }
        is RichBlock.Code -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.InkRaised)
                .padding(12.dp),
        ) {
            Text(block.text, style = SapphireMono.Body, color = palette.ReaderInk)
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
        }
    }
}

/** Serif heading scale keyed to level; falls back to bodyLarge beyond h3. */
private fun headingStyle(level: Int) = when (level) {
    1 -> TextStyle(fontFamily = SapphireFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 33.sp)
    2 -> TextStyle(fontFamily = SapphireFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp)
    else -> TextStyle(fontFamily = SapphireFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp)
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
    base: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 25.sp, letterSpacing = 0.25.sp),
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
