package com.hermes.analyzer.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NetworkAnalyzer(private val context: Context) {
    suspend fun analyze(filePath: String): String = withContext(Dispatchers.IO) {
        "Network analysis of $filePath: No hardcoded endpoints detected in stub mode."
    }
}
