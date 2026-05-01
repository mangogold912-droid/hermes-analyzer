package com.hermes.analyzer.ai.agents

import com.hermes.analyzer.ai.AdvancedAIEngine
import kotlinx.coroutines.*

/**
 * Agent 4: Web Scout
 * 전문 역할: 웹 검색, 공식 문서 확인, CVE 정보 조회, 최신 취약점 탐색
 */
class WebScoutAgent(private val engine: AdvancedAIEngine) {
    data class WebResult(
        val query: String,
        val sources: List<WebSource>,
        val summary: String
    )

    data class WebSource(
        val title: String,
        val url: String,
        val snippet: String,
        val reliability: Int
    )

    suspend fun search(query: String): WebResult {
        val webResult = try {
            engine.webSearch(query)
        } catch (e: Exception) {
            "Web search unavailable: ${e.message}"
        }
        
        val sources = extractSources(webResult)
        val summary = if (sources.isNotEmpty()) {
            "Found ${sources.size} sources for query: $query"
        } else {
            "No web results available. Using local knowledge base."
        }
        
        return WebResult(query, sources, summary)
    }

    suspend fun searchCVE(cveId: String): String {
        val result = search("$cveId vulnerability details site:mitre.org OR site:cvedetails.com")
        return if (result.sources.isNotEmpty()) {
            "## $cveId Details\n\n${result.sources.first().snippet}\n\nSources:\n${result.sources.joinToString("\n") { "- [${it.title}](${it.url})" }}"
        } else {
            "CVE information for $cveId could not be retrieved. Check https://cve.mitre.org/cgi-bin/cvename.cgi?name=$cveId"
        }
    }

    suspend fun searchToolDocumentation(toolName: String): String {
        val result = search("$toolName documentation official github usage")
        return if (result.sources.isNotEmpty()) {
            "## $toolName Documentation\n\n${result.sources.take(3).joinToString("\n\n") { "### ${it.title}\n${it.snippet}\nURL: ${it.url}" }}"
        } else {
            "Documentation for $toolName not found via web search. Try visiting https://github.com/search?q=$toolName"
        }
    }

    suspend fun findLatestVulnerability(product: String): String {
        val result = search("$product vulnerability CVE 2026 OR 2025 latest security advisory")
        return result.summary + "\n\n" + result.sources.take(5).joinToString("\n") { "- ${it.title}: ${it.url}" }
    }

    private fun extractSources(raw: String): List<WebSource> {
        val sources = mutableListOf<WebSource>()
        val lines = raw.lines()
        var currentTitle = ""
        var currentUrl = ""
        var currentSnippet = ""
        
        lines.forEach { line ->
            when {
                line.startsWith("Title:") -> currentTitle = line.substringAfter("Title:").trim()
                line.startsWith("URL:") -> currentUrl = line.substringAfter("URL:").trim()
                line.startsWith("Snippet:") -> {
                    currentSnippet = line.substringAfter("Snippet:").trim()
                    if (currentTitle.isNotEmpty() && currentUrl.isNotEmpty()) {
                        sources.add(WebSource(currentTitle, currentUrl, currentSnippet, 70))
                    }
                }
                line.contains("http") && line.contains(" - ") -> {
                    val parts = line.split(" - ", limit = 2)
                    if (parts.size == 2) {
                        sources.add(WebSource(parts[0].trim(), parts[0].trim(), parts[1].trim(), 50))
                    }
                }
            }
        }
        return sources
    }
}
