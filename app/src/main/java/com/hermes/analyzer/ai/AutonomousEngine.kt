package com.hermes.analyzer.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AutonomousEngine(private val context: Context) {
    private val engine = UnifiedAIEngine(context)
    suspend fun process(userInput: String, filePath: String? = null): String = withContext(Dispatchers.IO) {
        engine.process(userInput, filePath)
    }
}
