package com.hermes.analyzer.browser

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

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
            put("codeBlocks", JSONArray(result.codeBlocks.map { JSONObject().apply {
                put("language", it.language); put("code", it.code.take(500))
            }}))
        }.toString()
    }

    fun parseHTML(html: String, url: String): ParsedPage {
        val title = extractTitle(html)
        val bodyText = stripTags(html)
        val links = extractLinks(html, url)
        val tables = extractTables(html)
        val codeBlocks = extractCodeBlocks(html)
        val images = extractImages(html, url)
        return ParsedPage(title, url, bodyText, links, tables, codeBlocks, images)
    }

    private fun extractTitle(html: String): String {
        val idx = html.indexOf("<title>", ignoreCase = true)
        val end = html.indexOf("</title>", ignoreCase = true)
        return if (idx >= 0 && end > idx) html.substring(idx + 7, end).trim() else ""
    }

    private fun stripTags(html: String): String {
        var text = html
        while (true) {
            val start = text.indexOf('<')
            if (start == -1) break
            val end = text.indexOf('>', start)
            if (end == -1) break
            text = text.substring(0, start) + " " + text.substring(end + 1)
        }
        text = text.replace("&lt;", "<")
        text = text.replace("&gt;", ">")
        text = text.replace("&amp;", "&")
        text = text.replace("&quot;", "\"")
        text = text.replace("  ", " ")
        return text.trim()
    }
    private fun extractLinks(html: String, baseUrl: String): List<LinkInfo> {
        val links = mutableListOf<LinkInfo>()
        val pattern = Regex("""<a[^>]+href=["']([^"']+)["'][^>]*>([^<]*)</a>""", RegexOption.IGNORE_CASE)
        pattern.findAll(html).forEach { match ->
            links.add(LinkInfo(match.groupValues[2].trim(), resolveUrl(match.groupValues[1], baseUrl)))
        }
        return links
    }

    private fun extractTables(html: String): List<TableInfo> {
        val tables = mutableListOf<TableInfo>()
        val tablePattern = Regex("""<table[^>]*>(.*?)</table>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        tablePattern.findAll(html).forEach { tableMatch ->
            val tableHtml = tableMatch.groupValues[1]
            val headers = mutableListOf<String>()
            val rows = mutableListOf<List<String>>()
            Regex("""<th[^>]*>(.*?)</th>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(tableHtml).forEach { th ->
                headers.add(stripTags(th.groupValues[1]).trim())
            }
            Regex("""<tr[^>]*>(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(tableHtml).forEach { tr ->
                val cells = mutableListOf<String>()
                Regex("""<td[^>]*>(.*?)</td>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(tr.groupValues[1]).forEach { td ->
                    cells.add(stripTags(td.groupValues[1]).trim())
                }
                if (cells.isNotEmpty()) rows.add(cells)
            }
            if (headers.isNotEmpty() || rows.isNotEmpty()) tables.add(TableInfo(headers, rows))
        }
        return tables
    }

    private fun extractCodeBlocks(html: String): List<CodeBlock> {
        val blocks = mutableListOf<CodeBlock>()
        Regex("""<pre[^>]*>(.*?)</pre>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).forEach { match ->
            blocks.add(CodeBlock(null, stripTags(match.groupValues[1])))
        }
        Regex("""<code[^>]*>(.*?)</code>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).forEach { match ->
            val content = stripTags(match.groupValues[1])
            if (content.length > 20) blocks.add(CodeBlock(null, content))
        }
        return blocks
    }

    private fun extractImages(html: String, baseUrl: String): List<ImageInfo> {
        val images = mutableListOf<ImageInfo>()
        Regex("""<img[^>]+src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val src = resolveUrl(match.groupValues[1], baseUrl)
            images.add(ImageInfo(src, "", 0, 0))
        }
        return images
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
        sb.append("## Summary\n${parsed.bodyText.take(500)}...\n\n")
        sb.append("## Links (${parsed.links.size})\n")
        parsed.links.take(20).forEach { sb.append("- [${it.text}](${it.href})\n") }
        sb.append("\n")
        if (parsed.codeBlocks.isNotEmpty()) {
            sb.append("## Code Blocks (${parsed.codeBlocks.size})\n")
            parsed.codeBlocks.take(5).forEach { block ->
                sb.append("```\n${block.code.take(200)}\n```\n\n")
            }
        }
        return sb.toString()
    }
}
