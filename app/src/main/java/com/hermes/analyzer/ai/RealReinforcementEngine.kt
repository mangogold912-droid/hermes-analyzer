package com.hermes.analyzer.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * RealReinforcementEngine
 * 진짜 강화학습: Q-테이블 기반 도구/전략 선택 최적화
 * 매 세션마다 성공/실패 피드백으로 가중치 업데이트
 */
class RealReinforcementEngine(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_rl", Context.MODE_PRIVATE)
    private val TAG = "RealRL"

    // Q-Table: action -> { successCount, failCount, avgTimeMs, qValue }
    data class QAction(
        val name: String,
        var successCount: Int = 0,
        var failCount: Int = 0,
        var totalTimeMs: Long = 0,
        var invocationCount: Int = 0,
        var qValue: Double = 1.0
    )

    data class RLState(
        val taskType: String,
        val fileType: String,
        val complexity: Int
    )

    private val learningRate = 0.3
    private val discountFactor = 0.9
    private val epsilon = 0.1 // Exploration rate

    private val qTable = mutableMapOf<String, QAction>()

    init {
        loadQTable()
    }

    fun selectBestAction(actions: List<String>, state: RLState): String {
        ensureActionsInQTable(actions)

        // Epsilon-greedy exploration
        if (Random.nextDouble() < epsilon) {
            return actions.random()
        }

        // Select action with highest Q-value for this state context
        val contextKey = "${state.taskType}_${state.fileType}"
        val candidates = actions.map { action ->
            val key = "${contextKey}_$action"
            val q = qTable[key] ?: QAction(action)
            action to q.qValue
        }

        return candidates.maxByOrNull { it.second }?.first ?: actions.first()
    }

    fun recordOutcome(action: String, state: RLState, success: Boolean, durationMs: Long) {
        val contextKey = "${state.taskType}_${state.fileType}"
        val key = "${contextKey}_$action"
        val q = qTable.getOrPut(key) { QAction(action) }

        if (success) {
            q.successCount++
        } else {
            q.failCount++
        }
        q.totalTimeMs += durationMs
        q.invocationCount++

        // Q-learning update
        val reward = if (success) 1.0 else -0.5
        val timeBonus = if (durationMs < 5000) 0.2 else 0.0
        val totalReward = reward + timeBonus

        val oldQ = q.qValue
        q.qValue = oldQ + learningRate * (totalReward + discountFactor * oldQ - oldQ)

        saveQTable()
    }

    fun getStrategyReport(): String {
        val sb = StringBuilder()
        sb.append("# Reinforcement Learning Report\n\n")
        sb.append("Total tracked actions: ${qTable.size}\n\n")

        val sorted = qTable.values.sortedByDescending { it.qValue }
        sb.append("## Top Performing Actions\n\n")
        sb.append("| Action | Q-Value | Success | Fail | Avg Time |\n")
        sb.append("|--------|---------|---------|------|----------|\n")
        sorted.take(15).forEach { q ->
            val avgTime = if (q.invocationCount > 0) q.totalTimeMs / q.invocationCount else 0
            sb.append("| ${q.name} | %.3f | ${q.successCount} | ${q.failCount} | ${avgTime}ms |\n".format(q.qValue))
        }
        sb.append("\n## Learning Statistics\n\n")
        val totalSuccess = qTable.values.sumOf { it.successCount }
        val totalFail = qTable.values.sumOf { it.failCount }
        val totalInvocations = totalSuccess + totalFail
        sb.append("- Total invocations: $totalInvocations\n")
        sb.append("- Success rate: ${if (totalInvocations > 0) (totalSuccess * 100 / totalInvocations) else 0}%\n")
        sb.append("- Exploration rate (epsilon): ${(epsilon * 100).toInt()}%\n")
        sb.append("- Learning rate: $learningRate\n")
        return sb.toString()
    }

    fun getActionConfidence(action: String, state: RLState): Double {
        val contextKey = "${state.taskType}_${state.fileType}"
        val key = "${contextKey}_$action"
        return qTable[key]?.qValue ?: 1.0
    }

    fun exportPolicy(): String {
        val obj = JSONObject()
        qTable.forEach { (key, q) ->
            obj.put(key, JSONObject().apply {
                put("name", q.name)
                put("success", q.successCount)
                put("fail", q.failCount)
                put("qValue", q.qValue)
                put("invocations", q.invocationCount)
            })
        }
        return obj.toString(2)
    }

    fun importPolicy(json: String) {
        val obj = JSONObject(json)
        obj.keys().forEach { key ->
            val q = obj.getJSONObject(key)
            qTable[key] = QAction(
                q.getString("name"),
                q.optInt("success", 0),
                q.optInt("fail", 0),
                q.optLong("totalTime", 0),
                q.optInt("invocations", 0),
                q.optDouble("qValue", 1.0)
            )
        }
        saveQTable()
    }

    fun reset() {
        qTable.clear()
        prefs.edit().remove("qtable").apply()
    }

    private fun ensureActionsInQTable(actions: List<String>) {
        actions.forEach { action ->
            qTable.getOrPut(action) { QAction(action) }
        }
    }

    private fun loadQTable() {
        val raw = prefs.getString("qtable", null) ?: return
        try {
            val obj = JSONObject(raw)
            obj.keys().forEach { key ->
                val q = obj.getJSONObject(key)
                qTable[key] = QAction(
                    q.optString("name", key),
                    q.optInt("success", 0),
                    q.optInt("fail", 0),
                    q.optLong("totalTime", 0),
                    q.optInt("invocations", 0),
                    q.optDouble("qValue", 1.0)
                )
            }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun saveQTable() {
        val obj = JSONObject()
        qTable.forEach { (key, q) ->
            obj.put(key, JSONObject().apply {
                put("name", q.name)
                put("success", q.successCount)
                put("fail", q.failCount)
                put("totalTime", q.totalTimeMs)
                put("invocations", q.invocationCount)
                put("qValue", q.qValue)
            })
        }
        prefs.edit().putString("qtable", obj.toString()).apply()
    }
}
