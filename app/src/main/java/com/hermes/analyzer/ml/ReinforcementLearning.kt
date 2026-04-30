package com.hermes.analyzer.ml

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ReinforcementLearning(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_rl", Context.MODE_PRIVATE)

    // 분석 결과에 대한 피드백을 저장하고 AI 성능 개선
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

    // AI 성능 점수 계산
    fun calculatePlatformScore(platformName: String): Float {
        val platformFeedbacks = getAllFeedback().filter { it.platformName == platformName }
        if (platformFeedbacks.isEmpty()) return 0.5f

        val avgRating = platformFeedbacks.map { it.userRating }.average().toFloat()
        val accuracy = platformFeedbacks.count { it.wasAccurate }.toFloat() / platformFeedbacks.size

        return (avgRating / 5f * 0.6f + accuracy * 0.4f).coerceIn(0f, 1f)
    }

    // 최고 성능 AI 순위
    fun getPlatformRankings(): List<Pair<String, Float>> {
        val platforms = listOf("openai", "kimi", "qwen", "gemini", "claude", "deepseek", "ollama", "suprninja")
        return platforms.map { it to calculatePlatformScore(it) }.sortedByDescending { it.second }
    }

    // 프롬프트 개선 제안
    fun generateImprovedPrompt(originalPrompt: String, analysisResult: String): String {
        val topPlatform = getPlatformRankings().firstOrNull()?.first ?: "openai"
        return """
            $originalPrompt

            [이전 분석 결과]
            $analysisResult

            [강화학습 피드백 반영]
            - 최고 성능 플랫폼: $topPlatform
            - 더 정확하고 상세한 분석을 제공하세요
            - 코드의 의도와 실제 동작을 추론하세요
            - 보안 취약점을 구체적으로 지적하세요
        """.trimIndent()
    }
}
