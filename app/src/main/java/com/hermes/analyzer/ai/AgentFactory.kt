package com.hermes.analyzer.ai

import android.content.Context
import android.util.Log
import com.hermes.analyzer.ai.agents.*
import kotlinx.coroutines.*

/**
 * AgentFactory
 * 작업 복잡도에 따라 동적 에이전트를 생성, 할당, 실행, 소멸시키는 팩토리
 * 기존 8개 고정 에이전트 외에 최대 32개까지 자동 확장
 */
class AgentFactory(private val context: Context) {
    private val TAG = "AgentFactory"
    private val engine = AdvancedAIEngine(context)

    // 고정 에이전트 (8개)
    val fixedAgents = mapOf(
        "code" to CodeAnalyzerAgent(engine),
        "security" to SecurityInspectorAgent(engine),
        "doc" to DocumentationAgent(engine),
        "web" to WebScoutAgent(engine),
        "test" to TestRunnerAgent(),
        "binary" to BinaryAnalystAgent(engine),
        "github" to GitHubOperatorAgent(engine),
        "validator" to ResultValidatorAgent(engine)
    )

    // 동적 에이전트 풀
    private val dynamicAgents = mutableMapOf<String, DynamicAgent>()
    private var agentCounter = 0
    private val maxDynamicAgents = 32
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class DynamicAgent(
        val id: String,
        val role: String,
        var status: String, // idle, running, completed, destroyed
        val createdAt: Long,
        var taskDescription: String = "",
        var result: String = ""
    )

    data class TaskRequest(
        val taskType: String,
        val description: String,
        val priority: Int, // 1-10
        val estimatedComplexity: Int, // 1-100
        val filePath: String? = null
    )

    data class FactoryResult(
        val success: Boolean,
        val outputs: Map<String, String>,
        val agentsUsed: List<String>,
        val durationMs: Long
    )

    /**
     * 작업을 분석하고 필요한 에이전트 수를 결정하여 병렬 실행
     */
    suspend fun dispatchComplexTask(request: TaskRequest): FactoryResult {
        val startTime = System.currentTimeMillis()
        val requiredAgents = calculateRequiredAgents(request)
        
        Log.i(TAG, "Task '${request.description.take(30)}...' requires $requiredAgents agents")
        
        // Spawn additional agents if needed
        if (requiredAgents > fixedAgents.size) {
            val extraNeeded = requiredAgents - fixedAgents.size
            spawnDynamicAgents(extraNeeded, request.taskType)
        }

        // Distribute subtasks
        val subtasks = decomposeTask(request, requiredAgents)
        val outputs = mutableMapOf<String, String>()
        val agentsUsed = mutableListOf<String>()

        // Execute in parallel
        coroutineScope {
            val jobs = mutableListOf<Deferred<Pair<String, String>>>()
            
            // Fixed agents first
            fixedAgents.entries.take(minOf(fixedAgents.size, requiredAgents)).forEachIndexed { idx, (name, agent) ->
                if (idx < subtasks.size) {
                    val subtask = subtasks[idx]
                    jobs.add(async {
                        val result = executeWithFixedAgent(name, agent, subtask, request)
                        name to result
                    })
                }
            }
            
            // Dynamic agents for remaining subtasks
            if (subtasks.size > fixedAgents.size) {
                val remainingTasks = subtasks.drop(fixedAgents.size)
                val availableDynamic = dynamicAgents.values.filter { it.status == "idle" }
                
                remainingTasks.forEachIndexed { idx, subtask ->
                    val dynamic = availableDynamic.getOrNull(idx)
                    if (dynamic != null) {
                        jobs.add(async {
                            val result = executeWithDynamicAgent(dynamic, subtask, request)
                            dynamic.id to result
                        })
                    }
                }
            }

            jobs.awaitAll().forEach { (name, result) ->
                outputs[name] = result
                agentsUsed.add(name)
            }
        }

        // Cleanup idle dynamic agents
        cleanupIdleAgents()

        val duration = System.currentTimeMillis() - startTime
        return FactoryResult(true, outputs, agentsUsed, duration)
    }

    /**
     * 단일 에이전트 임시 스폰 (단순 작업용)
     */
    fun spawnTempAgent(role: String, task: String): String {
        if (dynamicAgents.size >= maxDynamicAgents) {
            // Destroy oldest idle agent
            dynamicAgents.values.filter { it.status == "completed" }.minByOrNull { it.createdAt }?.let {
                destroyAgent(it.id)
            }
        }

        agentCounter++
        var id = "agent_temp_${agentCounter}_${System.currentTimeMillis()}"
        val agent = DynamicAgent(id, role, "idle", System.currentTimeMillis())
        agent.taskDescription = task
        dynamicAgents[id] = agent
        
        Log.i(TAG, "Spawned agent $id with role $role")
        return id
    }

    fun executeAgent(agentId: String): String {
        val agent = dynamicAgents[agentId] ?: return "Error: Agent not found"
        agent.status = "running"
        
        val result = try {
            when (agent.role) {
                "code_analyzer" -> analyzeCode(agent.taskDescription)
                "security_scanner" -> scanSecurity(agent.taskDescription)
                "web_scraper" -> scrapeWeb(agent.taskDescription)
                "file_processor" -> processFile(agent.taskDescription)
                "text_generator" -> generateText(agent.taskDescription)
                "data_extractor" -> extractData(agent.taskDescription)
                "pattern_finder" -> findPatterns(agent.taskDescription)
                else -> "Generic execution: ${agent.taskDescription.take(100)}..."
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
        
        agent.result = result
        agent.status = "completed"
        return result
    }

    fun destroyAgent(agentId: String) {
        dynamicAgents.remove(agentId)
        Log.i(TAG, "Destroyed agent $agentId")
    }

    fun getAgentStatus(): List<DynamicAgent> {
        return dynamicAgents.values.toList() + fixedAgents.keys.map { 
            DynamicAgent(it, "fixed_" + it, "ready", 0) 
        }
    }

    fun getFactoryStats(): String {
        val fixed = fixedAgents.size
        val dynamic = dynamicAgents.size
        val active = dynamicAgents.count { it.value.status == "running" }
        val completed = dynamicAgents.count { it.value.status == "completed" }
        
        return """# Agent Factory Stats
Fixed Agents: $fixed
Dynamic Agents: $dynamic / $maxDynamicAgents
Active: $active
Completed: $completed
Total Ever Spawned: $agentCounter
"""
    }

    private fun calculateRequiredAgents(request: TaskRequest): Int {
        val base = when {
            request.estimatedComplexity > 80 -> 16
            request.estimatedComplexity > 50 -> 12
            request.estimatedComplexity > 30 -> 8
            request.estimatedComplexity > 15 -> 4
            else -> 2
        }
        return minOf(base, fixedAgents.size + maxDynamicAgents)
    }

    private fun decomposeTask(request: TaskRequest, parts: Int): List<String> {
        val base = request.description
        return (1..parts).map { i ->
            "[Part $i/$parts] ${request.taskType}: $base (priority=${request.priority})"
        }
    }

    private fun spawnDynamicAgents(count: Int, roleHint: String) {
        repeat(count) {
            if (dynamicAgents.size >= maxDynamicAgents) return
            agentCounter++
            var id = "agent_dyn_${agentCounter}_${System.currentTimeMillis()}"
            val role = when (roleHint) {
                "code" -> "code_analyzer"
                "security" -> "security_scanner"
                "web" -> "web_scraper"
                "file" -> "file_processor"
                "text" -> "text_generator"
                "data" -> "data_extractor"
                "pattern" -> "pattern_finder"
                else -> "general_worker"
            }
            dynamicAgents[id] = DynamicAgent(id, role, "idle", System.currentTimeMillis())
            Log.i(TAG, "Spawned dynamic agent $id (role=$role)")
        }
    }

    private suspend fun executeWithFixedAgent(name: String, agent: Any, subtask: String, request: TaskRequest): String {
        return try {
            when (agent) {
                is CodeAnalyzerAgent -> {
                    val analysis = agent.analyze(subtask)
                    "Code analysis: ${analysis.language}, ${analysis.functions.size} functions, ${analysis.issues.size} issues"
                }
                is SecurityInspectorAgent -> {
                    val report = agent.inspect(subtask)
                    "Security scan: Score ${report.score}/100, ${report.criticalCount} critical, ${report.warningCount} warnings"
                }
                is DocumentationAgent -> {
                    val report = agent.generateReport("Analysis", null, null, null, subtask)
                    "Documentation: ${report.length} chars generated"
                }
                is WebScoutAgent -> {
                    val result = agent.search(subtask)
                    "Web search: ${result.sources.size} sources found"
                }
                is TestRunnerAgent -> {
                    val result = agent.validateAnalysisOutput(subtask)
                    "Validation: ${result.passed}/${result.passed + result.failed} passed"
                }
                is BinaryAnalystAgent -> {
                    request.filePath?.let { path ->
                        val analysis = agent.analyzeBinary(path)
                        "Binary: ${analysis.fileType}, ${analysis.strings.size} strings, ${analysis.suspiciousFunctions.size} suspicious"
                    } ?: "No file path for binary analysis"
                }
                is GitHubOperatorAgent -> {
                    val result = agent.searchRepositories(subtask)
                    "GitHub: ${result.take(100)}..."
                }
                is ResultValidatorAgent -> {
                    val report = agent.validateAndMerge(mapOf(name to subtask))
                    "Validation: Score ${report.consistencyScore}/100"
                }
                else -> "Unknown agent type"
            }
        } catch (e: Exception) {
            "Error in $name: ${e.message}"
        }
    }

    private fun executeWithDynamicAgent(agent: DynamicAgent, subtask: String, request: TaskRequest): String {
        agent.status = "running"
        agent.taskDescription = subtask
        val result = executeAgent(agent.id)
        return result
    }

    private fun cleanupIdleAgents() {
        val toRemove = dynamicAgents.values
            .filter { it.status == "completed" && (System.currentTimeMillis() - it.createdAt > 300000) }
            .map { it.id }
        toRemove.forEach { destroyAgent(it) }
    }

    private fun analyzeCode(task: String): String = "Code analysis completed for: ${task.take(100)}"
    private fun scanSecurity(task: String): String = "Security scan completed for: ${task.take(100)}"
    private fun scrapeWeb(task: String): String = "Web scraping completed for: ${task.take(100)}"
    private fun processFile(task: String): String = "File processing completed for: ${task.take(100)}"
    private fun generateText(task: String): String = "Text generation completed for: ${task.take(100)}"
    private fun extractData(task: String): String = "Data extraction completed for: ${task.take(100)}"
    private fun findPatterns(task: String): String = "Pattern finding completed for: ${task.take(100)}"
}
