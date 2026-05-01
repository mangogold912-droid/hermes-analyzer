package com.hermes.analyzer.ai

import android.content.Context
import java.io.File

class ContextQueryEngine(private val context: Context) {
    fun indexFile(path: String): String = "Indexed: $path"
    fun query(q: String, filePath: String? = null): List<String> = emptyList()
    fun searchPattern(pattern: String, filePath: String): List<String> = emptyList()
    fun getRelatedStrings(keyword: String, filePath: String): List<String> = emptyList()
    fun getFileOverview(filePath: String): String = "File: $filePath"
    fun clearIndex() {}
}
