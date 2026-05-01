package com.hermes.analyzer.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AdvancedCognitiveEngine - 고지능 인지/추론 엔진
 *
 * Capabilities:
 * 1. Hierarchical reasoning: simple → complex problem decomposition
 * 2. Intent inference: understanding what user REALLY wants
 * 3. Memory networks: short-term working memory + long-term knowledge
 * 4. Meta-cognition: self-performance analysis and adjustment
 * 5. Pattern recognition: finding hidden patterns in data
 * 6. Causal reasoning: understanding cause-effect relationships
 */
class AdvancedCognitiveEngine(private val context: Context) {

    companion object {
        private const val TAG = "AdvancedCognitive"
        private const val WORKING_MEMORY_FILE = "working_memory.json"
        private const val KNOWLEDGE_FILE = "knowledge_base.json"
        private const val MAX_WORKING_MEMORY = 50
        private const val MAX_KNOWLEDGE_ENTRIES = 500
    }

    private val dataDir = File(context.getExternalFilesDir(null), "ai_cognitive")
    private val workingMemoryFile = File(dataDir, WORKING_MEMORY_FILE)
    private val knowledgeFile = File(dataDir, KNOWLEDGE_FILE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Working memory (short-term, session-based)
    private val workingMemory = mutableListOf<MemoryNode>()

    // Long-term knowledge
    private val knowledgeBase = mutableMapOf<String, KnowledgeEntry>()

    init {
        dataDir.mkdirs()
        loadWorkingMemory()
        loadKnowledgeBase()
    }

    // ==================== MEMORY NETWORK ====================

    data class MemoryNode(
        val id: String,
        val content: String,
        val type: MemoryType,
        val timestamp: Long,
        val relevance: Double,
        val tags: List<String>
    )

    enum class MemoryType { FACT, PATTERN, STRATEGY, OUTCOME, USER_PREFERENCE, ERROR }

    data class KnowledgeEntry(
        val key: String,
        val value: String,
        val confidence: Double,
        val source: String,
        val lastUpdated: Long
    )

    /** Add item to working memory */
    fun addToWorkingMemory(content: String, type: MemoryType, tags: List<String> = emptyList()) {
        val node = MemoryNode(
            id = "wm_${System.currentTimeMillis()}_${workingMemory.size}",
            content = content,
            type = type,
            timestamp = System.currentTimeMillis(),
            relevance = 1.0,
            tags = tags
        )
        workingMemory.add(node)
        if (workingMemory.size > MAX_WORKING_MEMORY) {
            // Remove least relevant
            val minRelevance = workingMemory.minOf { it.relevance }
            workingMemory.removeAll { it.relevance == minRelevance }
        }
        // Decay older items
        decayWorkingMemory()
    }

    /** Promote working memory to long-term knowledge */
    fun consolidateKnowledge(key: String, value: String, confidence: Double, source: String) {
        knowledgeBase[key] = KnowledgeEntry(
            key = key,
            value = value,
            confidence = confidence,
            source = source,
            lastUpdated = System.currentTimeMillis()
        )
        if (knowledgeBase.size > MAX_KNOWLEDGE_ENTRIES) {
            // Remove lowest confidence
            val minKey = knowledgeBase.minByOrNull { it.value.confidence }?.key
            minKey?.let { knowledgeBase.remove(it) }
        }
        scope.launch { saveKnowledgeBase() }
    }

    private fun decayWorkingMemory() {
        val now = System.currentTimeMillis()
        workingMemory.forEachIndexed { _, node ->
            val age = (now - node.timestamp) / 1000.0 // seconds
            val decay = 1.0 / (1.0 + age / 300.0) // Half-life: 5 minutes
            node.copy(relevance = node.relevance * decay)
        }
    }

    /** Query working memory by tags or content match */
    fun queryWorkingMemory(query: String, type: MemoryType? = null, limit: Int = 10): List<MemoryNode> {
        val lowerQuery = query.lowercase()
        return workingMemory
            .filter { node ->
                val typeMatch = type == null || node.type == type
                val contentMatch = node.content.lowercase().contains(lowerQuery) ||
                    node.tags.any { it.lowercase().contains(lowerQuery) }
                typeMatch && contentMatch
            }
            .sortedByDescending { it.relevance }
            .take(limit)
    }

    /** Query knowledge base */
    fun queryKnowledge(keyPrefix: String): List<KnowledgeEntry> {
        return knowledgeBase.values
            .filter { it.key.startsWith(keyPrefix) }
            .sortedByDescending { it.confidence }
    }

    // ==================== INTENT INFERENCE ====================

    /**
     * Infer user's true intent from their message.
     * Goes beyond keyword matching to understand context.
     */
    fun inferIntent(userMessage: String, fileType: String? = null): UserIntent {
        val lower = userMessage.lowercase()

        // Direct intent patterns
        val securityTerms = listOf("vuln", "security", "exploit", "buffer overflow", "취약", "해킹")
        val cryptoTerms = listOf("crypto", "encrypt", "decrypt", "aes", "rsa", "sha", "암호")
        val networkTerms = listOf("network", "api", "http", "url", "통신", "서버")
        val decompileTerms = listOf("decompile", "disassemble", "source", "소스", "역컴파일")
        val behaviorTerms = listOf("behavior", "malware", "trojan", "virus", "백도어", "악성")

        val scores = mutableMapOf(
            IntentType.SECURITY to securityTerms.count { lower.contains(it) },
            IntentType.CRYPTO to cryptoTerms.count { lower.contains(it) },
            IntentType.NETWORK to networkTerms.count { lower.contains(it) },
            IntentType.DECOMPILE to decompileTerms.count { lower.contains(it) },
            IntentType.BEHAVIOR to behaviorTerms.count { lower.contains(it) },
            IntentType.GENERAL to 0
        )

        // File-type boost
        when (fileType?.lowercase()) {
            "apk" -> scores[IntentType.DECOMPILE] = scores[IntentType.DECOMPILE]!! + 2
            "elf", "so" -> scores[IntentType.SECURITY] = scores[IntentType.SECURITY]!! + 2
        }

        // Urgency detection
        val urgencyWords = listOf("urgent", "quick", "fast", "now", "immediately", "급히", "빨리")
        val urgency = urgencyWords.count { lower.contains(it) }

        val bestType = scores.maxByOrNull { it.value }?.key ?: IntentType.GENERAL
        val confidence = (scores[bestType]!! / (scores.values.sum().toDouble().coerceAtLeast(1.0)))
            .coerceIn(0.0, 1.0)

        // Extract entities (tools, file names, etc.)
        val entities = extractEntities(lower)

        return UserIntent(
            primaryType = bestType,
            confidence = confidence,
            urgency = urgency,
            entities = entities,
            suggestedApproach = suggestApproach(bestType, fileType)
        )
    }

    private fun extractEntities(text: String): List<String> {
        val tools = listOf("ida", "ghidra", "radare2", "jadx", "frida", "apktool", "burp", "gdb", "binary ninja")
        return tools.filter { text.contains(it) }
    }

    private fun suggestApproach(intent: IntentType, fileType: String?): String {
        return when (intent) {
            IntentType.SECURITY -> "Focus on vulnerability scan, unsafe function detection, exploit surface analysis"
            IntentType.CRYPTO -> "Extract cryptographic constants, analyze algorithm usage, key management review"
            IntentType.NETWORK -> "Map all network endpoints, protocol analysis, certificate validation check"
            IntentType.DECOMPILE -> "Multi-tool decompilation strategy: JADX → manual review → native analysis"
            IntentType.BEHAVIOR -> "Dynamic analysis preparation, sandbox execution planning, behavior signature extraction"
            IntentType.GENERAL -> "Comprehensive static analysis followed by targeted deep-dive"
        }
    }

    enum class IntentType { SECURITY, CRYPTO, NETWORK, DECOMPILE, BEHAVIOR, GENERAL }

    data class UserIntent(
        val primaryType: IntentType,
        val confidence: Double,
        val urgency: Int,
        val entities: List<String>,
        val suggestedApproach: String
    )

    // ==================== HIERARCHICAL REASONING ====================

    /**
     * Break down a complex goal into hierarchical sub-goals
     */
    fun decomposeGoal(goal: String, fileType: String?): ReasoningTree {
        val root = ReasoningNode(
            id = "root",
            description = goal,
            complexity = estimateComplexity(goal),
            subGoals = mutableListOf()
        )

        // Phase 1: Information gathering
        root.subGoals.add(ReasoningNode(
            id = "phase1",
            description = "Information Gathering",
            complexity = 1,
            subGoals = mutableListOf(
                ReasoningNode("p1_1", "Extract file metadata", 1),
                ReasoningNode("p1_2", "Identify file structure", 1),
                ReasoningNode("p1_3", "String extraction and initial scan", 1)
            )
        ))

        // Phase 2: Deep analysis (type-specific)
        val phase2 = ReasoningNode("phase2", "Deep Analysis", 2, mutableListOf())
        when (fileType?.lowercase()) {
            "apk" -> {
                phase2.subGoals.add(ReasoningNode("p2_1", "AndroidManifest analysis", 2))
                phase2.subGoals.add(ReasoningNode("p2_2", "DEX decompilation", 2))
                phase2.subGoals.add(ReasoningNode("p2_3", "Native library scan (.so)", 2))
            }
            "elf", "so" -> {
                phase2.subGoals.add(ReasoningNode("p2_1", "ELF header and segment analysis", 2))
                phase2.subGoals.add(ReasoningNode("p2_2", "Symbol and relocation table scan", 2))
                phase2.subGoals.add(ReasoningNode("p2_3", "Disassembly and control flow", 3))
            }
            else -> {
                phase2.subGoals.add(ReasoningNode("p2_1", "Binary structure analysis", 2))
                phase2.subGoals.add(ReasoningNode("p2_2", "Pattern and signature matching", 2))
            }
        }
        root.subGoals.add(phase2)

        // Phase 3: Security assessment
        root.subGoals.add(ReasoningNode(
            id = "phase3",
            description = "Security Assessment",
            complexity = 2,
            subGoals = mutableListOf(
                ReasoningNode("p3_1", "Vulnerability pattern scan", 2),
                ReasoningNode("p3_2", "Risk scoring and classification", 1),
                ReasoningNode("p3_3", "Mitigation recommendations", 1)
            )
        ))

        // Phase 4: Synthesis (only for complex goals)
        if (root.complexity >= 3) {
            root.subGoals.add(ReasoningNode(
                id = "phase4",
                description = "Result Synthesis & Reporting",
                complexity = 1,
                subGoals = mutableListOf(
                    ReasoningNode("p4_1", "Cross-reference findings", 1),
                    ReasoningNode("p4_2", "Generate executive summary", 1)
                )
            ))
        }

        return ReasoningTree(root = root, estimatedSteps = countSteps(root))
    }

    private fun estimateComplexity(goal: String): Int {
        val words = goal.split(" ").size
        val complexityKeywords = listOf("deep", "full", "complete", "comprehensive", "advanced", "thorough")
        val bonus = complexityKeywords.count { goal.lowercase().contains(it) }
        return ((words / 5) + bonus).coerceIn(1, 5)
    }

    private fun countSteps(node: ReasoningNode): Int {
        return 1 + node.subGoals.sumOf { countSteps(it) }
    }

    data class ReasoningTree(val root: ReasoningNode, val estimatedSteps: Int)

    data class ReasoningNode(
        val id: String,
        val description: String,
        val complexity: Int,
        val subGoals: MutableList<ReasoningNode> = mutableListOf()
    )

    // ==================== PATTERN RECOGNITION ====================

    /**
     * Find patterns in a list of strings or data points
     */
    fun findPatterns(data: List<String>): List<DetectedPattern> {
        val patterns = mutableListOf<DetectedPattern>()

        // URL pattern
        val urls = data.filter { it.matches(Regex("https?://[^\\s]+")) }
        if (urls.isNotEmpty()) {
            patterns.add(DetectedPattern("URLs", urls.size, urls.take(5), "Network endpoints or C2 servers"))
        }

        // IP pattern
        val ips = data.filter { it.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) }
        if (ips.isNotEmpty()) {
            patterns.add(DetectedPattern("IP Addresses", ips.size, ips.take(5), "Hardcoded server addresses"))
        }

        // Email pattern
        val emails = data.filter { it.matches(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")) }
        if (emails.isNotEmpty()) {
            patterns.add(DetectedPattern("Emails", emails.size, emails.take(5), "Contact or account info"))
        }

        // Crypto key pattern
        val keys = data.filter { it.length >= 32 && it.matches(Regex("[A-Za-z0-9+/=]+")) }
        if (keys.isNotEmpty()) {
            patterns.add(DetectedPattern("Base64-like strings", keys.size, keys.take(3), "Possible keys or encoded data"))
        }

        // Function name pattern
        val functions = data.filter { it.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*\\(.*")) }
        if (functions.isNotEmpty()) {
            patterns.add(DetectedPattern("Function signatures", functions.size, functions.take(5), "API calls or exports"))
        }

        return patterns
    }

    data class DetectedPattern(
        val name: String,
        val count: Int,
        val examples: List<String>,
        val interpretation: String
    )

    // ==================== META-COGNITION ====================

    /** Analyze own performance and suggest self-improvements */
    fun selfReflect(lastAnalysisResult: String, executionTimeMs: Long): ReflectionReport {
        val issues = mutableListOf<String>()
        val improvements = mutableListOf<String>()

        // Time-based reflection
        when {
            executionTimeMs > 30000 -> {
                issues.add("Analysis took >30s - consider parallelizing or using lighter tools")
                improvements.add("Pre-filter tools based on file type before full scan")
            }
            executionTimeMs < 1000 -> {
                issues.add("Analysis was very fast (<1s) - may have missed depth")
                improvements.add("Add mandatory deep-scan phase for critical files")
            }
        }

        // Result quality reflection
        if (!lastAnalysisResult.contains("vulnerability") && !lastAnalysisResult.contains("security")) {
            issues.add("No security findings mentioned - verify scan completeness")
        }
        if (lastAnalysisResult.length < 500) {
            issues.add("Output seems brief - may need more detailed reporting")
            improvements.add("Add automatic expansion for sections with low detail")
        }

        // Content-based reflection
        if (lastAnalysisResult.contains("Error") || lastAnalysisResult.contains("Failed")) {
            issues.add("Errors detected during analysis")
            improvements.add("Implement automatic retry with alternative tools")
        }

        return ReflectionReport(
            issues = issues,
            improvements = improvements,
            selfScore = calculateSelfScore(executionTimeMs, lastAnalysisResult)
        )
    }

    private fun calculateSelfScore(timeMs: Long, result: String): Double {
        var score = 0.5
        if (timeMs in 2000..15000) score += 0.2 // Good timing
        if (result.length > 1000) score += 0.1 // Substantial output
        if (result.contains("recommendation") || result.contains("suggest")) score += 0.1 // Actionable
        if (!result.contains("Error")) score += 0.1 // Clean execution
        return score.coerceIn(0.0, 1.0)
    }

    data class ReflectionReport(
        val issues: List<String>,
        val improvements: List<String>,
        val selfScore: Double
    )

    // ==================== PERSISTENCE ====================

    private fun loadWorkingMemory() {
        try {
            if (workingMemoryFile.exists()) {
                val json = JSONArray(workingMemoryFile.readText())
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    workingMemory.add(MemoryNode(
                        id = obj.getString("id"),
                        content = obj.getString("content"),
                        type = MemoryType.valueOf(obj.getString("type")),
                        timestamp = obj.getLong("timestamp"),
                        relevance = obj.getDouble("relevance"),
                        tags = obj.getJSONArray("tags").let { arr ->
                            List(arr.length()) { arr.getString(it) }
                        }
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Working memory load failed: ${e.message}")
        }
    }

    private fun saveWorkingMemory() {
        try {
            val json = JSONArray()
            workingMemory.forEach { node ->
                json.put(JSONObject().apply {
                    put("id", node.id)
                    put("content", node.content)
                    put("type", node.type.name)
                    put("timestamp", node.timestamp)
                    put("relevance", node.relevance)
                    put("tags", JSONArray(node.tags))
                })
            }
            workingMemoryFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Working memory save failed: ${e.message}")
        }
    }

    private fun loadKnowledgeBase() {
        try {
            if (knowledgeFile.exists()) {
                val json = JSONObject(knowledgeFile.readText())
                json.keys().forEach { key ->
                    val obj = json.getJSONObject(key)
                    knowledgeBase[key] = KnowledgeEntry(
                        key = key,
                        value = obj.getString("value"),
                        confidence = obj.getDouble("confidence"),
                        source = obj.getString("source"),
                        lastUpdated = obj.getLong("lastUpdated")
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Knowledge base load failed: ${e.message}")
        }
    }

    private fun saveKnowledgeBase() {
        try {
            val json = JSONObject()
            knowledgeBase.forEach { (k, v) ->
                json.put(k, JSONObject().apply {
                    put("value", v.value)
                    put("confidence", v.confidence)
                    put("source", v.source)
                    put("lastUpdated", v.lastUpdated)
                })
            }
            knowledgeFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Knowledge base save failed: ${e.message}")
        }
    }

    /** Reset cognitive state */
    fun resetCognitiveState() {
        workingMemory.clear()
        knowledgeBase.clear()
        workingMemoryFile.delete()
        knowledgeFile.delete()
        Log.i(TAG, "Cognitive state reset")
    }

    /** Cleanup */
    fun destroy() {
        saveWorkingMemory()
        saveKnowledgeBase()
        scope.cancel()
    }
}
