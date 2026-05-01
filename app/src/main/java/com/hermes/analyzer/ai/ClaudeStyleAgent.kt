package com.hermes.analyzer.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClaudeStyleAgent(private val context: Context) {
    private val engine = UnifiedAIEngine(context)
    suspend fun runAgent(query: String, filePath: String? = null, maxSteps: Int = 10): String = withContext(Dispatchers.IO) {
        engine.process(query, filePath)
    }
}
