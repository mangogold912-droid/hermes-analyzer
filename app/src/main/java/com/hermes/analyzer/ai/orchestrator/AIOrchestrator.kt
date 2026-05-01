package com.hermes.analyzer.ai.orchestrator

import com.hermes.analyzer.ai.AdvancedAIEngine
import com.hermes.analyzer.ai.agents.*
import com.hermes.analyzer.AuditLogManager
import kotlinx.coroutines.*

/**
 * AI Orchestrator
 * 메인 AI가 사용자의 자연어 목표를 이해하고 작업을 분해하며,
 * 8개 보조 에이전트에게 병렬 분배 후 결과를 종합한다.
 */
class AIOrchestrator(private val context: android.content.Context) {
    private val engine = AdvancedAIEngine(context)
    private val audit = AuditLogManager(context)
    
    // 8 professional sub-agents
    val codeAgent = CodeAnalyzerAgent(engine)
    val securityAgent = SecurityInspectorAgent(engine)
    val docAgent = DocumentationAgent(engine)
    val webAgent = WebScoutAgent(engine)
    val testAgent = TestRunnerAgent()
    val binaryAgent = BinaryAnalystAgent(engine)
    val githubAgent = GitHubOperatorAgent(engine)
    val validatorAgent = ResultValidatorAgent(engine)

    data class TaskPlan(
        val goal: String,
        val phases: List<Phase>,
        val estimatedTimeMs: Long
    )

    data class Phase(
        val name: String,
        val description: String,
        val assignedAgents: List<String>,
        val inputs: Map<String, String>
    )

    data class ExecutionResult(
        val success: Boolean,
        val finalReport: String,
        val agentOutputs: Map<String, String>,
        val durationMs: Long,
        val validationScore: Int
    )

    /**
     * Main entry: execute a user goal autonomously
     */
    suspend fun executeGoal(userGoal: String, filePath: String? = null): ExecutionResult {
        val startTime = System.currentTimeMillis()
        audit.log("orchestrator", "goal_start", userGoal, "in_progress")
        
        return try {
            // Step 1: Decompose goal into phases
            val plan = decomposeGoal(userGoal, filePath)
            
            // Step 2: Dispatch to 8 agents in parallel
            val agentResults = dispatchToAgents(plan, filePath, userGoal)
            
            // Step 3: Validate and merge results
            val validation = validatorAgent.validateAndMerge(agentResults)
            
            // Step 4: Generate final report
            val finalReport = generateFinalReport(validation, userGoal, filePath)
            
            val duration = System.currentTimeMillis() - startTime
            audit.log("orchestrator", "goal_complete", userGoal, "success", emptyList(), true)
            
            ExecutionResult(
                success = true,
                finalReport = finalReport,
                agentOutputs = agentResults,
                durationMs = duration,
                validationScore = validation.consistencyScore
            )
        } catch (e: Exception) {
            audit.log("orchestrator", "goal_failed", userGoal, "error: ${e.message}")
            ExecutionResult(
                success = false,
                finalReport = "Execution failed: ${e.message}",
                agentOutputs = emptyMap(),
                durationMs = System.currentTimeMillis() - startTime,
                validationScore = 0
            )
        }
    }

    /**
     * Break down complex goal into executable phases
     */
    fun decomposeGoal(goal: String, filePath: String?): TaskPlan {
        val lower = goal.lowercase()
        val phases = mutableListOf<Phase>()
        
        // Always start with context gathering
        phases.add(Phase(
            name = "Context Analysis",
            description = "Analyze input file and understand user intent",
            assignedAgents = listOf("binary", "code"),
            inputs = mapOf("file" to (filePath ?: "none"), "goal" to goal)
        ))
        
        when {
            lower.contains("github") || lower.contains("repository") || lower.contains("pr") || lower.contains("commit") -> {
                phases.add(Phase("GitHub Analysis", "Search and analyze GitHub repositories", listOf("github", "web"), emptyMap()))
                phases.add(Phase("Code Review", "Review cloned code for issues", listOf("code", "security"), emptyMap()))
                phases.add(Phase("Documentation", "Generate PR description and summary", listOf("doc"), emptyMap()))
            }
            lower.contains("ida") || lower.contains("binary") || lower.contains("elf") || lower.contains("so") || lower.contains("apk") || lower.contains("dex") -> {
                phases.add(Phase("Binary Analysis", "Disassemble and extract binary features", listOf("binary"), emptyMap()))
                phases.add(Phase("Security Scan", "Scan for vulnerabilities in binary", listOf("security"), emptyMap()))
                phases.add(Phase("String Analysis", "Extract and analyze interesting strings", listOf("binary", "web"), emptyMap()))
                phases.add(Phase("Documentation", "Generate binary analysis report", listOf("doc"), emptyMap()))
            }
            lower.contains("web") || lower.contains("search") || lower.contains("find") || lower.contains("document") -> {
                phases.add(Phase("Web Research", "Search web for relevant information", listOf("web"), emptyMap()))
                phases.add(Phase("Documentation", "Compile findings into report", listOf("doc"), emptyMap()))
            }
            lower.contains("test") || lower.contains("validate") || lower.contains("check") -> {
                phases.add(Phase("Validation", "Run validation checks on input", listOf("test", "code"), emptyMap()))
                phases.add(Phase("Security Scan", "Security validation", listOf("security"), emptyMap()))
            }
            else -> {
                // Generic analysis pipeline
                phases.add(Phase("Code Analysis", "Analyze code structure and quality", listOf("code"), emptyMap()))
                phases.add(Phase("Security Scan", "Scan for security issues", listOf("security"), emptyMap()))
                phases.add(Phase("Binary Check", "Check if binary analysis is needed", listOf("binary"), emptyMap()))
                phases.add(Phase("Documentation", "Generate comprehensive report", listOf("doc"), emptyMap()))
            }
        }
        
        // Always end with validation
        phases.add(Phase(
            name = "Result Validation",
            description = "Cross-check all agent outputs for consistency",
            assignedAgents = listOf("validator"),
            inputs = emptyMap()
        ))
        
        return TaskPlan(goal, phases, phases.size * 3000L)
    }

    /**
     * Dispatch tasks to 8 agents in parallel based on plan
     */
    private suspend fun dispatchToAgents(plan: TaskPlan, filePath: String?, userGoal: String): Map<String, String> {
        val results = mutableMapOf<String, String>()
        
        // Read file content if available
        val fileContent = filePath?.let { path ->
            try { java.io.File(path).readText().take(5000) } catch (e: Exception) { "Binary file" }
        } ?: ""
        
        // Parallel execution of all agents
        coroutineScope {
            val jobs = mutableListOf<Deferred<Pair<String, String>>>()
            
            // Code Analyzer
            if (plan.phases.any { it.assignedAgents.contains("code") }) {
                jobs.add(async(Dispatchers.IO) {
                    val result = try {
                        if (fileContent.isNotEmpty() && fileContent != "Binary file") {
                            val analysis = codeAgent.analyze(fileContent)
                            "Language: ${analysis.language}\nComplexity: ${analysis.complexity}\nFunctions: ${analysis.functions.size}\nIssues: ${analysis.issues.size}\nDependencies: ${analysis.dependencies.joinToString()}\nSummary: ${analysis.summary.take(500)}"
                        } else {
                            "Code analysis skipped (binary or no file)"
                        }
                    } catch (e: Exception) { "Code analysis error: ${e.message}" }
                    "Code Analyzer" to result
                })
            }
            
            // Security Inspector
            if (plan.phases.any { it.assignedAgents.contains("security") }) {
                jobs.add(async(Dispatchers.IO) {
                    val result = try {
                        val report = securityAgent.inspect(fileContent + "\n" + userGoal)
                        "Security Score: ${report.score}/100\nCritical: ${report.criticalCount}\nWarnings: ${report.warningCount}\nFindings: ${report.findings.size}\nTop Fix: ${report.recommendations.firstOrNull() ?: "None"}"
                    } catch (e: Exception) { "Security scan error: ${e.message}" }
                    "Security Inspector" to result
                })
            }
            
            // Binary Analyst
            if (plan.phases.any { it.assignedAgents.contains("binary") } && filePath != null) {
                jobs.add(async(Dispatchers.IO) {
                    val result = try {
                        val analysis = binaryAgent.analyzeBinary(filePath)
                        "Type: ${analysis.fileType}\nStrings: ${analysis.strings.size}\nSuspicious: ${analysis.suspiciousFunctions.size}\nImports: ${analysis.imports.size}\nExports: ${analysis.exports.size}"
                    } catch (e: Exception) { "Binary analysis error: ${e.message}" }
                    "Binary Analyst" to result
                })
            }
            
            // Web Scout
            if (plan.phases.any { it.assignedAgents.contains("web") }) {
                jobs.add(async(Dispatchers.IO) {
                    val result = try {
                        val search = webAgent.search(userGoal)
                        "Sources: ${search.sources.size}\nSummary: ${search.summary.take(500)}"
                    } catch (e: Exception) { "Web search error: ${e.message}" }
                    "Web Scout" to result
                })
            }
            
            // GitHub Operator
            if (plan.phases.any { it.assignedAgents.contains("github") }) {
                jobs.add(async(Dispatchers.IO) {
                    val result = try {
                        val repos = githubAgent.searchRepositories(userGoal.replace(" ", "+"))
                        repos.take(1000)
                    } catch (e: Exception) { "GitHub search error: ${e.message}" }
                    "GitHub Operator" to result
                })
            }
            
            // Documentation Agent
            jobs.add(async(Dispatchers.IO) {
                val result = try {
                    // Doc agent waits for other results, so we give it a preliminary summary
                    "Documentation agent ready to compile final report after all agents complete."
                } catch (e: Exception) { "Documentation error: ${e.message}" }
                "Documentation Agent" to result
            })
            
            // Test Runner
            jobs.add(async(Dispatchers.IO) {
                val result = try {
                    if (filePath != null) {
                        val test = testAgent.validateFileStructure(filePath, engine.getFileType(filePath))
                        "Tests: ${test.passed + test.failed}\nPassed: ${test.passed}\nFailed: ${test.failed}\nDuration: ${test.durationMs}ms"
                    } else {
                        "No file to validate"
                    }
                } catch (e: Exception) { "Validation error: ${e.message}" }
                "Test Runner" to result
            })
            
            // Wait for all
            jobs.awaitAll().forEach { (name, output) ->
                results[name] = output
                audit.log(name, "agent_complete", output.take(100), "success")
            }
        }
        
        // Generate documentation after all results are in
        val docResult = try {
            val codeAnalysis = results["Code Analyzer"]?.let { parseCodeAnalysis(it) }
            val secReport = results["Security Inspector"]?.let { parseSecurityReport(it) }
            val binaryInfo = results["Binary Analyst"]?.let { parseBinaryInfo(it) }
            docAgent.generateReport("Analysis Report", codeAnalysis, secReport, binaryInfo, userGoal)
        } catch (e: Exception) { "Report generation error: ${e.message}" }
        results["Documentation Agent"] = docResult
        
        return results
    }

    private fun generateFinalReport(validation: ResultValidatorAgent.ValidationReport, userGoal: String, filePath: String?): String {
        val sb = StringBuilder()
        sb.append("# Hermes Analyzer - Final Report\n\n")
        sb.append("**Goal**: $userGoal\n")
        sb.append("**File**: ${filePath ?: "None"}\n")
        sb.append("**Validation Score**: ${validation.consistencyScore}/100\n")
        sb.append("**Confidence**: ${"%.0f".format(validation.confidence * 100)}%\n\n")
        sb.append("---\n\n")
        
        if (validation.conflicts.isNotEmpty()) {
            sb.append("## Cross-Agent Conflicts\n\n")
            validation.conflicts.forEach { c ->
                sb.append("**${c.topic}**\n")
                c.resolutions.forEach { sb.append("- $it\n") }
                sb.append("\n")
            }
        }
        
        sb.append(validation.mergedReport)
        return sb.toString()
    }

    // Helper parsers
    private fun parseCodeAnalysis(text: String): CodeAnalyzerAgent.CodeAnalysis? {
        return CodeAnalyzerAgent.CodeAnalysis(
            language = Regex("Language: (\w+)").find(text)?.groupValues?.get(1) ?: "unknown",
            complexity = Regex("Complexity: (\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            issues = emptyList(),
            functions = emptyList(),
            dependencies = emptyList(),
            summary = text
        )
    }

    private fun parseSecurityReport(text: String): SecurityInspectorAgent.SecurityReport? {
        return SecurityInspectorAgent.SecurityReport(
            score = Regex("Score: (\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 100,
            criticalCount = Regex("Critical: (\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            warningCount = Regex("Warnings: (\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            infoCount = 0,
            findings = emptyList(),
            recommendations = emptyList()
        )
    }

    private fun parseBinaryInfo(text: String): Map<String, String> {
        return mapOf(
            "type" to (Regex("Type: ([\w/]+)").find(text)?.groupValues?.get(1) ?: "unknown"),
            "strings" to (Regex("Strings: (\d+)").find(text)?.groupValues?.get(1) ?: "0"),
            "suspicious" to (Regex("Suspicious: (\d+)").find(text)?.groupValues?.get(1) ?: "0")
        )
    }
}
