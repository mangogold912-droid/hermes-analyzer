package com.hermes.analyzer.network

import android.content.Context
import android.util.Log
import com.hermes.analyzer.browser.BrowserEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * LinkAbsorber
 * 외부 링크에서 콘텐츠를 흡수하고 스킬/지식으로 변환
 */
class LinkAbsorber(context: Context) {
    private val TAG = "LinkAbsorber"
    private val browserEngine = BrowserEngine()
    private val prefs = context.getSharedPreferences("hermes_absorbed", Context.MODE_PRIVATE)

    data class AbsorbedContent(
        val url: String,
        val title: String,
        val rawText: String,
        val extractedCode: List<CodeSnippet>,
        val extractedLinks: List<String>,
        val summary: String,
        val skillsExtracted: List<String>,
        val timestamp: Long
    )

    data class CodeSnippet(
        val language: String,
        val code: String,
        val sourceUrl: String
    )

    fun absorbUrl(url: String): AbsorbedContent {
        Log.i(TAG, "Absorbing: $url")
        val html = fetchUrl(url)
        val parsed = browserEngine.parseHTML(html, url)

        val codeSnippets = mutableListOf<CodeSnippet>()
        parsed.codeBlocks.forEach { block ->
            val lang = detectLanguage(block.code)
            codeSnippets.add(CodeSnippet(lang, block.code, url))
        }

        val skills = extractSkillsFromPage(parsed.bodyText, parsed.codeBlocks)
        val summary = generateSummary(parsed)

        val content = AbsorbedContent(
            url = url,
            title = parsed.title,
            rawText = parsed.bodyText,
            extractedCode = codeSnippets,
            extractedLinks = parsed.links.map { it.href },
            summary = summary,
            skillsExtracted = skills,
            timestamp = System.currentTimeMillis()
        )

        saveAbsorbed(content)
        return content
    }

    fun absorbMultipleUrls(urls: List<String>): List<AbsorbedContent> {
        return urls.map { absorbUrl(it) }
    }

    fun searchAbsorbed(query: String): List<AbsorbedContent> {
        val lower = query.lowercase()
        return getAllAbsorbed().filter { content ->
            content.title.lowercase().contains(lower) ||
            content.rawText.lowercase().contains(lower) ||
            content.summary.lowercase().contains(lower) ||
            content.extractedCode.any { it.code.lowercase().contains(lower) }
        }
    }

    fun getAllAbsorbed(): List<AbsorbedContent> {
        return prefs.all.keys.filter { it.startsWith("absorbed_") }.mapNotNull { key ->
            prefs.getString(key, null)?.let { parseAbsorbed(JSONObject(it)) }
        }.sortedByDescending { it.timestamp }
    }

    fun getAbsorbedByUrl(url: String): AbsorbedContent? {
        val id = url.hashCode().toString()
        val raw = prefs.getString("absorbed_$id", null) ?: return null
        return parseAbsorbed(JSONObject(raw))
    }

    fun deleteAbsorbed(url: String) {
        val id = url.hashCode().toString()
        prefs.edit().remove("absorbed_$id").apply()
    }

    fun getAbsorbedStats(): String {
        val all = getAllAbsorbed()
        val totalCode = all.sumOf { it.extractedCode.size }
        val totalSkills = all.sumOf { it.skillsExtracted.size }
        return """# Absorbed Content Stats
Total URLs: ${all.size}
Total Code Snippets: $totalCode
Total Skills Extracted: $totalSkills
Latest: ${all.firstOrNull()?.title ?: "None"}
"""
    }

    private fun fetchUrl(url: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Hermes-Analyzer/1.0")
            conn.connect()
            val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()
            response
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed: ${e.message}")
            "<html><title>Error</title><body>Failed to load: ${e.message}</body></html>"
        }
    }

    private fun detectLanguage(code: String): String {
        return when {
            code.contains("fun ") && code.contains("val ") -> "kotlin"
            code.contains("def ") -> "python"
            code.contains("function ") || code.contains("const ") || code.contains("let ") -> "javascript"
            code.contains("public class") || code.contains("private void") -> "java"
            code.contains("#include") -> "c"
            code.contains("package main") && code.contains("func ") -> "go"
            code.contains("fn ") -> "rust"
            code.contains("<?php") -> "php"
            else -> "unknown"
        }
    }

    private fun extractSkillsFromPage(text: String, codeBlocks: List<BrowserEngine.CodeBlock>): List<String> {
        val skills = mutableListOf<String>()
        codeBlocks.forEach { block ->
            val lang = detectLanguage(block.code)
            if (lang != "unknown" && block.code.length > 50) {
                skills.add("Extracted $lang function (${block.code.lines().size} lines)")
            }
        }
        // Detect tutorial/learning content
        val tutorialPatterns = listOf("tutorial", "guide", "how to", "step by step", "example", "sample")
        tutorialPatterns.forEach { pattern ->
            if (text.lowercase().contains(pattern)) {
                skills.add("Tutorial content: $pattern")
            }
        }
        return skills
    }

    private fun generateSummary(parsed: BrowserEngine.ParsedPage): String {
        val sb = StringBuilder()
        sb.append("Title: ${parsed.title}\n")
        sb.append("Content length: ${parsed.bodyText.length} chars\n")
        sb.append("Links: ${parsed.links.size}\n")
        sb.append("Tables: ${parsed.tables.size}\n")
        sb.append("Code blocks: ${parsed.codeBlocks.size}\n")
        sb.append("Images: ${parsed.images.size}\n")
        return sb.toString()
    }

    private fun saveAbsorbed(content: AbsorbedContent) {
        val id = content.url.hashCode().toString()
        val obj = JSONObject().apply {
            put("url", content.url)
            put("title", content.title)
            put("rawText", content.rawText.take(10000))
            put("summary", content.summary)
            put("timestamp", content.timestamp)
            put("links", JSONArray(content.extractedLinks.take(50)))
            put("skills", JSONArray(content.skillsExtracted))
            put("code", JSONArray(content.extractedCode.map { JSONObject().apply {
                put("lang", it.language)
                put("code", it.code.take(500))
                put("source", it.sourceUrl)
            }}))
        }
        prefs.edit().putString("absorbed_$id", obj.toString()).apply()
    }

    private fun parseAbsorbed(obj: JSONObject): AbsorbedContent {
        val codeArr = obj.optJSONArray("code") ?: JSONArray()
        val codeSnippets = (0 until codeArr.length()).map {
            val c = codeArr.getJSONObject(it)
            CodeSnippet(c.getString("lang"), c.getString("code"), c.getString("source"))
        }
        val linksArr = obj.optJSONArray("links") ?: JSONArray()
        val links = (0 until linksArr.length()).map { linksArr.getString(it) }
        val skillsArr = obj.optJSONArray("skills") ?: JSONArray()
        val skills = (0 until skillsArr.length()).map { skillsArr.getString(it) }

        return AbsorbedContent(
            obj.getString("url"),
            obj.getString("title"),
            obj.getString("rawText"),
            codeSnippets,
            links,
            obj.getString("summary"),
            skills,
            obj.getLong("timestamp")
        )
    }
}
