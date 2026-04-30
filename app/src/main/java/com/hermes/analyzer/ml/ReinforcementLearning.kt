package com.hermes.analyzer.ml

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ReinforcementLearning(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_rl", Context.MODE_PRIVATE)

    // Store feedback on analysis results for AI improvement
    data class Feedback(
        val platformName: String,
        val resultType: String,
        val wasAccurate: Boolean,
        val userRating: Int, // 1-5
        val correction: String = ""
    )

    fun saveFeedback(feedback: Feedback) {
        val key = "feedback_${feedback.platformName}_${System.currentTimeMillis()}"
        val json = JSONObject().apply {
            put("platform", feedback.platformName)
            put("type", feedback.resultType)
            put("accurate", feedback.wasAccurate)
            put("rating", feedback.userRating)
            put("correction", feedback.correction)
            put("timestamp", System.currentTimeMillis())
        }
        prefs.edit().putString(key, json.toString()).apply()
    }

    fun getAllFeedback(): List<Feedback> {
        val feedbacks = mutableListOf<Feedback>()
        for (key in prefs.all.keys) {
            if (key.startsWith("feedback_")) {
                val json = JSONObject(prefs.getString(key, "") ?: continue)
                feedbacks.add(Feedback(
                    platformName = json.getString("platform"),
                    resultType = json.getString("type"),
                    wasAccurate = json.getBoolean("accurate"),
                    userRating = json.getInt("rating"),
                    correction = json.optString("correction", "")
                ))
            }
        }
        return feedbacks.sortedByDescending { it.userRating }
    }

    // Calculate AI performance score
    fun calculatePlatformScore(platformName: String): Float {
        val platformFeedbacks = getAllFeedback().filter { it.platformName == platformName }
        if (platformFeedbacks.isEmpty()) return 0.5f

        val avgRating = platformFeedbacks.map { it.userRating }.average().toFloat()
        val accuracy = platformFeedbacks.count { it.wasAccurate }.toFloat() / platformFeedbacks.size

        return (avgRating / 5f * 0.6f + accuracy * 0.4f).coerceIn(0f, 1f)
    }

    // Top performing AI ranking
    fun getPlatformRankings(): List<Pair<String, Float>> {
        val platforms = listOf("openai", "kimi", "qwen", "gemini", "claude", "deepseek", "ollama", "suprninja")
        return platforms.map { it to calculatePlatformScore(it) }.sortedByDescending { it.second }
    }

    // Prompt improvement suggestions
    fun generateImprovedPrompt(originalPrompt: String, analysisResult: String): String {
        val topPlatform = getPlatformRankings().firstOrNull()?.first ?: "openai"
        return """
            $originalPrompt

            [Previous analysis results]
            $analysisResult

            [RL feedback applied]
            - Top platform: $topPlatform
            - Provide more accurate and detailed analysis
            - Infer code intent vs actual behavior
            - Point out specific security vulnerabilities
        """.trimIndent()
    }
}
