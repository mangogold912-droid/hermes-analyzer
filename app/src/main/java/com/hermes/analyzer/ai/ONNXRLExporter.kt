package com.hermes.analyzer.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ONNXRLExporter
 * 강화학습 Q-table을 ONNX 형식으로 변환하고 ONNX Runtime으로 추론
 * 온-디바이스 강화학습 루프: 프롬프트 전략, 도구 선택 가중치를 매 세션 개선
 */
class ONNXRLExporter(private val context: Context) {
    private val TAG = "ONNXRLExporter"
    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_onnx_rl", Context.MODE_PRIVATE)
    private val onnxDir = File(context.getExternalFilesDir(null), "onnx_models").apply { mkdirs() }

    data class RLPolicy(
        val stateFeatures: List<String>,
        val actionWeights: Map<String, Float>,
        val rewardHistory: List<Float>,
        val version: Int
    )

    data class InferenceResult(
        val bestAction: String,
        val confidence: Float,
        val allScores: Map<String, Float>
    )

    /**
     * Q-table 데이터를 간단한 ONNX 바이너리로 변환
     * 실제 ONNX protobuf 대신 JSON 기반 가벼운 형식 사용,
     * ONNX Runtime이 있으면 로드, 없으면 로컬 추론
     */
    fun exportQTableToONNX(qTable: Map<String, RealReinforcementEngine.QAction>, outputName: String = "rl_policy"): File {
        val onnxFile = File(onnxDir, "$outputName.onnx.json")

        // Convert Q-table to policy format
        val policy = JSONObject().apply {
            put("schema_version", 1)
            put("model_type", "rl_policy")
            put("exported_at", System.currentTimeMillis())
            put("input_features", JSONArray(listOf("task_type", "file_type", "complexity", "time_budget")))

            val actions = JSONObject()
            qTable.forEach { (key, qAction) ->
                actions.put(key, JSONObject().apply {
                    put("q_value", qAction.qValue)
                    put("success_rate", if (qAction.invocationCount > 0) qAction.successCount.toFloat() / qAction.invocationCount else 0f)
                    put("avg_time_ms", if (qAction.invocationCount > 0) qAction.totalTimeMs / qAction.invocationCount else 0L)
                    put("invocations", qAction.invocationCount)
                })
            }
            put("actions", actions)

            // Compute normalized policy probabilities
            val qValues = qTable.values.map { it.qValue }
            val maxQ = qValues.maxOrNull() ?: 1.0
            val expQ = qValues.map { kotlin.math.exp(it - maxQ) }
            val sumExp = expQ.sum()
            val probabilities = expQ.map { it / sumExp }

            val probs = JSONObject()
            qTable.keys.toList().forEachIndexed { i, key ->
                probs.put(key, probabilities.getOrElse(i) { 0.0 })
            }
            put("policy_probabilities", probs)
        }

        onnxFile.writeText(policy.toString(2))
        Log.i(TAG, "Exported Q-table to ${onnxFile.absolutePath} (${onnxFile.length()} bytes)")
        return onnxFile
    }

    /**
     * ONNX Runtime이 있으면 사용, 없으면 로컬 softmax 추론
     */
    fun inferBestAction(state: Map<String, Float>, modelFile: File? = null): InferenceResult {
        val policyFile = modelFile ?: getLatestPolicyFile()
        if (policyFile == null || !policyFile.exists()) {
            return InferenceResult("analyze", 0.5f, emptyMap())
        }

        return try {
            // Try ONNX Runtime reflection first
            val onnxResult = tryONNXRuntimeInference(state, policyFile)
            if (onnxResult != null) return onnxResult

            // Fallback: local inference from JSON policy
            localInference(state, policyFile)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            InferenceResult("analyze", 0.5f, emptyMap())
        }
    }

    /**
     * 리플렉션으로 ONNX Runtime 시도
     */
    private fun tryONNXRuntimeInference(state: Map<String, Float>, modelFile: File): InferenceResult? {
        return try {
            // Try to load ONNX Runtime via reflection
            val ortEnvironmentClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val env = ortEnvironmentClass.getMethod("getEnvironment").invoke(null)

            val sessionClass = Class.forName("ai.onnxruntime.OrtSession")
            val sessionOptionsClass = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")

            val options = sessionOptionsClass.getDeclaredConstructor().newInstance()
            val session = sessionClass.getMethod("createSession", String::class.java, sessionOptionsClass)
                .invoke(env, modelFile.absolutePath.replace(".json", ""))

            // If we get here, ONNX Runtime is available
            Log.i(TAG, "ONNX Runtime available for inference")

            // Create input tensor
            val inputTensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
            val floatArray = state.values.toFloatArray()
            val tensor = inputTensorClass.getMethod("createTensor", ortEnvironmentClass, FloatArray::class.java, LongArray::class.java)
                .invoke(null, env, floatArray, longArrayOf(1, floatArray.size.toLong()))

            // Run inference
            val inputs = java.util.HashMap<String, Any>()
            inputs["input"] = tensor
            val results = sessionClass.getMethod("run", Map::class.java).invoke(session, inputs)

            // Parse results
            @Suppress("UNCHECKED_CAST")
            val resultMap = results as Map<String, Any>
            val outputTensor = resultMap["output"]
            val outputArray = outputTensor?.javaClass?.getMethod("getFloatBuffer")?.invoke(outputTensor) as? java.nio.FloatBuffer

            if (outputArray != null) {
                val scores = mutableMapOf<String, Float>()
                val actions = listOf("code", "security", "doc", "web", "test", "binary", "github", "validator")
                actions.forEachIndexed { i, action ->
                    if (i < outputArray.limit()) {
                        scores[action] = outputArray.get(i)
                    }
                }
                val best = scores.maxByOrNull { it.value }
                InferenceResult(best?.key ?: "analyze", best?.value ?: 0.5f, scores)
            } else {
                null
            }
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "ONNX Runtime not available")
            null
        } catch (e: Exception) {
            Log.w(TAG, "ONNX Runtime inference failed: ${e.message}")
            null
        }
    }

    /**
     * 로컬 JSON 기반 추론 (ONNX Runtime 없을 때)
     */
    private fun localInference(state: Map<String, Float>, policyFile: File): InferenceResult {
        val policy = JSONObject(policyFile.readText())
        val probs = policy.getJSONObject("policy_probabilities")

        val scores = mutableMapOf<String, Float>()
        probs.keys().forEach { key ->
            scores[key] = probs.getDouble(key).toFloat()
        }

        // Apply state features as modifiers
        val taskType = state["task_type"] ?: 0f
        val fileType = state["file_type"] ?: 0f
        val complexity = state["complexity"] ?: 0.5f

        // Boost scores based on task type
        val adjustedScores = scores.mapValues { (_, score) ->
            var adjusted = score
            if (taskType > 0.5f) adjusted *= 1.2f
            if (complexity > 0.7f) adjusted *= 0.9f
            adjusted
        }

        val best = adjustedScores.maxByOrNull { it.value }
        return InferenceResult(
            best?.key ?: "analyze",
            (best?.value ?: 0.5f).coerceIn(0f, 1f),
            adjustedScores
        )
    }

    fun loadPolicyFile(name: String): File? {
        val file = File(onnxDir, "$name.onnx.json")
        return if (file.exists()) file else null
    }

    fun getLatestPolicyFile(): File? {
        return onnxDir.listFiles()
            ?.filter { it.name.endsWith(".onnx.json") }
            ?.maxByOrNull { it.lastModified() }
    }

    fun listPolicies(): List<File> {
        return onnxDir.listFiles()?.filter { it.name.endsWith(".onnx.json") }?.toList() ?: emptyList()
    }

    fun deletePolicy(name: String) {
        File(onnxDir, "$name.onnx.json").delete()
    }

    /**
     * 실시간 보상 기록 및 정책 업데이트
     */
    fun recordReward(action: String, reward: Float, state: Map<String, Float>) {
        val history = JSONArray(prefs.getString("reward_history", "[]") ?: "[]")
        history.put(JSONObject().apply {
            put("action", action)
            put("reward", reward)
            put("state", JSONObject(state))
            put("timestamp", System.currentTimeMillis())
        })

        // Keep last 1000 records
        if (history.length() > 1000) {
            val trimmed = JSONArray()
            (history.length() - 1000 until history.length()).forEach { trimmed.put(history.get(it)) }
            prefs.edit().putString("reward_history", trimmed.toString()).apply()
        } else {
            prefs.edit().putString("reward_history", history.toString()).apply()
        }
    }

    fun getRewardStats(): String {
        val history = JSONArray(prefs.getString("reward_history", "[]") ?: "[]")
        val rewards = mutableListOf<Float>()
        val actions = mutableMapOf<String, MutableList<Float>>()

        for (i in 0 until history.length()) {
            val entry = history.getJSONObject(i)
            val r = entry.getDouble("reward").toFloat()
            val a = entry.getString("action")
            rewards.add(r)
            actions.getOrPut(a) { mutableListOf() }.add(r)
        }

        val sb = StringBuilder()
        sb.append("# ONNX RL Reward Statistics\n\n")
        sb.append("Total records: ${rewards.size}\n")
        sb.append("Average reward: ${if (rewards.isNotEmpty()) rewards.average() else 0}\n")
        sb.append("Max reward: ${rewards.maxOrNull() ?: 0}\n")
        sb.append("Min reward: ${rewards.minOrNull() ?: 0}\n\n")

        sb.append("## By Action\n\n")
        actions.forEach { (action, list) ->
            sb.append("- **$action**: ${list.size} records, avg=${"%.3f".format(list.average())}\n")
        }

        return sb.toString()
    }

    /**
     * DQN 스타일 경량화된 정책 네트워크 (로컬 실행)
     */
    fun createMiniDQN(inputSize: Int = 4, hiddenSize: Int = 32, outputSize: Int = 8): MiniDQN {
        return MiniDQN(inputSize, hiddenSize, outputSize)
    }

    /**
     * 경량 DQN 네트워크 (순수 Kotlin, 외부 라이브러리 불필요)
     */
    class MiniDQN(val inputSize: Int, val hiddenSize: Int, val outputSize: Int) {
        private val weights1 = Array(inputSize) { FloatArray(hiddenSize) { (Math.random() * 0.1 - 0.05).toFloat() } }
        private val bias1 = FloatArray(hiddenSize) { 0f }
        private val weights2 = Array(hiddenSize) { FloatArray(outputSize) { (Math.random() * 0.1 - 0.05).toFloat() } }
        private val bias2 = FloatArray(outputSize) { 0f }

        fun forward(input: FloatArray): FloatArray {
            // Layer 1: ReLU
            val hidden = FloatArray(hiddenSize)
            for (i in 0 until hiddenSize) {
                var sum = bias1[i]
                for (j in input.indices) {
                    sum += input[j] * weights1[j][i]
                }
                hidden[i] = kotlin.math.max(0f, sum) // ReLU
            }

            // Layer 2: Linear + Softmax
            val output = FloatArray(outputSize)
            for (i in 0 until outputSize) {
                var sum = bias2[i]
                for (j in 0 until hiddenSize) {
                    sum += hidden[j] * weights2[j][i]
                }
                output[i] = sum
            }

            // Softmax
            val maxVal = output.maxOrNull() ?: 0f
            val exp = output.map { kotlin.math.exp(it - maxVal) }
            val sumExp = exp.sum()
            return exp.map { (it / sumExp).toFloat() }.toFloatArray()
        }

        fun train(input: FloatArray, target: FloatArray, learningRate: Float = 0.01f) {
            val output = forward(input)
            val errors = FloatArray(outputSize) { target[it] - output[it] }

            // Backpropagation (simplified)
            for (i in 0 until hiddenSize) {
                for (j in 0 until outputSize) {
                    weights2[i][j] += learningRate * errors[j] * 1f // Simplified gradient
                }
            }
            for (j in 0 until outputSize) {
                bias2[j] += learningRate * errors[j]
            }
        }

        fun exportWeights(): String {
            val sb = StringBuilder()
            sb.append("MiniDQN_${inputSize}_${hiddenSize}_${outputSize}\n")
            weights1.forEach { row -> sb.append(row.joinToString(",") { "%.6f".format(it) }).append("\n") }
            bias1.forEach { sb.append("%.6f".format(it)).append("\n") }
            weights2.forEach { row -> sb.append(row.joinToString(",") { "%.6f".format(it) }).append("\n") }
            bias2.forEach { sb.append("%.6f".format(it)).append("\n") }
            return sb.toString()
        }
    }
}
