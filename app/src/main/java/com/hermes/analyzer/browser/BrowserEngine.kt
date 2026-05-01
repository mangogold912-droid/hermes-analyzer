package com.hermes.analyzer.browser

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * BrowserEngine
 * 내장 브라우저 HTML 파싱 엔진 (본문, 링크, 표, 코드 블록 추출)
 */
class BrowserEngine {

    data class ParsedPage(
        val title: String,
        val url: String,
        val bodyText: String,
        val links: List<LinkInfo>,
        val tables: List<TableInfo>,
        val codeBlocks: List<CodeBlock>,
        val images: List<ImageInfo>
    )

    data class LinkInfo(val text: String, val href: String)
    data class TableInfo(val headers: List<String>, val rows: List<List<String>>)
    data class CodeBlock(val language: String?, val code: String)
    data class ImageInfo(val src: String, val alt: String, val width: Int, val height: Int)

    @JavascriptInterface
    fun parsePageHTML(html: String, url: String): String {
        val result = parseHTML(html, url)
        return JSONObject().apply {
            put("title", result.title)
            put("url", result.url)
            put("bodyText", result.bodyText.take(5000))
            put("links", JSONArray(result.links.map { JSONObject().apply {
                put("text", it.text); put("href", it.href)
            }}))
            put("tables", JSONArray(result.tables.map { t ->
                JSONObject().apply {
                    put("headers", JSONArray(t.headers))
                    put("rows", JSONArray(t.rows.map { JSONArray(it) }))
                }
            }))
            put("codeBlocks", JSONArray(result.codeBlocks.map { JSONObject().apply {
                put("language", it.language); put("code", it.code.take(500))
            }}))
        }.toString()
    }

    fun parseHTML(html: String, url: String): ParsedPage {
        val title = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim() ?: ""
        
        // Extract body text (strip tags)
        val bodyText = stripTags(html)
        
        // Extract links
        val links = mutableListOf<LinkInfo>()
        Regex("""<a[^>]+href=["']([^"']+)["'][^>]*>([^<]*)</a>""", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            links.add(LinkInfo(match.groupValues[2].trim(), resolveUrl(match.groupValues[1], url)))
        }
        
        // Extract tables
        val tables = extractTables(html)
        
        // Extract code blocks
        val codeBlocks = mutableListOf<CodeBlock>()
        Regex("""<pre[^>]*>(.*?)</pre>""", RegexOption.DOT_MATCHES_ALL + RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val content = stripTags(match.groupValues[1])
            codeBlocks.add(CodeBlock(null, content))
        }
        Regex("""<code[^>]*>(.*?)</code>""", RegexOption.DOT_MATCHES_ALL + RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val content = stripTags(match.groupValues[1])
            if (content.length > 20) {
                codeBlocks.add(CodeBlock(null, content))
            }
        }
        
        // Extract images
        val images = mutableListOf<ImageInfo>()
        Regex("""<img[^>]+src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val imgTag = match.groupValues[0]
            val src = resolveUrl(match.groupValues[1], url)
            val alt = Regex("""alt=["']([^"']*)["']""").find(imgTag)?.groupValues?.get(1) ?: ""
            images.add(ImageInfo(src, alt, 0, 0))
        }
        
        return ParsedPage(title, url, bodyText, links, tables, codeBlocks, images)
    }

    private fun stripTags(html: String): String {
        var text = html
        text = text.replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        text = text.replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        text = text.replace(Regex("<[^>]+>"), " ")
        text = text.replace("&lt;", "<")
        text = text.replace("&gt;", ">")
        text = text.replace("&amp;", "&")
        text = text.replace("&quot;", "\"")
        text = text.replace(Regex("\s+"), " ")
        return text.trim()
    }


    private fun extractTables(html: String): List<TableInfo> {
        val tables = mutableListOf<TableInfo>()
        Regex("""<table[^>]*>(.*?)</table>""", RegexOption.DOT_MATCHES_ALL + RegexOption.IGNORE_CASE).findAll(html).forEach { tableMatch ->
            val tableHtml = tableMatch.groupValues[1]
            val headers = mutableListOf<String>()
            val rows = mutableListOf<List<String>>()
            
            Regex("""<th[^>]*>(.*?)</th>""", RegexOption.DOT_MATCHES_ALL + RegexOption.IGNORE_CASE).findAll(tableHtml).forEach { th ->
                headers.add(stripTags(th.groupValues[1]).trim())
            }
            
            Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL + RegexOption.IGNORE_CASE).findAll(tableHtml).forEach { tr ->
                val cells = mutableListOf<String>()
                Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL + RegexOption.IGNORE_CASE).findAll(tr.groupValues[1]).forEach { td ->
                    cells.add(stripTags(td.groupValues[1]).trim())
                }
                if (cells.isNotEmpty()) rows.add(cells)
            }
            
            if (headers.isNotEmpty() || rows.isNotEmpty()) {
                tables.add(TableInfo(headers, rows))
            }
        }
        return tables
    }

    private fun resolveUrl(href: String, baseUrl: String): String {
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> baseUrl.substringBefore("/", baseUrl) + href
            else -> baseUrl.substringBeforeLast("/") + "/" + href
        }
    }

    fun formatAnalysis(parsed: ParsedPage): String {
        val sb = StringBuilder()
        sb.append("# ${parsed.title}\n\n")
        sb.append("URL: ${parsed.url}\n\n")
        
        sb.append("## Summary\n")
        sb.append("${parsed.bodyText.take(500)}...\n\n")
        
        sb.append("## Links (${parsed.links.size})\n")
        parsed.links.take(20).forEach { sb.append("- [${it.text}](${it.href})\n") }
        sb.append("\n")
        
        if (parsed.tables.isNotEmpty()) {
            sb.append("## Tables (${parsed.tables.size})\n")
            parsed.tables.forEachIndexed { i, t ->
                sb.append("### Table ${i + 1}\n")
                sb.append("Headers: ${t.headers.joinToString(" | ")}\n")
                t.rows.take(5).forEach { row ->
                    sb.append("| ${row.joinToString(" | ")} |\n")
                }
                sb.append("\n")
            }
        }
        
        if (parsed.codeBlocks.isNotEmpty()) {
            sb.append("## Code Blocks (${parsed.codeBlocks.size})\n")
            parsed.codeBlocks.take(5).forEach { block ->
                sb.append("```${block.language ?: ""}\n")
                sb.append("${block.code.take(200)}\n")
                sb.append("```\n\n")
            }
        }
        
        return sb.toString()
    }
}
