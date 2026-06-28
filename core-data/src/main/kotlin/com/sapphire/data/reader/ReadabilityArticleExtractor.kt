package com.sapphire.data.reader

import com.sapphire.domain.reader.ArticleExtractor
import com.sapphire.domain.reader.BodyParagraphParser
import com.sapphire.domain.reader.ExtractionOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * readability4J-backed [ArticleExtractor]. Fetches HTML via the shared [OkHttpClient] with
 * a browser-like User-Agent (avoids naive blocks), then runs Mozilla-Readability heuristics.
 * The extracted HTML is normalized through [BodyParagraphParser] so feed-body and
 * extracted-body share one paragraph pipeline.
 *
 * Failures map to the [ExtractionOutcome.Err] taxonomy — callers fall back silently.
 */
class ReadabilityArticleExtractor @Inject constructor(
    private val client: OkHttpClient,
) : ArticleExtractor {

    override suspend fun extract(url: String): ExtractionOutcome = withContext(Dispatchers.IO) {
        // One try covers the whole fetch+read path so any transport/encoding failure maps
        // into the Err taxonomy instead of escaping the non-fatal contract. execute() throws
        // IOException on network failure; body.string() throws IOException on a truncated
        // body; Request.Builder().url() throws IllegalArgumentException on a malformed URL.
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code in 400..499 -> ExtractionOutcome.Err.Blocked
                    !response.isSuccessful -> ExtractionOutcome.Err.Unreachable
                    else -> extractFromHtml(url, response.body?.string().orEmpty())
                }
            }
        } catch (_: java.io.IOException) {
            ExtractionOutcome.Err.Unreachable
        } catch (_: IllegalArgumentException) {
            // Malformed URL (HttpUrl.parse rejects it) — nothing to reach.
            ExtractionOutcome.Err.Unreachable
        }
    }

    private fun extractFromHtml(url: String, html: String): ExtractionOutcome {
        val article = try {
            Readability4J(url, html).parse()
        } catch (_: Exception) {
            // readability4J/Jsoup choked on pathological HTML — treat as nothing extracted.
            return ExtractionOutcome.Err.Empty
        }
        // readability4j 1.0.8 Article.content = articleContent.toString() (Jsoup Element
        // outerHtml) → cleaned HTML WITH block-level tags, so BodyParagraphParser can split
        // on <p>/<div>/etc. (contentWithMarkupTags does not exist in 1.0.8; content is it.)
        val contentHtml = article.content ?: ""
        val paragraphs = BodyParagraphParser.parse(contentHtml)
        val joined = paragraphs.joinToString("")
        return if (joined.length < MIN_USABLE_CHARS) {
            ExtractionOutcome.Err.Empty
        } else {
            ExtractionOutcome.Ok(
                title = article.title?.takeIf { it.isNotBlank() },
                paragraphs = paragraphs,
                byline = article.byline?.takeIf { it.isNotBlank() },
            )
        }
    }

    private companion object {
        const val MIN_USABLE_CHARS = 200
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
