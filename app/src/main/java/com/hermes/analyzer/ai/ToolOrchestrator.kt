package com.hermes.analyzer.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Tool Orchestrator - Parallel Tool Execution Engine
 * 
 * AgentPlanner가 생성한 실행 계획을 병렬/순차로 실행합니다.
 */
class ToolOrchestrator(
    private val pluginEngine: PluginEngine
) {

    data class StepResult(
        val step: Int,
        val description: String,
        val toolResults: Map<String, ToolResult>,
        val startTime: Long,
        val endTime: Long,
        val success: Boolean
    ) {
        val durationMs: Long get() = endTime - startTime
    }

    data class ToolResult(
        val toolId: String,
        val output: String,
        val success: Boolean,
        val error: String? = null
    )

    interface ProgressCallback {
        fun onPlanStarted(totalSteps: Int, filePath: String) {}
        fun onStepStarted(step: Int, description: String, toolIds: List<String>)
        fun onToolStarted(step: Int, toolId: String)
        fun onToolCompleted(step: Int, toolId: String, success: Boolean)
        fun onStepCompleted(step: Int, result: StepResult)
        fun onPlanCompleted(results: List<StepResult>, totalDurationMs: Long)
        fun onError(step: Int, error: String)
    }

    suspend fun executePlan(
        plan: List<AgentPlanner.PlanStep>,
        filePath: String,
        progressCallback: ProgressCallback? = null
    ): List<StepResult> {
        val results = mutableListOf<StepResult>()
        val completedSteps = mutableSetOf<Int>()
        val mutex = Mutex()
        val startTime = System.currentTimeMillis()
        
        val sortedPlan = sortPlanByDependencies(plan)
        
        progressCallback?.onPlanStarted(sortedPlan.size, filePath)
        
        for (step in sortedPlan) {
            if (step.dependsOn.isNotEmpty()) {
                val missing = step.dependsOn.filter { it !in completedSteps }
                if (missing.isNotEmpty()) {
                    val err = "Step ${step.step} waiting for steps: ${missing.joinToString()}"
                    progressCallback?.onError(step.step, err)
                    continue
                }
            }
            
            progressCallback?.onStepStarted(step.step, step.description, step.toolIds)
            
            val stepResult = if (step.parallel) {
                executeStepParallel(step, filePath, progressCallback)
            } else {
                executeStepSequential(step, filePath, progressCallback)
            }
            
            mutex.withLock {
                results.add(stepResult)
                completedSteps.add(step.step)
            }
            
            progressCallback?.onStepCompleted(step.step, stepResult)
        }
        
        val totalDuration = System.currentTimeMillis() - startTime
        progressCallback?.onPlanCompleted(results, totalDuration)
        
        return results
    }

    private suspend fun executeStepParallel(
        step: AgentPlanner.PlanStep,
        filePath: String,
        progressCallback: ProgressCallback?
    ): StepResult {
        val stepStart = System.currentTimeMillis()
        val toolResults = mutableMapOf<String, ToolResult>()
        
        coroutineScope {
            step.toolIds.map { toolId ->
                async(Dispatchers.IO) {
                    progressCallback?.onToolStarted(step.step, toolId)
                    
                    val result = try {
                        val params = mapOf("file" to filePath, "target" to filePath)
                        val output = pluginEngine.executePlugin(toolId, params)
                        ToolResult(toolId, output, true)
                    } catch (e: Exception) {
                        ToolResult(toolId, "", false, e.message)
                    }
                    
                    progressCallback?.onToolCompleted(step.step, toolId, result.success)
                    toolId to result
                }
            }.awaitAll().toMap(toolResults)
        }
        
        val success = toolResults.values.all { it.success }
        return StepResult(
            step = step.step,
            description = step.description,
            toolResults = toolResults,
            startTime = stepStart,
            endTime = System.currentTimeMillis(),
            success = success
        )
    }

    private suspend fun executeStepSequential(
        step: AgentPlanner.PlanStep,
        filePath: String,
        progressCallback: ProgressCallback?
    ): StepResult {
        val stepStart = System.currentTimeMillis()
        val toolResults = mutableMapOf<String, ToolResult>()
        
        for (toolId in step.toolIds) {
            progressCallback?.onToolStarted(step.step, toolId)
            
            val result = try {
                val params = mapOf("file" to filePath, "target" to filePath)
                val output = pluginEngine.executePlugin(toolId, params)
                ToolResult(toolId, output, true)
            } catch (e: Exception) {
                ToolResult(toolId, "", false, e.message)
            }
            
            toolResults[toolId] = result
            progressCallback?.onToolCompleted(step.step, toolId, result.success)
        }
        
        val success = toolResults.values.all { it.success }
        return StepResult(
            step = step.step,
            description = step.description,
            toolResults = toolResults,
            startTime = stepStart,
            endTime = System.currentTimeMillis(),
            success = success
        )
    }

    private fun sortPlanByDependencies(
        plan: List<AgentPlanner.PlanStep>
    ): List<AgentPlanner.PlanStep> {
        return plan.sortedBy { it.step }
    }

    fun synthesizeReport(results: List<StepResult>): String {
        val sb = StringBuilder()
        sb.append("# Autonomous Analysis Report\n\n")
        
        val totalDuration = results.sumOf { it.durationMs }
        val totalTools = results.sumOf { it.toolResults.size }
        val successTools = results.sumOf { r -> r.toolResults.values.count { it.success } }
        
        sb.append("## Summary\n")
        sb.append("- **Total Steps**: ${results.size}\n")
        sb.append("- **Total Duration**: ${formatDuration(totalDuration)}\n")
        sb.append("- **Tools Executed**: $totalTools\n")
        sb.append("- **Success Rate**: $successTools/$totalTools (${(successTools * 100 / maxOf(totalTools, 1))}%)\n\n")
        
        for (result in results) {
            sb.append("## Step ${result.step}: ${result.description}\n")
            sb.append("Duration: ${formatDuration(result.durationMs)}\n\n")
            
            for ((toolId, toolResult) in result.toolResults) {
                val icon = if (toolResult.success) "OK" else "FAIL"
                sb.append("### [$icon] $toolId\n")
                if (toolResult.success) {
                    sb.append("```\n")
                    sb.append(toolResult.output.take(2000))
                    sb.append("\n```\n")
                } else {
                    sb.append("**Error**: ${toolResult.error ?: "Unknown error"}\n")
                }
                sb.append("\n")
            }
        }
        
        return sb.toString()
    }

    fun resultsToJson(results: List<StepResult>): String {
        val json = JSONObject()
        val stepsArray = org.json.JSONArray()
        
        for (result in results) {
            val stepObj = JSONObject()
            stepObj.put("step", result.step)
            stepObj.put("description", result.description)
            stepObj.put("duration_ms", result.durationMs)
            stepObj.put("success", result.success)
            
            val toolsObj = JSONObject()
            for ((toolId, toolResult) in result.toolResults) {
                val toolObj = JSONObject()
                toolObj.put("success", toolResult.success)
                toolObj.put("output", toolResult.output.take(1000))
                if (toolResult.error != null) toolObj.put("error", toolResult.error)
                toolsObj.put(toolId, toolObj)
            }
            stepObj.put("tools", toolsObj)
            stepsArray.put(stepObj)
        }
        
        json.put("steps", stepsArray)
        json.put("total_duration_ms", results.sumOf { it.durationMs })
        return json.toString(2)
    }

    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60000 -> "${ms / 1000}s"
            else -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
        }
    }
}
