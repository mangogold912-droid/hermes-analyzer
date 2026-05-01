package com.hermes.analyzer.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AutonomousEngine - stub that delegates to UnifiedAIEngine
 */
class AutonomousEngine(private val context: Context) {
    private val engine = UnifiedAIEngine(context)

    suspend fun process(userInput: String, filePath: String? = null): String = withContext(Dispatchers.IO) {
        engine.process(userInput, filePath)
    }

    fun parseIntent(text: String) = "analyze"
    fun createActionPlan(intent: String, filePath: String?) = listOf<String>()
    fun resetSession() {}
    fun getCurrentFile(): String? = null
}
