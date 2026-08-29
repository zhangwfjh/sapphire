package com.sapphire.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.ceil

/**
 * Offline KaTeX + Mermaid rendering for reader bodies. The bundles live in
 * `assets/vendor/` so math and diagrams render with no network. Both views are
 * height-measuring WebViews: after content settles, JS reports scrollHeight and the
 * Compose node adopts it, so blocks flow natively inside [RichBlockList].
 */

/** True when a paragraph is one standalone display-math block (`$$ … $$` or `\[ … \]`). */
fun isDisplayMath(plainText: String): Boolean {
    val t = plainText.trim()
    if (t.length <= 4) return false
    return (t.startsWith("$$") && t.endsWith("$$")) ||
        (t.startsWith("\\[") && t.endsWith("\\]"))
}

/** Display-math body without the delimiters. */
fun displayMathLatex(plainText: String): String = plainText.trim()
    .removePrefix("$$").removeSuffix("$$")
    .removePrefix("\\[").removeSuffix("\\]")
    .trim()

/** True when a Code block carries a mermaid definition (language marker or ```mermaid fence). */
fun isMermaidDefinition(codeText: String): Boolean {
    val first = codeText.lineSequence().firstOrNull()?.trim() ?: return false
    return first.equals("mermaid", ignoreCase = true) || first == "```mermaid"
}


/** Math delimiters inside flowing text: `\( … \)` or single-dollar with non-space edges. */
private val INLINE_MATH = Regex("""\\\([\s\S]+?\\\)|\$[^\s$](?:[^$\n]*[^\s$])?\$""")

/** True when the text mixes prose with inline math (and is not pure display math). */
fun hasInlineMath(plainText: String): Boolean =
    !isDisplayMath(plainText) && INLINE_MATH.containsMatchIn(plainText)
/** Lazily-read vendored KaTeX bundle, inlined into each math WebView's HTML (no subresource loads). */
private object KatexAssets {
    @Volatile private var loaded = false
    @Volatile var js: String = ""
    @Volatile var css: String = ""

    fun load(context: android.content.Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                js = context.assets.open("vendor/katex/katex.min.js").bufferedReader().readText()
                    // Never let the payload terminate our own <script> element.
                    .replace("</script", "<\\/script")
                // Fonts can't be inlined cheaply — point the CSS at the asset files absolutely.
                css = context.assets.open("vendor/katex/katex.min.css").bufferedReader().readText()
                    .replace("url(fonts/", "url(file:///android_asset/vendor/katex/fonts/")
            }
            loaded = true
        }
    }
}
/**
 * A prose paragraph containing inline (or stray display) math, rendered by KaTeX with the
 * text around it as styled spans — one WebView for the whole paragraph. Plain paragraphs
 * never route here, so only math-bearing text pays the WebView cost.
 */
@Composable
fun KatexRichText(text: String) {
    val context = LocalContext.current
    KatexAssets.load(context)
    val html = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>${'$'}{KatexAssets.css}</style>
        <style>
          html,body{margin:0;padding:2px 0;background:transparent;color:#D8DEE6;}
          #root{font-size:15.5px;line-height:1.7;}
          .katex{font-size:1.05em;}
          .katex-display{margin:0.4em 0;}
        </style></head>
        <body><div id="root"></div>
        <script>${'$'}{KatexAssets.js}</script>
        <script>
          (function(){
            var text = ${jsStringLiteral(text)};
            var re = /\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\\\(([\s\S]+?)\\\)|\$([^\s$](?:[^$\n]*[^\s$])?)\$/g;
            var esc = function(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')};
            var out = '', last = 0, m;
            while ((m = re.exec(text)) !== null) {
              out += esc(text.slice(last, m.index));
              var tex = m[1] !== undefined ? m[1] : (m[2] !== undefined ? m[2] : (m[3] !== undefined ? m[3] : m[4]));
              var disp = m[1] !== undefined || m[2] !== undefined;
              try { out += katex.renderToString(tex, {displayMode: disp, throwOnError: false, strict: false}); }
              catch (e) { out += esc(m[0]); }
              last = m.index + m[0].length;
            }
            out += esc(text.slice(last));
            document.getElementById('root').innerHTML = out;
          })();
        </script></body></html>
    """.trimIndent()
    MeasuringWebView(html = html, viewId = "katexrich-${text.hashCode()}")
}
/** Mermaid definition without the marker/fence lines. */
fun mermaidDefinition(codeText: String): String {
    val lines = codeText.lineSequence().filter { it.trim() != "```" }.toList()
    val body = if (lines.firstOrNull()?.trim()?.equals("mermaid", ignoreCase = true) == true) lines.drop(1) else lines
    return body.joinToString("\n").trim()
}

/**
 * The shared measuring WebView host. [html] is fully-formed; content signals its height
 * through `Android.onHeight`, and the node re-lays out to fit. Initial [minHeightDp]
 * prevents a zero-height flash while scripts parse.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MeasuringWebView(
    html: String,
    viewId: String,
    minHeightDp: Int = 60,
) {
    val context = LocalContext.current
    var heightPx by remember(viewId) { mutableIntStateOf(0) }
    val density = context.resources.displayMetrics.density
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .layoutId(viewId)
            .then(
                if (heightPx > 0) Modifier.height(((ceil(heightPx / density)).toInt().coerceAtLeast(1)).dp)
                else Modifier.height(minHeightDp.dp),
            ),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(
                    object {
                        @android.webkit.JavascriptInterface
                        fun onHeight(px: Int) {
                            if (px > 0) heightPx = px
                        }
                    },
                    "Android",
                )
                webViewClient = object : WebViewClient() {
                    // Serve the vendored katex/mermaid bundles (and fonts) straight from the
                    // AssetManager — WebView blocks file:///android_asset subresource loads on
                    // some API levels even with allowFileAccess, which left math WebViews blank.
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): android.webkit.WebResourceResponse? {
                        val url = request.url.toString()
                        if (!url.startsWith("file:///android_asset/")) return null
                        val path = url.removePrefix("file:///android_asset/")
                            .substringBefore("?").substringBefore("#")
                        if (path.isBlank() || path.endsWith("/")) return null
                        val mime = when (path.substringAfterLast('.', "").lowercase()) {
                            "js" -> "application/javascript"
                            "css" -> "text/css"
                            "woff2" -> "font/woff2"
                            "html" -> "text/html"
                            else -> "application/octet-stream"
                        }
                        return try {
                            android.webkit.WebResourceResponse(mime, "utf-8", view.context.assets.open(path))
                        } catch (e: Exception) {
                            null
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // Continuous height tracking: timed passes cover font-load settling;
                        // ResizeObserver catches any later reflow. Height = max(body scroll,
                        // content rect bottom) + slack. Never documentElement.scrollHeight —
                        // it tracks the WebView's viewport and would loop grow->measure->grow.
                        val measure = "function __m(){" +
                            "var b=document.body;" +
                            "var el=document.getElementById('math')||document.getElementById('root')||document.querySelector('.mermaid');" +
                            "var r=el?el.getBoundingClientRect():{bottom:0};" +
                            "var h=Math.max(b.scrollHeight,r.bottom+2);" +
                            "window.Android.onHeight(Math.ceil(h+16));" +
                            "};" +
                            "setTimeout(__m,120);setTimeout(__m,700);setTimeout(__m,1400);" +
                            "if(window.ResizeObserver){" +
                            "var __t=document.getElementById('math')||document.getElementById('root')||document.querySelector('.mermaid');" +
                            "if(__t){new ResizeObserver(function(){__m();}).observe(__t);new ResizeObserver(function(){__m();}).observe(document.body);}" +
                            "}"
                        view.evaluateJavascript(measure, null)
                    }
                }
                loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
            }
        },
        update = { /* content is static per viewId; no updates needed */ },
    )
}

/** Renders one LaTeX display-math block with KaTeX (offline). */
@Composable
fun KatexBlock(latex: String) {
    val context = LocalContext.current
    KatexAssets.load(context)
    val html = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>${'$'}{KatexAssets.css}</style>
        <style>
          html,body{margin:0;padding:8px 2px;background:transparent;}
          #math{display:block;}
          .katex{font-size:1.12em;color:#D8DEE6;}
          .katex-display{margin:0.2em 0;}
          .katex-error{color:#F87171;font-family:monospace;font-size:13px;}
        </style></head>
        <body><div id="math"></div>
        <script>${'$'}{KatexAssets.js}</script>
        <script>
          try {
            katex.render(${jsStringLiteral(latex)}, document.getElementById("math"), {
              displayMode: true, throwOnError: false, strict: false, trust: true
            });
          } catch(e) {
            document.getElementById("math").textContent = ${jsStringLiteral(latex)};
          }
        </script></body></html>
    """.trimIndent()
    MeasuringWebView(html = html, viewId = "katex-${latex.hashCode()}")
}

/** Renders one mermaid diagram definition (offline). */
@Composable
fun MermaidDiagram(definition: String) {
    val html = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <script src="vendor/mermaid/mermaid.min.js"></script>
        <style>
          html,body{margin:0;padding:6px 0;background:transparent;}
          .mermaid{display:flex;justify-content:center;}
          .mermaid svg{max-width:100%;height:auto;}
        </style></head>
        <body><pre class="mermaid">${htmlEscape(definition)}</pre>
        <script>
          mermaid.initialize({
            startOnLoad: true, theme: "dark", securityLevel: "strict",
            themeVariables: { background: "transparent", fontSize: "14px" }
          });
          mermaid.run({querySelector: ".mermaid"}).catch(function(){});
        </script></body></html>
    """.trimIndent()
    MeasuringWebView(html = html, viewId = "mermaid-${definition.hashCode()}", minHeightDp = 120)
}

private fun htmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun jsStringLiteral(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
