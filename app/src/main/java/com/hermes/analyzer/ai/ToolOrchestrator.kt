package com.hermes.analyzer.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Tool Orchestrator - Parallel Tool Execution Engine
 * 
 * AgentPlanner가 생성한 실행 계획을 병렬/순차로 실행합니다.
 * 
 * 핵심 기능:
 * 1. 병렬 그룹별 동시 실행 (kotlinx.coroutines)
 * 2. 단계 간 의존성 관리 (DAG execution)
 * 3. 실시간 진행 상황 콜백
 * 4. 결과 수집 및 종합
 */
class ToolOrchestrator(
    private val pluginEngine: PluginEngine
) {

    /**
     * 실행 결과
     */
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

    /**
     * 진행 상황 콜백
     */
    interface ProgressCallback {
        fun onStepStarted(step: Int, description: String, toolIds: List<String>)
        fun onToolStarted(step: Int, toolId: String)
        fun onToolCompleted(step: Int, toolId: String, success: Boolean)
        fun onStepCompleted(step: Int, result: StepResult)
        fun onPlanCompleted(results: List<StepResult>, totalDurationMs: Long)
        fun onError(step: Int, error: String)
    }

    /**
     * 전체 계획 실행 (메인 진입점)
     */
    suspend fun executePlan(
        plan: List<AgentPlanner.PlanStep>,
        filePath: String,
        progressCallback: ProgressCallback? = null
    ): List<StepResult> {
        val results = mutableListOf<StepResult>()
        val completedSteps = mutableSetOf<Int>()
        val mutex = Mutex()
        val startTime = System.currentTimeMillis()
        
        // 계횑을 의존성 기반으로 정렬
        val sortedPlan = sortPlanByDependencies(plan)
        
        progressCallback?.let { cb ->
            cb.onPlanStarted(sortedPlan.size, filePath)
        }
        
        for (step in sortedPlan) {
            // 의존성 확인
            if (step.dependsOn.isNotEmpty()) {
                val missing = step.dependsOn.filter { it !in completedSteps }
                if (missing.isNotEmpty()) {
                    val err = "Step ${step.step} waiting for steps: ${missing.joinToString()}"
                    progressCallback?.onError(step.step, err)
                    continue
                }
            }
            
            progressCallback?.onStepStarted(step.step, step.description, step.toolIds)
            
            // 단계 실행
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

    /**
     * 병렬 단계 실행 - 모든 도구를 동시에 실행
     */
    private suspend fun executeStepParallel(
        step: AgentPlanner.PlanStep,
        filePath: String,
        progressCallback: ProgressCallback?
    ): StepResult {
        val stepStart = System.currentTimeMillis()
        val toolResults = mutableMapOf<String, ToolResult>()
        
        // 코루틴으로 병렬 실행
        coroutineScope {
            step.toolIds.map { toolId ->
                async(Dispatchers.IO) {
                    progressCallback?.onToolStarted(step.step, toolId)
                    
                    val result = try {
                        val output = pluginEngine.executePlugin(toolId, filePath)
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

    /**
     * 순차 단계 실행 - 도구를 하나씩 실행
     */
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
                val output = pluginEngine.executePlugin(toolId, filePath)
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

    /**
     * 의존성 기반 계획 정렬 (Topological Sort)
     */
    private fun sortPlanByDependencies(
        plan: List<AgentPlanner.PlanStep>
    ): List<AgentPlanner.PlanStep> {
        // 이미 step 번호 순서대로 되어 있으므로 의존성 체크만
        // 실제로는 DAG 정렬이 필요하지만, 현재 구현에서는
        // step 번호가 의존성을 반영하도록 생성됨
        return plan.sortedBy { it.step }
    }

    /**
     * 결과를 종합 보고서로 변환
     */
    fun synthesizeReport(results: List<StepResult>): String {
        val sb = StringBuilder()
        sb.appendLine("# Autonomous Analysis Report")
        sb.appendLine()
        
        val totalDuration = results.sumOf { it.durationMs }
        val totalTools = results.sumOf { it.toolResults.size }
        val successTools = results.sumOf { r -> r.toolResults.values.count { it.success } }
        
        sb.appendLine("## Summary")
        sb.appendLine("- **Total Steps**: ${results.size}")
        sb.appendLine("- **Total Duration**: ${formatDuration(totalDuration)}")
        sb.appendLine("- **Tools Executed**: $totalTools")
        sb.appendLine("- **Success Rate**: $successTools/$totalTools (${(successTools * 100 / maxOf(totalTools, 1))}%)")
        sb.appendLine()
        
        for (result in results) {
            sb.appendLine("## Step ${result.step}: ${result.description}")
            sb.appendLine("Duration: ${formatDuration(result.durationMs)}")
            sb.appendLine()
            
            for ((toolId, toolResult) in result.toolResults) {
                val icon = if (toolResult.success) "✅" else "❌"
                sb.appendLine("### $icon $toolId")
                if (toolResult.success) {
                    sb.appendLine("```")
                    sb.appendLine(toolResult.output.take(2000))
                    sb.appendLine("```")
                } else {
                    sb.appendLine("**Error**: ${toolResult.error ?: "Unknown error"}")
                }
                sb.appendLine()
            }
        }
        
        return sb.toString()
    }

    /**
     * 결과를 JSON으로 변환
     */
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

    // 확장 콜백 메서드
    interface ProgressCallback {
        fun onPlanStarted(totalSteps: Int, filePath: String) {}
        fun onStepStarted(step: Int, description: String, toolIds: List<String>)
        fun onToolStarted(step: Int, toolId: String)
        fun onToolCompleted(step: Int, toolId: String, success: Boolean)
        fun onStepCompleted(step: Int, result: StepResult)
        fun onPlanCompleted(results: List<StepResult>, totalDurationMs: Long)
        fun onError(step: Int, error: String)
    }
}
