package com.hermes.analyzer.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hermes.analyzer.model.*
import com.hermes.analyzer.utils.BinaryAnalyzer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AIMultiEngine(context: Context) {
    companion object {
        private const val TAG = "AIMultiEngine"
        private const val TIMEOUT_MS = 120000L

        val PLATFORMS = listOf(
            AIPlatform("openai", "ChatGPT Pro", baseUrl = "https://api.openai.com/v1", modelName = "gpt-4o"),
            AIPlatform("kimi", "Kimi Code", baseUrl = "https://api.moonshot.cn/v1", modelName = "kimi-latest"),
            AIPlatform("qwen", "Qwen AI", baseUrl = "https://dashscope.aliyuncs.com/api/v1", modelName = "qwen-max"),
            AIPlatform("gemini", "Gemini Pro", baseUrl = "https://generativelanguage.googleapis.com/v1beta", modelName = "gemini-1.5-pro"),
            AIPlatform("claude", "Claude Code", baseUrl = "https://api.anthropic.com/v1", modelName = "claude-3-5-sonnet"),
            AIPlatform("deepseek", "DeepSeek Coder", baseUrl = "https://api.deepseek.com/v1", modelName = "deepseek-chat"),
            AIPlatform("ollama", "Ollama Local", baseUrl = "http://localhost:11434/api", modelName = "llama3.2", isLocal = true),
            AIPlatform("suprninja", "Suprninja", baseUrl = "https://api.suprninja.ai/v1", modelName = "suprninja-v1")
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_ai", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val resultsMutex = Mutex()
    private val allResults = mutableListOf<AIResult>()

    var onProgress: ((Int, String) -> Unit)? = null
    var onResult: ((String, AIResult) -> Unit)? = null
    var onComplete: ((List<AIResult>) -> Unit)? = null

    fun getPlatforms(): List<AIPlatform> {
        return PLATFORMS.map { p ->
            p.copy(
                apiKey = prefs.getString("key_${p.name}", "") ?: "",
                enabled = prefs.getBoolean("enabled_${p.name}", true)
            )
        }
    }

    fun saveKey(platform: String, key: String) {
        prefs.edit().putString("key_$platform", key).apply()
    }

    fun setEnabled(platform: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$platform", enabled).apply()
    }

    // === Main Analysis Entry Point ===
    suspend fun analyzeFile(
        filePath: String,
        fileType: String,
        jobType: String,
        jobId: Long
    ): List<AIResult> = withContext(Dispatchers.IO) {
        allResults.clear()
        val platforms = getPlatforms().filter { it.enabled && (it.apiKey.isNotEmpty() || it.isLocal) }

        if (platforms.isEmpty()) {
            onProgress?.invoke(0, "No AI platforms configured!")
            return@withContext emptyList()
        }

        onProgress?.invoke(5, "Extracting binary features...")

        // Extract local analysis features
        val analyzer = BinaryAnalyzer
        val features = analyzer.extractFeatures(filePath, fileType)

        onProgress?.invoke(10, "Building analysis prompt...")

        val prompt = buildAnalysisPrompt(features, fileType, jobType)

        onProgress?.invoke(15, "Dispatching to ${platforms.size} AI platforms...")

        // Launch all in parallel
        val deferreds = platforms.map { platform ->
            async {
                try {
                    onProgress?.invoke(20, "${platform.displayName}: Starting...")
                    val result = sendToAI(platform, prompt, jobType, jobId)
                    resultsMutex.withLock { allResults.add(result) }
                    onResult?.invoke(platform.name, result)
                    onProgress?.invoke(
                        20 + (80 * allResults.size / platforms.size),
                        "${platform.displayName}: Done (${result.processingTime}ms)"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "${platform.name} failed: ${e.message}")
                    val errorResult = AIResult(
                        jobId = jobId,
                        platformName = platform.name,
                        resultType = jobType,
                        content = createErrorContent(platform.displayName, e.message ?: "Unknown error"),
                        rawText = e.stackTraceToString(),
                        confidence = 0.0f
                    )
                    resultsMutex.withLock { allResults.add(errorResult) }
                    onResult?.invoke(platform.name, errorResult)
                }
            }
        }

        deferreds.awaitAll()

        // Compute consensus
        val consensus = computeConsensus(allResults, jobId, jobType)
        resultsMutex.withLock { allResults.add(consensus) }

        onProgress?.invoke(100, "Analysis complete!")
        onComplete?.invoke(allResults)
        allResults.toList()
    }

    // === AI Chat ===
    suspend fun chat(platformName: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val platform = getPlatforms().find { it.name == platformName } ?: return@withContext "Platform not found"
        if (platform.apiKey.isEmpty() && !platform.isLocal) return@withContext "API key not configured"

        try {
            when (platform.name) {
                "openai" -> chatOpenAI(platform, messages)
                "kimi" -> chatKimi(platform, messages)
                "claude" -> chatClaude(platform, messages)
                "gemini" -> chatGemini(platform, messages)
                "deepseek" -> chatDeepSeek(platform, messages)
                "qwen" -> chatQwen(platform, messages)
                "ollama" -> chatOllama(platform, messages)
                "suprninja" -> chatSuprninja(platform, messages)
                else -> "Unsupported platform"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // === Platform-Specific Chat Implementations ===

    private fun chatOpenAI(platform: AIPlatform, messages: List<ChatMessage>): String {
        val json = JSONObject().apply {
            put("model", platform.modelName)
            put("messages", JSONArray(messages.map { m ->
                JSONObject().apply {
                    put("role", if (m.role == "user") "user" else "assistant")
                    put("content", m.content)
                }
            }))
            put("temperature", 0.2)
            put("max_tokens", 4096)
        }

        val request = Request.Builder()
            .url("${platform.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${platform.apiKey}")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return "Empty response"
        if (!response.isSuccessful) return "Error ${response.code}: $body"

        return JSONObject(body).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun chatKimi(platform: AIPlatform, messages: List<ChatMessage>): String {
        val json = JSONObject().apply {
            put("model", platform.modelName)
            put("messages", JSONArray(messages.map { m ->
                JSONObject().apply {
                    put("role", if (m.role == "user") "user" else "assistant")
                    put("content", m.content)
                }
            }))
            put("temperature", 0.2)
        }

        val request = Request.Builder()
            .url("${platform.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${platform.apiKey}")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return "Empty response"
        if (!response.isSuccessful) return "Error ${response.code}: $body"

        return JSONObject(body).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun chatClaude(platform: AIPlatform, messages: List<ChatMessage>): String {
        val userMessages = messages.filter { it.role == "user" }.map { it.content }
        val lastMessage = userMessages.lastOrNull() ?: return "No message"

        val json = JSONObject().apply {
            put("model", platform.modelName)
            put("max_tokens", 4096)
            put("messages", JSONArray(messages.map { m ->
                JSONObject().apply {
                    put("role", if (m.role == "user") "user" else "assistant")
                    put("content", m.content)
                }
            }))
        }

        val request = Request.Builder()
            .url("${platform.baseUrl}/messages")
            .header("x-api-key", platform.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return "Empty response"
        if (!response.isSuccessful) return "Error ${response.code}: $body"

        return JSONObject(body).getJSONArray("content")
            .getJSONObject(0).getString("text")
    }

    private fun chatGemini(platform: AIPlatform, messages: List<ChatMessage>): String {
        val lastMessage = messages.lastOrNull { it.role == "user" }?.content ?: return "No message"

        val json = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", lastMessage)
                }))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", 4096)
            })
        }

        val request = Request.Builder()
            .url("${platform.baseUrl}/models/${platform.modelName}:generateContent?key=${platform.apiKey}")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return "Empty response"
        if (!response.isSuccessful) return "Error ${response.code}: $body"

        return JSONObject(body).getJSONArray("candidates")
            .getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            .getJSONObject(0).getString("text")
    }

    private fun chatDeepSeek(platform: AIPlatform, messages: List<ChatMessage>): String {
        val json = JSONObject().apply {
            put("model", platform.modelName)
            put("messages", JSONArray(messages.map { m ->
                JSONObject().apply {
                    put("role", if (m.role == "user") "user" else "assistant")
                    put("content", m.content)
                }
            }))
        }

        val request = Request.Builder()
            .url("${platform.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${platform.apiKey}")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return "Empty response"
        return JSONObject(body).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun chatQwen(platform: AIPlatform, messages: List<ChatMessage>): String {
        val lastMessage = messages.lastOrNull { it.role == "user" }?.content ?: return "No message"

        val json = JSONObject().apply {
            put("model", platform.modelName)
            put("input", JSONObject().apply {
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", lastMessage)
                }))
            })
            put("parameters", JSONObject().apply {
                put("result_format", "message")
                put("max_tokens", 4096)
            })
        }

        val request = Request.Builder()
            .url("${platform.baseUrl}/services/aigc/text-generation/generation")
            .header("Authorization", "Bearer ${platform.apiKey}")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return "Empty response"
        return JSONObject(body).getJSONObject("output").getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun chatOllama(platform: AIPlatform, messages: List<ChatMessage>): String {
        val lastMessage = messages.lastOrNull { it.role == "user" }?.content ?: return "No message"

        val json = JSONObject().apply {
            put("model", platform.modelName)
            put("prompt", lastMessage)
            put("stream", false)
            put("options", JSONObject().apply {
                put("temperature", 0.2)
            })
        }

        val request = Request.Builder()
            .url("${platform.baseUrl}/generate")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return "Empty response"
        return JSONObject(body).getString("response")
    }

    private fun chatSuprninja(platform: AIPlatform, messages: List<ChatMessage>): String {
        val json = JSONObject().apply {
            put("model", platform.modelName)
            put("messages", JSONArray(messages.map { m ->
                JSONObject().apply {
                    put("role", if (m.role == "user") "user" else "assistant")
                    put("content", m.content)
                }
            }))
        }

        val request = Request.Builder()
            .url("${platform.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${platform.apiKey}")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return "Empty response"
        return JSONObject(body).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")
    }

    // === Private Helpers ===

    private fun sendToAI(platform: AIPlatform, prompt: String, jobType: String, jobId: Long): AIResult {
        val start = System.currentTimeMillis()

        val systemPrompt = """You are an expert reverse engineer and malware analyst. Analyze the provided binary and return ONLY a JSON object with this exact structure:
{
  "summary": "Executive summary of findings",
  "riskScore": 0-100,
  "detectedPatterns": ["pattern1"],
  "suspiciousApis": ["api1"],
  "obfuscationTechniques": ["technique1"],
  "vulnerabilities": [{"type":"NAME","severity":"critical|high|medium|low","description":"desc","recommendation":"fix"}],
  "behaviorAnalysis": "Behavioral analysis",
  "decompiledInsights": "What the code does functionally",
  "usageContext": "Where/how this binary is likely used"
}""".trimIndent()

        val messages = listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = prompt)
        )

        val rawText = when (platform.name) {
            "openai" -> chatOpenAI(platform, messages)
            "kimi" -> chatKimi(platform, messages)
            "claude" -> chatClaude(platform, messages)
            "gemini" -> chatGemini(platform, messages)
            "deepseek" -> chatDeepSeek(platform, messages)
            "qwen" -> chatQwen(platform, messages)
            "ollama" -> chatOllama(platform, messages)
            "suprninja" -> chatSuprninja(platform, messages)
            else -> "Unsupported platform"
        }

        val content = extractJsonFromText(rawText)
        val processingTime = System.currentTimeMillis() - start

        return AIResult(
            jobId = jobId,
            platformName = platform.name,
            resultType = jobType,
            content = content,
            rawText = rawText,
            confidence = computeConfidence(content),
            processingTime = processingTime
        )
    }

    private fun buildAnalysisPrompt(features: Map<String, Any>, fileType: String, jobType: String): String {
        val sb = StringBuilder()
        sb.appendLine("Analyze this $fileType binary file.")
        sb.appendLine("Analysis type: ${jobType.toUpperCase()}")
        sb.appendLine()

        features["strings"]?.let {
            @Suppress("UNCHECKED_CAST")
            val strings = it as List<String>
            sb.appendLine("=== Strings (${strings.size}) ===")
            strings.take(100).forEach { s -> sb.appendLine("  $s") }
            sb.appendLine()
        }

        features["functions"]?.let {
            @Suppress("UNCHECKED_CAST")
            val funcs = it as List<String>
            sb.appendLine("=== Functions (${funcs.size}) ===")
            funcs.take(50).forEach { f -> sb.appendLine("  $f") }
            sb.appendLine()
        }

        features["imports"]?.let {
            @Suppress("UNCHECKED_CAST")
            val imports = it as List<String>
            sb.appendLine("=== Imports (${imports.size}) ===")
            imports.take(50).forEach { imp -> sb.appendLine("  $imp") }
            sb.appendLine()
        }

        features["elfHeader"]?.let {
            @Suppress("UNCHECKED_CAST")
            val header = it as Map<String, String>
            sb.appendLine("=== ELF Header ===")
            header.forEach { (k, v) -> sb.appendLine("  $k: $v") }
            sb.appendLine()
        }

        features["dexHeader"]?.let {
            @Suppress("UNCHECKED_CAST")
            val header = it as Map<String, Any>
            sb.appendLine("=== DEX Header ===")
            header.forEach { (k, v) -> sb.appendLine("  $k: $v") }
            sb.appendLine()
        }

        features["segments"]?.let {
            @Suppress("UNCHECKED_CAST")
            val segs = it as List<Map<String, String>>
            sb.appendLine("=== Segments (${segs.size}) ===")
            segs.forEach { s -> sb.appendLine("  ${s["name"]}: ${s["start"]} - ${s["end"]} (${s["permissions"]})") }
            sb.appendLine()
        }

        sb.appendLine("Provide a thorough analysis including:")
        sb.appendLine("1. What this binary likely does functionally")
        sb.appendLine("2. Where/how it might be used")
        sb.appendLine("3. Security implications and vulnerabilities")
        sb.appendLine("4. Any suspicious or malicious behavior")
        sb.appendLine("5. Reverse engineering insights")

        return sb.toString()
    }

    private fun extractJsonFromText(text: String): String {
        val jsonRegex = """\{[\s\S]*\}""".toRegex()
        return jsonRegex.find(text)?.value ?: run {
            // Create structured content from raw text
            JSONObject().apply {
                put("summary", text.take(500))
                put("riskScore", 50)
                put("detectedPatterns", JSONArray())
                put("suspiciousApis", JSONArray())
                put("obfuscationTechniques", JSONArray())
                put("vulnerabilities", JSONArray())
                put("behaviorAnalysis", text)
                put("decompiledInsights", "AI returned non-JSON response")
                put("usageContext", "Unknown")
            }.toString()
        }
    }

    private fun computeConfidence(content: String): Float {
        return try {
            val json = JSONObject(content)
            var score = 0.5f
            if (json.optJSONArray("vulnerabilities")?.length() ?: 0 > 0) score += 0.15f
            if (json.optJSONArray("detectedPatterns")?.length() ?: 0 > 0) score += 0.1f
            if (json.optString("summary").length > 50) score += 0.1f
            if (json.optString("behaviorAnalysis").length > 100) score += 0.1f
            if (json.optString("decompiledInsights").length > 50) score += 0.05f
            score.coerceIn(0f, 1f)
        } catch (_: Exception) { 0.3f }
    }

    private fun computeConsensus(results: List<AIResult>, jobId: Long, jobType: String): AIResult {
        if (results.isEmpty()) return AIResult(jobId=jobId, platformName="consensus", resultType=jobType, content="No results", confidence=0f)

        val valid = results.filter { it.confidence > 0.2f }
        val avgConfidence = if (valid.isNotEmpty()) valid.map { it.confidence }.average().toFloat() else 0f

        val allVulns = valid.flatMap { r ->
            try {
                JSONObject(r.content).optJSONArray("vulnerabilities")?.let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }

        val vulnTypes = allVulns.groupBy { it.optString("type") }
        val consensusVulns = JSONArray()
        vulnTypes.filter { it.value.size >= valid.size / 2 }.forEach { (_, vulns) ->
            consensusVulns.put(vulns.first())
        }

        val content = JSONObject().apply {
            put("summary", "Consensus from ${valid.size} AI platforms. Average confidence: ${(avgConfidence * 100).toInt()}%")
            put("riskScore", valid.map { try { JSONObject(it.content).optInt("riskScore", 50) } catch (_: Exception) { 50 } }.average().toInt())
            put("detectedPatterns", valid.flatMap { try { JSONObject(it.content).optJSONArray("detectedPatterns")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList() } catch (_: Exception) { emptyList() } }.distinct())
            put("vulnerabilities", consensusVulns)
            put("behaviorAnalysis", "Multiple AI platforms analyzed this binary. Review individual results for details.")
            put("decompiledInsights", "See individual platform results for decompilation insights.")
            put("usageContext", valid.map { try { JSONObject(it.content).optString("usageContext", "") } catch (_: Exception) { "" } }.firstOrNull { it.isNotEmpty() } ?: "Unknown")
        }.toString()

        return AIResult(
            jobId = jobId,
            platformName = "consensus",
            resultType = jobType,
            content = content,
            rawText = "Consensus: ${valid.size} platforms, ${allVulns.size} total findings, ${consensusVulns.length()} consensus vulns",
            confidence = avgConfidence,
            processingTime = valid.sumOf { it.processingTime }
        )
    }

    private fun createErrorContent(platform: String, error: String): String {
        return JSONObject().apply {
            put("summary", "$platform failed: $error")
            put("riskScore", 0)
            put("detectedPatterns", JSONArray())
            put("suspiciousApis", JSONArray())
            put("vulnerabilities", JSONArray())
            put("behaviorAnalysis", "Error: $error")
            put("decompiledInsights", "")
            put("usageContext", "Unknown")
        }.toString()
    }
}
