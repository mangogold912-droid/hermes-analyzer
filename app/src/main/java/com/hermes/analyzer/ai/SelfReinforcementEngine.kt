package com.hermes.analyzer.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SelfReinforcementEngine - 자체 강화학습 엔진
 *
 * Core concepts:
 * 1. Reward/Penalty tracking per action
 * 2. Strategy weight optimization over time
 * 3. Adaptive strategy selection based on history
 * 4. Self-performance feedback loop
 * 5. Meta-learning: learns HOW to learn better
 *
 * All data stored locally in JSON files.
 */
class SelfReinforcementEngine(private val context: Context) {

    companion object {
        private const val TAG = "SelfReinforcement"
        private const val REWARD_LOG_FILE = "reward_log.json"
        private const val STRATEGY_FILE = "strategies.json"
        private const val META_FILE = "meta_learning.json"
        private const val MAX_REWARD_ENTRIES = 2000
        private const val DECAY_FACTOR = 0.95 // Older rewards matter less
        private const val LEARNING_RATE = 0.1 // How fast to adjust weights
    }

    private val dataDir = File(context.getExternalFilesDir(null), "ai_learning")
    private val rewardLogFile = File(dataDir, REWARD_LOG_FILE)
    private val strategyFile = File(dataDir, STRATEGY_FILE)
    private val metaFile = File(dataDir, META_FILE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // In-memory strategy weights
    private val strategyWeights = ConcurrentHashMap<String, Double>()
    private val actionSuccessCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val actionTotalCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val actionTimeSums = ConcurrentHashMap<String, AtomicLong>()

    init {
        dataDir.mkdirs()
        loadStrategies()
        loadMetaLearning()
    }

    // ==================== REWARD SYSTEM ====================

    /**
     * Record outcome of an action.
     * @param actionId e.g., "tool_radare2", "plan_decompile", "search_github"
     * @param success true if action achieved its goal
     * @param durationMs time taken
     * @param quality 0.0-1.0 score for output quality (optional)
     * @param metadata extra context
     */
    fun recordOutcome(
        actionId: String,
        success: Boolean,
        durationMs: Long,
        quality: Double = 0.5,
        metadata: Map<String, String> = emptyMap()
    ) {
        val reward = calculateReward(success, durationMs, quality)

        // Update counters
        actionTotalCounts.getOrPut(actionId) { AtomicInteger(0) }.incrementAndGet()
        if (success) {
            actionSuccessCounts.getOrPut(actionId) { AtomicInteger(0) }.incrementAndGet()
        }
        actionTimeSums.getOrPut(actionId) { AtomicLong(0) }.addAndGet(durationMs)

        // Update strategy weight
        val currentWeight = strategyWeights.getOrPut(actionId) { 1.0 }
        val newWeight = if (success) {
            currentWeight + LEARNING_RATE * reward
        } else {
            currentWeight * (1 - LEARNING_RATE) // Penalize failure
        }
        strategyWeights[actionId] = newWeight.coerceIn(0.1, 10.0)

        // Log to file
        scope.launch {
            logReward(actionId, success, reward, durationMs, quality, metadata)
        }
    }

    private fun calculateReward(success: Boolean, durationMs: Long, quality: Double): Double {
        val baseReward = if (success) 1.0 else -0.5
        val speedBonus = (1.0 / (1.0 + durationMs / 1000.0)).coerceIn(0.0, 1.0) // Faster = better
        val qualityBonus = quality.coerceIn(0.0, 1.0)
        return baseReward * (0.5 + 0.3 * speedBonus + 0.2 * qualityBonus)
    }

    private fun logReward(
        actionId: String, success: Boolean, reward: Double,
        durationMs: Long, quality: Double, metadata: Map<String, String>
    ) {
        try {
            val log = getRewardLog()
            val entry = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("actionId", actionId)
                put("success", success)
                put("reward", reward)
                put("durationMs", durationMs)
                put("quality", quality)
                put("metadata", JSONObject(metadata))
            }
            log.put(entry)

            // Trim if too large
            if (log.length() > MAX_REWARD_ENTRIES) {
                val trimmed = JSONArray()
                for (i in log.length() - MAX_REWARD_ENTRIES until log.length()) {
                    trimmed.put(log.get(i))
                }
                rewardLogFile.writeText(trimmed.toString())
            } else {
                rewardLogFile.writeText(log.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reward log failed: ${e.message}")
        }
    }

    // ==================== STRATEGY OPTIMIZATION ====================

    /** Get learned weight for an action */
    fun getStrategyWeight(actionId: String): Double {
        return strategyWeights[actionId] ?: 1.0
    }

    /** Rank available strategies by learned performance */
    fun rankStrategies(actionIds: List<String>): List<Pair<String, Double>> {
        return actionIds.map { id ->
            val weight = getStrategyWeight(id)
            val successRate = getSuccessRate(id)
            val avgTime = getAverageTime(id)
            // Combined score: weight * successRate * speed factor
            val speedFactor = 1.0 / (1.0 + avgTime / 5000.0)
            val score = weight * successRate * speedFactor
            id to score
        }.sortedByDescending { it.second }
    }

    /** Get success rate for an action */
    fun getSuccessRate(actionId: String): Double {
        val total = actionTotalCounts[actionId]?.get() ?: 0
        if (total == 0) return 0.5 // Default 50%
        val success = actionSuccessCounts[actionId]?.get() ?: 0
        return success.toDouble() / total
    }

    /** Get average execution time */
    fun getAverageTime(actionId: String): Double {
        val total = actionTotalCounts[actionId]?.get() ?: 0
        if (total == 0) return 5000.0 // Default 5s
        val sum = actionTimeSums[actionId]?.get() ?: 0
        return sum.toDouble() / total
    }

    /** Generate strategy report */
    fun getStrategyReport(): String {
        val sb = StringBuilder()
        sb.append("## Self-Reinforcement Learning Report\n\n")

        val allActions = strategyWeights.keys.sortedByDescending { getStrategyWeight(it) }
        if (allActions.isEmpty()) {
            sb.append("No learning data yet. Perform analysis to start learning.\n")
            return sb.toString()
        }

        sb.append("### Top Performing Strategies\n\n")
        allActions.take(10).forEach { action ->
            val weight = getStrategyWeight(action)
            val rate = getSuccessRate(action)
            val avgTime = getAverageTime(action)
            val total = actionTotalCounts[action]?.get() ?: 0
            sb.append("**$action**\n")
            sb.append("- Weight: %.2f | Success: %.1f%% | Avg Time: %.0fms | Used: %d\n\n"
                .format(weight, rate * 100, avgTime, total))
        }

        // Overall stats
        val totalActions = actionTotalCounts.values.sumOf { it.get() }
        val totalSuccess = actionSuccessCounts.values.sumOf { it.get() }
        val overallRate = if (totalActions > 0) totalSuccess.toDouble() / totalActions else 0.0
        sb.append("### Overall Statistics\n")
        sb.append("- Total actions: $totalActions\n")
        sb.append("- Overall success rate: %.1f%%\n".format(overallRate * 100))
        sb.append("- Unique strategies: ${strategyWeights.size}\n")
        return sb.toString()
    }

    // ==================== META-LEARNING ====================

    /** Meta-learning: analyze own learning patterns and suggest improvements */
    fun metaAnalyze(): MetaInsight {
        val log = getRewardLog()
        if (log.length() < 10) {
            return MetaInsight("Not enough data for meta-analysis", emptyList(), 0.0)
        }

        val insights = mutableListOf<String>()

        // Find best performing action types
        val typePerformance = mutableMapOf<String, MutableList<Double>>()
        for (i in 0 until log.length()) {
            val entry = log.getJSONObject(i)
            val action = entry.getString("actionId")
            val type = action.substringBefore("_", "unknown")
            val reward = entry.getDouble("reward")
            typePerformance.getOrPut(type) { mutableListOf() }.add(reward)
        }

        val bestType = typePerformance.maxByOrNull { it.value.average() }?.key
        val worstType = typePerformance.minByOrNull { it.value.average() }?.key

        if (bestType != null) {
            insights.add("Strategy type '$bestType' shows highest average reward")
        }
        if (worstType != null && worstType != bestType) {
            insights.add("Strategy type '$worstType' underperforms - consider alternatives")
        }

        // Detect learning trend
        val recent = mutableListOf<Double>()
        val older = mutableListOf<Double>()
        val mid = log.length() / 2
        for (i in 0 until log.length()) {
            val entry = log.getJSONObject(i)
            val reward = entry.getDouble("reward")
            if (i >= mid) recent.add(reward) else older.add(reward)
        }

        val recentAvg = if (recent.isNotEmpty()) recent.average() else 0.0
        val olderAvg = if (older.isNotEmpty()) older.average() else 0.0
        val trend = recentAvg - olderAvg

        when {
            trend > 0.1 -> insights.add("Learning trend: IMPROVING (+${"%.2f".format(trend)})")
            trend < -0.1 -> insights.add("Learning trend: DECLINING (${"%.2f".format(trend)}) - need strategy adjustment")
            else -> insights.add("Learning trend: STABLE")
        }

        // Confidence score based on data volume
        val confidence = (log.length().toDouble() / 100).coerceIn(0.0, 1.0)

        return MetaInsight(
            summary = "Meta-analysis complete. ${insights.size} insights generated.",
            insights = insights,
            confidence = confidence
        )
    }

    data class MetaInsight(
        val summary: String,
        val insights: List<String>,
        val confidence: Double
    )

    // ==================== PERSISTENCE ====================

    private fun loadStrategies() {
        try {
            if (strategyFile.exists()) {
                val json = JSONObject(strategyFile.readText())
                json.keys().forEach { key ->
                    strategyWeights[key] = json.getDouble(key)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Strategy load failed: ${e.message}")
        }
    }

    private fun saveStrategies() {
        try {
            val json = JSONObject()
            strategyWeights.forEach { (k, v) -> json.put(k, v) }
            strategyFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Strategy save failed: ${e.message}")
        }
    }

    private fun loadMetaLearning() {
        // Meta learning data loaded on demand
    }

    private fun getRewardLog(): JSONArray {
        return try {
            if (rewardLogFile.exists()) {
                JSONArray(rewardLogFile.readText())
            } else {
                JSONArray()
            }
        } catch (e: Exception) {
            JSONArray()
        }
    }

    /** Reset all learning data */
    fun resetLearning() {
        strategyWeights.clear()
        actionSuccessCounts.clear()
        actionTotalCounts.clear()
        actionTimeSums.clear()
        rewardLogFile.delete()
        strategyFile.delete()
        metaFile.delete()
        Log.i(TAG, "All learning data reset")
    }

    /** Cleanup */
    fun destroy() {
        saveStrategies()
        scope.cancel()
    }
}
