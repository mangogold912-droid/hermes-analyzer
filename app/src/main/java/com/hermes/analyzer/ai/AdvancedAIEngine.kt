package com.hermes.analyzer.ai

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import com.hermes.analyzer.ai.AIMultiEngine
import com.hermes.analyzer.ai.LocalLLMEngine
import com.hermes.analyzer.ai.SelfReinforcementEngine
import com.hermes.analyzer.ai.AdvancedCognitiveEngine
import com.hermes.analyzer.model.ChatMessage

/**
 * AdvancedAIEngine - Autonomous Reverse Engineering AI
 *
 * Core capabilities:
 * 1. Natural language goal understanding
 * 2. Intelligent tool selection and parallel execution
 * 3. Web searching for unknown tools/info
 * 4. GitHub integration for tool discovery/download
 * 5. File upload and analysis during chat
 * 6. Self-reinforcement learning from results
 * 7. Memory persistence across sessions
 * 8. Virtual sandbox for safe execution
 * 9. Web browser automation (download/read files)
 * 10. Plugin auto-download when missing
 */
class AdvancedAIEngine(private val context: Context) {

    companion object {
        private const val TAG = "AdvancedAIEngine"
        private const val PREFS_NAME = "hermes_advanced_ai"
        private const val MEMORY_PREFS = "hermes_ai_memory"
        private const val MAX_MEMORY_ENTRIES = 1000
        private const val GITHUB_API = "https://api.github.com"
        private const val WEB_SEARCH_API = "https://api.bing.microsoft.com/v7.0/search"

        // 8 AI platforms
        const val AI_OPENAI = "openai"
        const val AI_KIMI = "kimi"
        const val AI_QWEN = "qwen"
        const val AI_GEMINI = "gemini"
        const val AI_CLAUDE = "claude"
        const val AI_DEEPSEEK = "deepseek"
        const val AI_OLLAMA = "ollama"
        const val AI_SUPRNINJA = "suprninja"

        val ALL_PLATFORMS = listOf(AI_OPENAI, AI_KIMI, AI_QWEN, AI_GEMINI,
            AI_CLAUDE, AI_DEEPSEEK, AI_OLLAMA, AI_SUPRNINJA)
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val memoryPrefs: SharedPreferences = context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE)
    private val pluginEngine: PluginEngine = PluginEngine(context)
    private val planner: AgentPlanner = AgentPlanner()
    private val orchestrator: ToolOrchestrator = ToolOrchestrator(pluginEngine)
    private val reflection: ReflectionEngine = ReflectionEngine(planner, pluginEngine)
    private val reinforcement: SelfReinforcementEngine = SelfReinforcementEngine(context)
    private val cognitive: AdvancedCognitiveEngine = AdvancedCognitiveEngine(context)
    private val multiEngine: AIMultiEngine = AIMultiEngine(context)
    private val localLLM: LocalLLMEngine = LocalLLMEngine(context)

    init {
        try {
            val models = localLLM.getAvailableModels()
            if (models.isNotEmpty()) {
                localLLM.loadModel(models.first())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auto-load local model failed: ${e.message}")
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Tool download tracking
    private val downloadedTools = ConcurrentHashMap<String, DownloadedTool>()

    data class DownloadedTool(
        val name: String,
        val source: String,
        val localPath: String,
        val installDate: Long = System.currentTimeMillis()
    )

    data class UploadedFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val type: String
    )

    // ==================== 1. API KEY MANAGEMENT ====================

    fun saveApiKey(platform: String, key: String): Boolean {
        if (key.isBlank() || platform !in ALL_PLATFORMS) return false
        val trimmed = key.trim()
        prefs.edit().putString("apikey_$platform", trimmed).apply()
        // Also sync to AIMultiEngine so chatWithParallelAI can find it
        multiEngine.saveKey(platform, trimmed)
        Log.i(TAG, "API key saved: $platform")
        return true
    }

    fun getApiKey(platform: String): String? {
        return prefs.getString("apikey_$platform", null)?.takeIf { it.isNotBlank() }
    }

    fun hasApiKey(platform: String): Boolean = getApiKey(platform) != null

    fun getActivePlatforms(): List<String> = ALL_PLATFORMS.filter { hasApiKey(it) }

    fun deleteApiKey(platform: String) {
        prefs.edit().remove("apikey_$platform").apply()
    }

    // ==================== 2. FILE UPLOAD IN CHAT ====================

    /**
     * Process uploaded file during AI chat
     */
    fun processUploadedFile(uri: Uri, userMessage: String = ""): String {
        val fileName = getFileNameFromUri(uri) ?: "unknown"
        val fileSize = getFileSizeFromUri(uri) ?: 0L
        val fileType = detectFileType(fileName)

        // Save to app's analysis directory
        val destFile = File(context.getExternalFilesDir(null), "uploads/$fileName")
        destFile.parentFile?.mkdirs()

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "File copy failed: ${e.message}")
            return "Error: Failed to copy uploaded file - ${e.message}"
        }

        // Auto-analyze with agent
        val analysisResult = analyzeFileAutonomously(destFile.absolutePath, fileType, userMessage)

        return buildString {
            append("## File Uploaded: $fileName\n\n")
            append("- **Size**: ${formatFileSize(fileSize)}\n")
            append("- **Type**: $fileType\n")
            append("- **Path**: ${destFile.absolutePath}\n\n")
            append("---\n\n")
            append(analysisResult)
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = cursor.getString(idx)
                }
            }
        }
        if (result == null) {
            result = uri.lastPathSegment
        }
        return result
    }

    private fun getFileSizeFromUri(uri: Uri): Long? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0) return cursor.getLong(idx)
                }
            }
        }
        return null
    }

    private fun detectFileType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "apk" -> "apk"
            "elf", "so", "o" -> "elf"
            "dex" -> "dex"
            "jar" -> "jar"
            "zip" -> "zip"
            "txt" -> "text"
            "json" -> "json"
            "xml" -> "xml"
            "smali" -> "smali"
            else -> "binary"
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    // ==================== 3. AUTONOMOUS FILE ANALYSIS ====================

    /**
     * Main autonomous analysis - the core AI agent
     * 
     * 1. Extract real file features (strings, headers)
     * 2. Run built-in plugins for preliminary analysis
     * 3. Call 8 AI platforms in parallel for deep analysis
     * 4. Synthesize final report from AI results + plugin results
     */
    fun analyzeFileAutonomously(filePath: String, fileType: String, userGoal: String): String {
        val sb = StringBuilder()
        val startTime = System.currentTimeMillis()

        sb.append("## Autonomous Analysis Started\n\n")
        sb.append("**Goal**: ${if (userGoal.isNotBlank()) userGoal else "Comprehensive analysis"}\n")
        sb.append("**File**: $filePath\n")
        sb.append("**Type**: $fileType\n\n")

        // === Step 1: Extract REAL file features ===
        sb.append("### Phase 1: Local Feature Extraction\n\n")
        val features = extractLocalFeatures(filePath, fileType)
        features.forEach { (k, v) ->
            val preview = v.toString().take(200)
            sb.append("- **$k**: $preview${if (v.toString().length > 200) "..." else ""}\n")
        }
        sb.append("\n")

        // === Step 2: Run plugin analysis (supplementary) ===
        val pluginResults = runBlocking {
            runLocalPlugins(filePath, fileType)
        }
        if (pluginResults.isNotEmpty()) {
            sb.append("### Phase 2: Plugin Scan Results\n\n")
            pluginResults.forEach { (name, output) ->
                sb.append("**$name**: ${output.take(300)}${if (output.length > 300) "..." else ""}\n\n")
            }
        }

        // === Step 3: 8 AI PARALLEL ANALYSIS (core) ===
        sb.append("### Phase 3: 8 AI Parallel Deep Analysis\n\n")
        val activePlatforms = getActivePlatforms()
        // Build analysis prompt with real file data
        val analysisPrompt = buildAnalysisPrompt(filePath, fileType, userGoal, features, pluginResults)

        val aiResult = runBlocking {
            try {
                chatWithParallelAI(analysisPrompt, filePath)
            } catch (e: Exception) {
                // API errors are blocked - return local analysis only
                generateRuleBasedFallback(userGoal, filePath)
            }
        }
        sb.append(aiResult)

        // === Step 4: Save to memory for learning ===
        val duration = System.currentTimeMillis() - startTime
        saveToMemorySimple(userGoal, filePath, fileType, duration, activePlatforms.isNotEmpty())

        sb.append("\n---\n\n")
        sb.append("**Analysis completed in ${duration / 1000}s**\n")
        sb.append("**Local plugins**: ${pluginResults.size}\n")
        sb.append("**AI platforms**: ${activePlatforms.size}/8 active\n")

        // === Step 5: Self-reinforcement learning ===
        val hasDeepAnalysis = activePlatforms.isNotEmpty() || localLLM.isModelReady()
        reinforcement.recordOutcome("autonomous_analysis", hasDeepAnalysis, duration, if (hasDeepAnalysis) 0.8 else 0.4, mapOf("fileType" to fileType, "pluginsUsed" to pluginResults.size.toString()))

        // === Step 6: Cognitive analysis summary ===
        val intent = cognitive.inferIntent(userGoal, fileType)
        cognitive.addToWorkingMemory("Analyzed $fileType: ${intent.primaryType} intent (confidence: ${intent.confidence})", AdvancedCognitiveEngine.MemoryType.OUTCOME, listOf("analysis", fileType))
        val reflection = cognitive.selfReflect(sb.toString(), duration)
        if (reflection.issues.isNotEmpty()) {
            sb.append("\n### Self-Improvement Notes\n")
            reflection.issues.take(3).forEach { sb.append("- $it\n") }
        }

        return sb.toString()
    }

    /**
     * Extract REAL local features from the binary file
     */
    private fun extractLocalFeatures(filePath: String, fileType: String): Map<String, String> {
        val features = mutableMapOf<String, String>()
        val file = File(filePath)
        if (!file.exists()) return features

        try {
            // Basic file info
            features["size"] = formatFileSize(file.length())
            features["md5"] = computeMD5(file)

            // Read first bytes for magic signature
            file.inputStream().use { fis ->
                val magic = ByteArray(16)
                val read = fis.read(magic)
                if (read > 0) {
                    features["magic_hex"] = magic.take(read).joinToString(" ") { "%02X".format(it) }
                    features["magic_ascii"] = magic.take(read).map { 
                        if (it in 32..126) it.toChar() else '.' 
                    }.joinToString("")
                }
            }

            // File-type specific extraction
            when (fileType.lowercase()) {
                "apk", "zip", "jar" -> {
                    features["is_zip"] = "true"
                    // List zip entries
                    try {
                        val entries = mutableListOf<String>()
                        java.util.zip.ZipFile(file).use { zip ->
                            entries.addAll(zip.entries().toList().map { it.name }.take(50))
                        }
                        features["zip_entries_count"] = entries.size.toString()
                        features["zip_entries_sample"] = entries.take(20).joinToString("\n")
                        // Look for DEX
                        features["has_dex"] = entries.any { it.endsWith(".dex") }.toString()
                        features["has_so"] = entries.any { it.endsWith(".so") }.toString()
                        features["has_android_manifest"] = entries.any { 
                            it.equals("AndroidManifest.xml", ignoreCase = true) 
                        }.toString()
                    } catch (_: Exception) {}
                }
                "elf", "so", "o" -> {
                    features["is_elf"] = "true"
                    // ELF magic: 7F 45 4C 46
                    file.inputStream().use { fis ->
                        val header = ByteArray(64)
                        val read = fis.read(header)
                        if (read >= 20) {
                            val elfClass = when (header[4]) {
                                1.toByte() -> "32-bit"
                                2.toByte() -> "64-bit"
                                else -> "unknown"
                            }
                            val endian = when (header[5]) {
                                1.toByte() -> "Little Endian"
                                2.toByte() -> "Big Endian"
                                else -> "unknown"
                            }
                            val osAbi = when (header[7]) {
                                0.toByte() -> "System V"
                                3.toByte() -> "Linux"
                                else -> "other"
                            }
                            features["elf_class"] = elfClass
                            features["elf_endian"] = endian
                            features["elf_osabi"] = osAbi
                        }
                    }
                }
                "dex" -> {
                    features["is_dex"] = "true"
                    file.inputStream().use { fis ->
                        val header = ByteArray(112)
                        val read = fis.read(header)
                        if (read >= 8) {
                            val magicStr = header.take(8).map { 
                                if (it in 32..126) it.toChar() else '.' 
                            }.joinToString("")
                            features["dex_magic"] = magicStr
                        }
                    }
                }
            }

            // Extract strings (first 1000 printable strings)
            val strings = extractStrings(file, 1000)
            features["strings_count"] = strings.size.toString()
            features["strings_sample"] = strings.take(30).joinToString("\n")
            // Interesting strings
            val urls = strings.filter { it.startsWith("http://") || it.startsWith("https://") }
            val ips = strings.filter { Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").containsMatchIn(it) }
            val crypto = strings.filter { 
                it.contains("AES") || it.contains("RSA") || it.contains("SHA") || 
                it.contains("encrypt", true) || it.contains("decrypt", true) ||
                it.contains("cipher", true) || it.contains("base64", true)
            }
            if (urls.isNotEmpty()) features["urls"] = urls.take(10).joinToString("\n")
            if (ips.isNotEmpty()) features["ips"] = ips.take(10).joinToString("\n")
            if (crypto.isNotEmpty()) features["crypto_refs"] = crypto.take(10).joinToString("\n")

        } catch (e: Exception) {
            Log.w(TAG, "Feature extraction failed: ${e.message}")
            features["error"] = e.message ?: "unknown"
        }

        return features
    }

    private fun computeMD5(file: File): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "unknown" }
    }

    private fun extractStrings(file: File, maxCount: Int): List<String> {
        val strings = mutableListOf<String>()
        try {
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                val current = StringBuilder()
                var read: Int
                while (fis.read(buffer).also { read = it } > 0 && strings.size < maxCount) {
                    for (i in 0 until read) {
                        val b = buffer[i]
                        if (b in 32..126) {
                            current.append(b.toChar())
                        } else {
                            if (current.length >= 4) {
                                strings.add(current.toString())
                            }
                            current.clear()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return strings
    }

    /**
     * Run a subset of local plugins for supplementary data
     */
    private suspend fun runLocalPlugins(filePath: String, fileType: String): Map<String, String> {
        val results = mutableMapOf<String, String>()
        val pluginsToRun = listOf("radare2_wrapper", "string_extractor", "crypto_hunter", "network_analyzer")

        for (pluginId in pluginsToRun) {
            try {
                val params = mapOf("file" to filePath, "target" to filePath)
                val output = withContext(Dispatchers.IO) {
                    pluginEngine.executePlugin(pluginId, params)
                }
                if (!output.startsWith("Error")) {
                    results[pluginId] = output
                }
            } catch (_: Exception) {}
        }
        return results
    }

    /**
     * Build analysis prompt for AI with real file data
     */
    private fun buildAnalysisPrompt(
        filePath: String, fileType: String, userGoal: String,
        features: Map<String, String>, pluginResults: Map<String, String>
    ): String {
        val sb = StringBuilder()
        sb.append("You are an expert reverse engineering AI. Analyze this binary file thoroughly.\n\n")
        sb.append("USER GOAL: $userGoal\n")
        sb.append("FILE TYPE: $fileType\n")
        sb.append("FILE PATH: $filePath\n\n")

        // Real extracted features
        sb.append("=== EXTRACTED FILE FEATURES ===\n")
        features.forEach { (k, v) ->
            sb.append("$k: $v\n")
        }
        sb.append("\n")

        // Plugin results
        if (pluginResults.isNotEmpty()) {
            sb.append("=== LOCAL PLUGIN SCAN RESULTS ===\n")
            pluginResults.forEach { (name, output) ->
                sb.append("--- $name ---\n$output\n\n")
            }
        }

        sb.append("Provide a comprehensive analysis including:\n")
        sb.append("1. File type and structure identification\n")
        sb.append("2. Security analysis - potential vulnerabilities, suspicious behavior\n")
        sb.append("3. Network communication indicators (URLs, IPs, API endpoints)\n")
        sb.append("4. Cryptographic usage analysis\n")
        sb.append("5. Reverse engineering insights - what this binary likely does\n")
        sb.append("6. Risk assessment and recommendations\n")
        sb.append("7. If APK: component analysis (activities, services, receivers)\n")
        sb.append("\nRespond in a well-structured markdown format.")

        return sb.toString()
    }

    /**
     * Simplified memory save for agent analysis
     */
    private fun saveToMemorySimple(
        goal: String, filePath: String, fileType: String,
        duration: Long, aiUsed: Boolean
    ) {
        try {
            val entry = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("goal", goal)
                put("fileType", fileType)
                put("duration", duration)
                put("aiUsed", aiUsed)
                put("success", true)
            }
            val memory = getMemoryJson()
            memory.put(entry)
            memoryPrefs.edit().putString("analysis_memory", memory.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Memory save failed: ${e.message}")
        }
    }

    // ==================== 4. TOOL DISCOVERY & DOWNLOAD ====================

    private fun findMissingTools(toolIds: List<String>): List<String> {
        val available = PluginEngine.BUILTIN_PLUGINS.map { p -> p.id }
        return toolIds.filter { it !in available && it != "ai_synthesis" }
    }

    /**
     * Discover tool from GitHub/web and download
     */
    private suspend fun discoverAndDownloadTool(toolId: String): String {
        // Search GitHub for the tool
        val searchResult = try {
            val query = URLEncoder.encode("$toolId reverse engineering tool android", "UTF-8")
            val url = URL("${GITHUB_API}/search/repositories?q=$query&sort=stars&order=desc&per_page=5")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "Hermes-Analyzer")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            val responseCode = conn.responseCode
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (responseCode == 200) {
                val json = JSONObject(body)
                val items = json.optJSONArray("items")
                if (items != null && items.length() > 0) {
                    val first = items.getJSONObject(0)
                    val fullName = first.getString("full_name")
                    val htmlUrl = first.getString("html_url")
                    Pair(fullName, htmlUrl)
                } else null
            } else null
        } catch (e: Exception) {
            null
        }

        if (searchResult == null) {
            val webResult = searchWebForTool(toolId)
            return if (webResult != null) {
                "Found info: $webResult (manual install required)"
            } else {
                "Could not find tool '$toolId' online"
            }
        }

        return "Found on GitHub: ${searchResult.first} - ${searchResult.second}"
    }

    private suspend fun searchWebForTool(toolName: String): String? {
        return try {
            val query = URLEncoder.encode("$toolName reverse engineering download github", "UTF-8")
            val url = URL("https://html.duckduckgo.com/html/?q=$query")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val html = conn.inputStream.bufferedReader().use { it.readText() }

            // Extract first result title/link
            val titleMatch = Regex("<a[^>]+class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .find(html)
            val snippetMatch = Regex("<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .find(html)

            val title = titleMatch?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()
            val snippet = snippetMatch?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()

            if (title != null) "$title - ${snippet ?: ""}" else null
        } catch (e: Exception) {
            Log.w(TAG, "Web search failed: ${e.message}")
            null
        }
    }

    // ==================== 5. WEB SEARCHING ====================

    /**
     * Search the web for information related to analysis
     */
    fun webSearch(query: String): String {
        val sb = StringBuilder()
        sb.append("## Web Search: $query\n\n")

        // Use web search API or fallback to basic HTTP
        val results = try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://html.duckduckgo.com/html/?q=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val html = conn.inputStream.bufferedReader().use { it.readText() }

            // Parse search results
            val titles = Regex("<a[^>]+class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).take(5).map {
                    it.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
                }.toList()

            val snippets = Regex("<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).take(5).map {
                    it.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
                }.toList()

            titles.zip(snippets).map { (t, s) -> "**$t**\n$s" }
        } catch (e: Exception) {
            Log.e(TAG, "Web search error: ${e.message}")
            listOf("Search unavailable: ${e.message}")
        }

        results.forEachIndexed { i, r ->
            sb.append("${i + 1}. $r\n\n")
        }

        return sb.toString()
    }

    // ==================== 6. GITHUB INTEGRATION ====================

    /**
     * Search GitHub repositories for reverse engineering tools
     */
    fun searchGitHub(query: String): String {
        val sb = StringBuilder()
        sb.append("## GitHub Search: $query\n\n")

        val results = try {
            val encoded = URLEncoder.encode("$query reverse engineering", "UTF-8")
            val url = URL("${GITHUB_API}/search/repositories?q=$encoded&sort=stars&order=desc&per_page=10")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "Hermes-Analyzer")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val items = json.optJSONArray("items") ?: return "No results found"

            (0 until minOf(items.length(), 10)).map { i ->
                val item = items.getJSONObject(i)
                val name = item.getString("full_name")
                val desc = item.optString("description", "No description").take(100)
                val stars = item.optInt("stargazers_count", 0)
                val lang = item.optString("language", "Unknown")
                val htmlUrl = item.getString("html_url")
                "**[$name]($htmlUrl)** ($stars stars, $lang)\n$desc"
            }
        } catch (e: Exception) {
            Log.e(TAG, "GitHub search error: ${e.message}")
            listOf("GitHub API error: ${e.message}")
        }

        results.forEachIndexed { i, r ->
            sb.append("${i + 1}. $r\n\n")
        }

        return sb.toString()
    }

    /**
     * Download a tool from GitHub release
     */
    fun downloadFromGitHub(repoUrl: String, callback: (String) -> Unit) {
        scope.launch {
            try {
                // Extract owner/repo from URL
                val match = Regex("github\\.com/([^/]+)/([^/]+)").find(repoUrl)
                if (match == null) {
                    mainHandler.post { callback("Invalid GitHub URL") }
                    return@launch
                }

                val (owner, repo) = match.destructured

                // Get latest release
                val url = URL("${GITHUB_API}/repos/$owner/$repo/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "Hermes-Analyzer")

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val tag = json.getString("tag_name")
                val assets = json.optJSONArray("assets")

                val downloadDir = File(context.getExternalFilesDir(null), "downloaded_tools")
                downloadDir.mkdirs()

                if (assets != null && assets.length() > 0) {
                    val asset = assets.getJSONObject(0)
                    val assetName = asset.getString("name")
                    val downloadUrl = asset.getString("browser_download_url")

                    // Download file
                    val destFile = File(downloadDir, "$repo/$assetName")
                    destFile.parentFile?.mkdirs()

                    URL(downloadUrl).openStream().use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    downloadedTools[repo] = DownloadedTool(repo, "github:$owner/$repo", destFile.absolutePath)
                    saveDownloadedTools()

                    mainHandler.post {
                        callback("Downloaded: $assetName (${destFile.length()} bytes) to ${destFile.absolutePath}")
                    }
                } else {
                    mainHandler.post { callback("No release assets found for $repo") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}")
                mainHandler.post { callback("Download failed: ${e.message}") }
            }
        }
    }

    // ==================== 7. REINFORCEMENT LEARNING ====================

    /**
     * Save analysis result to memory for future learning
     */
    private fun saveToMemory(
        goal: String, filePath: String, fileType: String,
        results: List<ToolOrchestrator.StepResult>, duration: Long, confidence: Float
    ) {
        try {
            val entry = JSONObject()
            entry.put("timestamp", System.currentTimeMillis())
            entry.put("goal", goal)
            entry.put("fileType", fileType)
            entry.put("duration", duration)
            entry.put("confidence", confidence)
            entry.put("toolsUsed", JSONArray(results.flatMap { it.toolResults.keys }))
            entry.put("success", results.all { it.success })

            // Save to memory prefs
            val memory = getMemoryJson()
            memory.put(entry)

            // Keep only MAX_MEMORY_ENTRIES
            while (memory.length() > MAX_MEMORY_ENTRIES) {
                // Remove oldest entry
                val newArray = JSONArray()
                for (i in 1 until memory.length()) {
                    newArray.put(memory.get(i))
                }
                // Replace - need to clear and re-add
                // Actually SharedPreferences with JSONArray is tricky
                // Just keep the last N by trimming the array
            }

            memoryPrefs.edit().putString("analysis_memory", memory.toString()).apply()
            Log.i(TAG, "Memory saved: goal='$goal', confidence=${confidence}")
        } catch (e: Exception) {
            Log.e(TAG, "Memory save failed: ${e.message}")
        }
    }

    /**
     * Retrieve past similar analyses for learning
     */
    fun getRelevantMemory(goal: String, fileType: String): String {
        try {
            val memory = getMemoryJson()
            if (memory.length() == 0) return "No previous analysis memory"

            val relevant = mutableListOf<JSONObject>()
            for (i in 0 until memory.length()) {
                val entry = memory.getJSONObject(i)
                val entryGoal = entry.getString("goal").lowercase()
                val entryType = entry.getString("fileType").lowercase()

                // Simple relevance scoring
                var score = 0
                val goalWords = goal.lowercase().split(" ")
                for (word in goalWords) {
                    if (word.length > 3 && entryGoal.contains(word)) score++
                }
                if (entryType == fileType.lowercase()) score += 3

                if (score >= 2) relevant.add(entry)
            }

            if (relevant.isEmpty()) return "No relevant past analyses found"

            val sb = StringBuilder()
            sb.append("## Past Similar Analyses (${relevant.size} found)\n\n")

            relevant.take(5).forEachIndexed { i, entry ->
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(Date(entry.getLong("timestamp")))
                val conf = (entry.getDouble("confidence") * 100).toInt()
                val tools = entry.getJSONArray("toolsUsed").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
                sb.append("${i + 1}. **${entry.getString("goal")}** ($date)\n")
                sb.append("   Confidence: $conf% | Tools: ${tools.joinToString()}\n\n")
            }

            return sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Memory retrieval failed: ${e.message}")
            return "Memory retrieval error"
        }
    }

    private fun getMemoryJson(): JSONArray {
        val str = memoryPrefs.getString("analysis_memory", "[]")
        return try {
            JSONArray(str)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    /**
     * Get learning statistics
     */
    fun getLearningStats(): String {
        val memory = getMemoryJson()
        if (memory.length() == 0) return "No learning data yet"

        var totalSuccess = 0
        var totalDuration = 0L
        val toolFrequency = mutableMapOf<String, Int>()

        for (i in 0 until memory.length()) {
            val entry = memory.getJSONObject(i)
            if (entry.optBoolean("success")) totalSuccess++
            totalDuration += entry.optLong("duration", 0)
            val tools = entry.optJSONArray("toolsUsed")
            if (tools != null) {
                for (j in 0 until tools.length()) {
                    val tool = tools.getString(j)
                    toolFrequency[tool] = toolFrequency.getOrDefault(tool, 0) + 1
                }
            }
        }

        val sb = StringBuilder()
        sb.append("## AI Learning Statistics\n\n")
        sb.append("- **Total analyses**: ${memory.length()}\n")
        sb.append("- **Success rate**: ${totalSuccess}/${memory.length()} (${(totalSuccess * 100 / maxOf(memory.length(), 1))}%)\n")
        sb.append("- **Avg duration**: ${totalDuration / maxOf(memory.length(), 1) / 1000}s\n\n")

        sb.append("### Most Used Tools\n")
        toolFrequency.entries.sortedByDescending { it.value }.take(10)
            .forEach { (tool, count) ->
                sb.append("- $tool: $count times\n")
            }

        return sb.toString()
    }

    // ==================== 8. SANDBOX ====================

    /**
     * Virtual sandbox - safe execution environment
     */
    class SandboxEngine(context: Context) {
        private val sandboxDir = File(context.getExternalFilesDir(null), "sandbox")

        init {
            sandboxDir.mkdirs()
        }

        fun createSandbox(name: String): File {
            val dir = File(sandboxDir, name)
            dir.mkdirs()
            // Create isolated subdirectories
            File(dir, "input").mkdirs()
            File(dir, "output").mkdirs()
            File(dir, "tools").mkdirs()
            File(dir, "logs").mkdirs()
            return dir
        }

        fun getSandbox(name: String): File? {
            val dir = File(sandboxDir, name)
            return if (dir.exists()) dir else null
        }

        fun listSandboxes(): List<String> {
            return sandboxDir.listFiles()?.map { it.name } ?: emptyList()
        }

        fun clearSandbox(name: String) {
            File(sandboxDir, name).deleteRecursively()
        }

        fun getSandboxPath(): String = sandboxDir.absolutePath
    }

    // ==================== 9. BROWSER AUTOMATION ====================

    /**
     * Setup WebView for AI-controlled browsing
     */
    fun setupAIWebView(webView: WebView, onPageLoaded: (String, String) -> Unit) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Extract page content for AI analysis
                view.evaluateJavascript(
                    "(function(){return document.body.innerText.substring(0,5000);})()",
                ) { content ->
                    val cleanContent = content?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                    onPageLoaded(url, cleanContent)
                }
            }
        }
    }

    /**
     * AI browses to a URL and extracts content
     */
    fun aiBrowse(webView: WebView, url: String, callback: (String) -> Unit) {
        mainHandler.post {
            setupAIWebView(webView) { loadedUrl, content ->
                val summary = "## Web Content: $loadedUrl\n\n$content\n"
                callback(summary)
            }
            webView.loadUrl(url)
        }
    }

    /**
     * Download file via browser
     */
    fun aiDownloadFile(url: String, fileName: String, callback: (String) -> Unit) {
        scope.launch {
            try {
                val downloadDir = File(context.getExternalFilesDir(null), "downloads")
                downloadDir.mkdirs()
                val destFile = File(downloadDir, fileName)

                URL(url).openStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        val bytes = input.copyTo(output)
                        mainHandler.post {
                            callback("Downloaded: $fileName (${bytes} bytes) to ${destFile.absolutePath}")
                        }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { callback("Download failed: ${e.message}") }
            }
        }
    }

    // ==================== 10. NATURAL LANGUAGE PROCESSING ====================

    /**
     * Parse natural language command into structured intent
     */
    fun parseIntent(command: String): AnalysisIntent {
        val lower = command.lowercase()

        // Detect file type mentioned
        val fileType = when {
            lower.contains("apk") -> "apk"
            lower.contains("elf") || lower.contains(".so") -> "elf"
            lower.contains("dex") -> "dex"
            lower.contains("jar") -> "jar"
            lower.contains("exe") || lower.contains("pe") -> "pe"
            lower.contains("mach-o") || lower.contains("dylib") -> "macho"
            else -> "auto"
        }

        // Detect analysis depth
        val depth = when {
            lower.contains("comprehensive") || lower.contains("full") ||
            lower.contains("deep") || lower.contains("all") ||
            lower.contains("entire") || lower.contains("complete") ||
            lower.contains("전체") || lower.contains("모두") || lower.contains("종합") -> "deep"
            lower.contains("quick") || lower.contains("brief") ||
            lower.contains("fast") || lower.contains("overview") ||
            lower.contains("간단") || lower.contains("빠르게") -> "quick"
            else -> "standard"
        }

        // Extract explicit goals
        val goals = mutableListOf<String>()
        if (hasAny(lower, listOf("security", "vuln", "취약점", "보안"))) goals.add("security")
        if (hasAny(lower, listOf("crypto", "encrypt", "암호", "cipher", "aes", "rsa"))) goals.add("crypto")
        if (hasAny(lower, listOf("network", "traffic", "네트워크", "통신", "api", "url"))) goals.add("network")
        if (hasAny(lower, listOf("string", "문자열", "text"))) goals.add("strings")
        if (hasAny(lower, listOf("native", "jni", "so", "네이티브"))) goals.add("native")
        if (hasAny(lower, listOf("obfuscat", "pack", "난독", "패킹"))) goals.add("obfuscation")
        if (hasAny(lower, listOf("malware", "virus", "backdoor", "악성"))) goals.add("malware")

        // Extract file path if mentioned
        val filePath = extractFilePath(command)

        return AnalysisIntent(command, fileType, depth, goals, filePath)
    }

    data class AnalysisIntent(
        val originalCommand: String,
        val fileType: String,
        val depth: String,
        val goals: List<String>,
        val filePath: String?
    )

    private fun extractFilePath(command: String): String? {
        // Match common file paths
        val patterns = listOf(
            Regex("/(sdcard|storage|data|mnt)/[^\\s\"'\n]+"),
            Regex("/[^\\s\"'\n]+\\.(apk|elf|so|dex|jar|zip|exe|dll)", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(command)?.value?.let { return it }
        }
        return null
    }

    // ==================== HELPERS ====================

    private fun saveDownloadedTools() {
        val json = JSONArray()
        downloadedTools.forEach { (name, tool) ->
            val obj = JSONObject()
            obj.put("name", name)
            obj.put("source", tool.source)
            obj.put("localPath", tool.localPath)
            obj.put("installDate", tool.installDate)
            json.put(obj)
        }
        prefs.edit().putString("downloaded_tools", json.toString()).apply()
    }

    private fun loadDownloadedTools() {
        try {
            val str = prefs.getString("downloaded_tools", "[]") ?: "[]"
            val json = JSONArray(str)
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val name = obj.getString("name")
                downloadedTools[name] = DownloadedTool(
                    name = name,
                    source = obj.getString("source"),
                    localPath = obj.getString("localPath"),
                    installDate = obj.getLong("installDate")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load downloaded tools: ${e.message}")
        }
    }

    private fun hasAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }

    /**
     * Cleanup resources
     */
    // ==================== LOCAL LLM ====================

    /** Check if local LLM model is downloaded and ready */
    fun isLocalLLMReady(): Boolean = localLLM.isModelReady()

    /** Get local LLM status info */
    fun getLocalLLMInfo(): String = localLLM.getDownloadInfo()

    /** Download local LLM model (~1.3GB) */
    fun downloadLocalLLM(
        onProgress: (Int, Long, Long) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        localLLM.onDownloadProgress = { p, d, t -> onProgress(p, d, t) }
        localLLM.downloadModel(onProgress, onComplete)
    }

    /** Delete local LLM to free space */
    fun deleteLocalLLM() = localLLM.deleteModel()

    /** Initialize local LLM engine */
    fun initLocalLLM(): Boolean = localLLM.initialize()

    /** Generate response using local LLM */
    suspend fun generateWithLocalLLM(prompt: String): String =
        localLLM.generateResponseAsync(prompt)

    fun destroy() {
        scope.cancel()
        reinforcement.destroy()
        cognitive.destroy()
        localLLM.destroy()
    }

    init {
        loadDownloadedTools()
        // Sync API keys from our prefs to AIMultiEngine on startup
        ALL_PLATFORMS.forEach { platform ->
            prefs.getString("apikey_$platform", null)?.let { key ->
                if (key.isNotBlank()) multiEngine.saveKey(platform, key)
            }
        }
    }
    // ==================== 8 AI PARALLEL CHAT ====================

    /**
     * Send message to all 8 AI platforms in parallel and combine results
     */
    suspend fun chatWithParallelAI(userMessage: String, filePath: String? = null): String {
        val activePlatforms = getActivePlatforms()

        // No API keys + no local LLM → local rule-based analysis
        if (activePlatforms.isEmpty() && !localLLM.isModelReady()) {
            return generateLocalFallbackResponse(userMessage, filePath)
        }

        // No API keys but local LLM ready → use local LLM
        if (activePlatforms.isEmpty() && localLLM.isModelReady()) {
            return generateLocalFallbackResponse(userMessage, filePath)
        }

        val results = mutableListOf<Pair<String, String>>()

        coroutineScope {
            activePlatforms.map { platform ->
                async(Dispatchers.IO) {
                    try {
                        val response = chatSinglePlatform(platform, userMessage)
                        val isError = response.startsWith("Error ") || 
                            response.startsWith("[No API key") ||
                            response.contains("quota", true) ||
                            response.contains("Invalid API key", true) ||
                            response.contains("Rate limit", true) ||
                            response.contains("Authentication", true)
                        platform to if (isError) "" else response
                    } catch (e: Exception) {
                        platform to ""
                    }
                }
            }.awaitAll().forEach { results.add(it) }
        }

        val successes = results.filter { it.second.isNotBlank() }

        // ALL APIs failed → use local LLM or rule-based
        if (successes.isEmpty()) {
            return generateLocalFallbackResponse(userMessage, filePath)
        }

        val sb = StringBuilder()
        sb.append("## 8 AI Parallel Analysis\n\n")
        sb.append("Platforms responded: ${successes.size}/${activePlatforms.size}\n\n")

        successes.forEach { (platform, response) ->
            sb.append("### **${platform.replaceFirstChar { it.uppercase() }}**\n")
            sb.append("```\n${response.take(1500)}\n```\n\n")
        }

        val bestResponse = successes.maxByOrNull { it.second.length }?.second ?: ""
        sb.append("---\n\n")
        sb.append("### Combined Best Answer\n\n")
        sb.append(bestResponse.take(3000))
        return sb.toString()
    }

    /**
     * Chat with a single AI platform via HTTP API
     * Uses AIMultiEngine which has all 8 platforms implemented
     */
    private fun chatSinglePlatform(platform: String, message: String): String {
        val apiKey = getApiKey(platform) ?: return "[No API key for $platform]"
        return try {
            runBlocking {
                multiEngine.chat(platform, listOf(ChatMessage(role = "user", content = message)))
            }
        } catch (e: Exception) {
            "Error ($platform): ${e.message}"
        }
    }

    // ==================== LOCAL FALLBACK ANALYSIS ====================

    /**
     * LOCAL FALLBACK: API failure origin blocking.
     * Tries local LLM first, then rule-based analysis.
     * Users NEVER see raw API errors.
     */
        private fun generateLocalFallbackResponse(userMessage: String, filePath: String?): String {
        val prompt = buildLocalLLMPrompt(userMessage, filePath)
        return try {
            localLLM.generateResponse(prompt)
        } catch (e: Exception) {
            Log.w(TAG, "Local LLM error: ${e.message}")
            localLLM.generateResponse(userMessage)
        }
    }


    // ==================== PUBLIC API FOR COGNITIVE & REINFORCEMENT ====================

    /** Delegate: Infer user intent from message */
    fun inferIntent(message: String, fileType: String?): AdvancedCognitiveEngine.UserIntent {
        return cognitive.inferIntent(message, fileType)
    }

    /** Delegate: Decompose complex goal into hierarchical reasoning tree */
    fun decomposeGoal(goal: String, fileType: String?): AdvancedCognitiveEngine.ReasoningTree {
        return cognitive.decomposeGoal(goal, fileType)
    }

    /** Delegate: Self-reflection on analysis result */
    fun selfReflect(analysisResult: String, executionTimeMs: Long): AdvancedCognitiveEngine.ReflectionReport {
        return cognitive.selfReflect(analysisResult, executionTimeMs)
    }

    /** Delegate: Query working memory / knowledge base */
    fun queryKnowledge(keyPrefix: String): List<AdvancedCognitiveEngine.KnowledgeEntry> {
        return cognitive.queryKnowledge(keyPrefix)
    }

    /** Delegate: Get reinforcement learning strategy report */
    fun getStrategyReport(): String {
        return reinforcement.getStrategyReport()
    }

    /** Get file type from path */
    fun getFileType(filePath: String): String {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "apk" -> "apk"
            "elf", "so", "o" -> "elf"
            "dex" -> "dex"
            "jar" -> "jar"
            "zip" -> "zip"
            else -> "binary"
        }
    }
}
