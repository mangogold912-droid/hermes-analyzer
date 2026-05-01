package com.hermes.analyzer.ai

import android.content.Context

class ToolRouter(private val context: Context) {
    fun getToolDefinitions(): List<String> = listOf("analyze_file", "run_shell", "search_strings")
    fun parseToolCalls(output: String): List<String> = emptyList()
    fun executeTool(name: String, args: Map<String, String>): String = "Tool executed"
    fun formatToolResults(results: List<String>): String = results.joinToString("
")
    fun getSystemPromptWithTools(): String = "Available tools: analyze_file, run_shell, search_strings"
}
