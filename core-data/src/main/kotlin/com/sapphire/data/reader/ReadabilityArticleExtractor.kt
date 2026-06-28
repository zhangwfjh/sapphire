package com.sapphire.data.reader

import com.sapphire.domain.reader.ArticleExtractor
import com.sapphire.domain.reader.BodyParagraphParser
import com.sapphire.domain.reader.ExtractionOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
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

    private val minUsableChars = 200

    override suspend fun extract(url: String): ExtractionOutcome = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .get()
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (_: IOException) {
            return@withContext ExtractionOutcome.Err.Unreachable
        }

        response.use {
            when {
                it.code in 400..499 -> ExtractionOutcome.Err.Blocked
                !it.isSuccessful -> ExtractionOutcome.Err.Unreachable
                else -> {
                    val html = it.body?.string().orEmpty()
                    extractFromHtml(url, html)
                }
            }
        }
    }

    private fun extractFromHtml(url: String, html: String): ExtractionOutcome {
        val article = Readability4J(url, html).parse()
        // readability4j 1.0.8 Article.content = articleContent.toString() (Jsoup Element
        // outerHtml) → cleaned HTML WITH block-level tags, so BodyParagraphParser can split
        // on <p>/<div>/etc. (contentWithMarkupTags does not exist in 1.0.8; content is it.)
        val contentHtml = article.content ?: ""
        val paragraphs = BodyParagraphParser.parse(contentHtml)
        val joined = paragraphs.joinToString("")
        return if (joined.length < minUsableChars) {
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
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
