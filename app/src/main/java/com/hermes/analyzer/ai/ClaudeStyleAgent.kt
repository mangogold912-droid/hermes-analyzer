package com.hermes.analyzer.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hermes.analyzer.sandbox.SandboxManager
import com.hermes.analyzer.ai.LocalLLMEngine
import com.hermes.analyzer.ai.ContextQueryEngine
import com.hermes.analyzer.ai.ToolRouter
import com.hermes.analyzer.ai.ToolCall
import com.hermes.analyzer.ai.ToolResult

/**
 * Represents a single step in the agent's reasoning loop.
 */
data class AgentStep(
    val stepNumber: Int,
    val reasoning: String,
    val toolCalls: List<ToolCall>,
    val toolResults: List<ToolResult>,
    val observation: String
)

/**
 * Represents an entire agent session from start to finish.
 */
data class AgentSession(
    val sessionId: String,
    val userQuery: String,
    val filePath: String?,
    val steps: MutableList<AgentStep>,
    var status: String, // thinking, acting, observing, complete, error
    var finalAnswer: String? = null
)

/**
 * Claude-Style Agent implementing the Think -> Act -> Observe -> Repeat loop.
 *
 * This is the core autonomous AI agent that:
 * 1. Receives user natural language input + file context
 * 2. Reasons about what needs to be done (Think)
 * 3. Decides which tools to call (Act) - using ToolRouter
 * 4. Observes tool execution results
 * 5. Repeats until the task is complete
 * 6. Synthesizes a final natural language answer
 */
class ClaudeStyleAgent(private val context: Context) {
    private val toolRouter = ToolRouter(context)
    private val contextEngine = ContextQueryEngine(context)
    private val localLLM = LocalLLMEngine(context)
    private val sandbox = SandboxManager(context)
    private val history = mutableListOf<AgentSession>()

    /**
     * Main entry point. Creates a session, runs the loop up to maxSteps.
     *
     * @param userQuery The user's natural language query.
     * @param filePath Optional attached file path for context.
     * @param maxSteps Maximum number of Think-Act-Observe iterations.
     * @return The completed [AgentSession] with finalAnswer populated.
     */
    suspend fun runAgent(
        userQuery: String,
        filePath: String? = null,
        maxSteps: Int = 10
    ): AgentSession = withContext(Dispatchers.IO) {
        val session = AgentSession(
            sessionId = System.currentTimeMillis().toString(),
            userQuery = userQuery,
            filePath = filePath,
            steps = mutableListOf(),
            status = "thinking"
        )

        try {
            var completedEarly = false
            for (stepNum in 1..maxSteps) {
                if (completedEarly) break

                // --- THINK ---
                session.status = "thinking"
                val reasoning = think(userQuery, filePath, session.steps)

                // --- ACT ---
                session.status = "acting"
                val toolCalls = act(reasoning, userQuery, filePath)

                if (toolCalls.isEmpty()) {
                    val observation = "분석 완료. 추가 도구 호출이 필요하지 않습니다.\nAnalysis complete. No additional tool calls needed."
                    session.steps.add(
                        AgentStep(
                            stepNumber = stepNum,
                            reasoning = reasoning,
                            toolCalls = emptyList(),
                            toolResults = emptyList(),
                            observation = observation
                        )
                    )
                    completedEarly = true
                    break
                }

                // --- EXECUTE TOOLS ---
                val toolResults = executeTools(toolCalls)

                // --- OBSERVE ---
                session.status = "observing"
                val observation = observe(toolResults)

                session.steps.add(
                    AgentStep(
                        stepNumber = stepNum,
                        reasoning = reasoning,
                        toolCalls = toolCalls,
                        toolResults = toolResults,
                        observation = observation
                    )
                )

                // Check for natural completion signal (no errors and observation indicates done)
                val hasErrors = toolResults.any { it.error != null || it.success == false }
                if (!hasErrors && (observation.contains("complete") || observation.contains("완료"))) {
                    completedEarly = true
                }
            }

            // --- SYNTHESIZE FINAL ANSWER ---
            session.status = "complete"
            session.finalAnswer = synthesizeAnswer(session)
        } catch (e: Exception) {
            session.status = "error"
            session.finalAnswer = "분석 중 오류가 발생했습니다: ${e.message}\nAn error occurred during analysis: ${e.message}"
        }

        history.add(session)
        session
    }

    /**
     * Builds a reasoning prompt and asks the LLM to plan the analysis.
     *
     * @param query The user's query.
     * @param filePath Optional file path for context lookup.
     * @param previousSteps Prior steps to include in the prompt for continuity.
     * @return The LLM's reasoning text.
     */
    private fun think(
        query: String,
        filePath: String?,
        previousSteps: List<AgentStep>
    ): String {
        val fileContext = if (filePath != null) {
            try {
                contextEngine.queryFileContext(filePath)
            } catch (e: Exception) {
                "파일 컨텍스트를 가져올 수 없습니다: ${e.message}\nUnable to retrieve file context: ${e.message}"
            }
        } else {
            "첨부 파일 없음\nNo attached file."
        }

        val sb = StringBuilder()
        sb.append("You are Hermes Analyzer AI. The user asks: \"$query\"\n")
        sb.append("Attached file: ${filePath ?: "none"}\n")
        sb.append("$fileContext\n")
        sb.append("\n")

        if (previousSteps.isNotEmpty()) {
            sb.append("Previous steps taken:\n")
            previousSteps.forEach { step ->
                sb.append("Step ${step.stepNumber}: ${step.reasoning}\n")
                sb.append("  Observation: ${step.observation}\n")
            }
            sb.append("\n")
        }

        sb.append("Think step by step. What analysis steps are needed? What tools should you use?\n")
        sb.append("Do NOT answer yet. Only describe your reasoning plan.\n")
        sb.append("한국어로 생각하고 영어로도 요약해 주세요. Think in Korean and also summarize in English.")

        return localLLM.generateResponse(sb.toString())
    }

    /**
     * Builds an action prompt asking the LLM to output tool calls.
     *
     * @param reasoning The reasoning text from the think phase.
     * @param query The user's original query.
     * @param filePath Optional attached file path.
     * @return Parsed list of [ToolCall] objects.
     */
    private fun act(
        reasoning: String,
        query: String,
        filePath: String?
    ): List<ToolCall> {
        val toolDescriptions = try {
            toolRouter.getSystemPromptWithTools()
        } catch (e: Exception) {
            "도구 설명을 가져올 수 없습니다.\nUnable to retrieve tool descriptions: ${e.message}"
        }

        val sb = StringBuilder()
        sb.append("Based on your reasoning, now call the appropriate tools.\n")
        sb.append("User query: \"$query\"\n")
        sb.append("Attached file: ${filePath ?: "none"}\n")
        sb.append("Your reasoning: $reasoning\n")
        sb.append("\n")
        sb.append("Available tools:\n")
        sb.append("$toolDescriptions\n")
        sb.append("\n")
        sb.append("Output your tool calls in this exact format:\n")
        sb.append("<tool_call>\n")
        sb.append("name: TOOL_NAME\n")
        sb.append("arguments:\n")
        sb.append("  param1: value1\n")
        sb.append("  param2: value2\n")
        sb.append("</tool_call>\n")
        sb.append("\n")
        sb.append("You may call multiple tools. Be specific with file paths.\n")
        sb.append("파일 경로는 절대 경로로 지정하세요. Use absolute file paths.")

        val llmOutput = localLLM.generateResponse(sb.toString())
        return try {
            toolRouter.parseToolCalls(llmOutput)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Executes each parsed tool call sequentially via the ToolRouter.
     *
     * @param calls List of tool calls to execute.
     * @return List of [ToolResult] matching each call.
     */
    private fun executeTools(calls: List<ToolCall>): List<ToolResult> {
        return calls.map { call ->
            try {
                toolRouter.executeTool(call)
            } catch (e: Exception) {
                ToolResult(
                    toolName = call.name,
                    success = false,
                    error = "도구 실행 오류: ${e.message}\nTool execution error: ${e.message}",
                    data = null
                )
            }
        }
    }

    /**
     * Formats tool execution results into a concise observation string.
     *
     * @param results Results from the execute phase.
     * @return Observation text.
     */
    private fun observe(results: List<ToolResult>): String {
        return try {
            toolRouter.formatToolResults(results)
        } catch (e: Exception) {
            val sb = StringBuilder()
            sb.append("결과 관찰 오류: ${e.message}\nObservation error: ${e.message}\n")
            results.forEach { result ->
                sb.append("- ${result.toolName}: ")
                if (result.success) {
                    sb.append("성공 / Success\n")
                } else {
                    sb.append("실패 / Failure: ${result.error ?: "Unknown error"}\n")
                }
            }
            sb.toString()
        }
    }

    /**
     * Builds a final synthesis prompt and asks the LLM for a natural language answer.
     *
     * @param session The completed agent session containing all steps.
     * @return The final synthesized answer string.
     */
    private fun synthesizeAnswer(session: AgentSession): String {
        val sb = StringBuilder()
        sb.append("You have completed the analysis. Here is what you did:\n")
        sb.append("\n")

        session.steps.forEach { step ->
            sb.append("Step ${step.stepNumber}:\n")
            sb.append("Reasoning: ${step.reasoning}\n")
            if (step.toolCalls.isNotEmpty()) {
                sb.append("Tools called: ${step.toolCalls.joinToString { it.name }}\n")
            }
            sb.append("Observation: ${step.observation}\n")
            sb.append("\n")
        }

        sb.append("User original question: \"${session.userQuery}\"\n")
        sb.append("Attached file: ${session.filePath ?: "none"}\n")
        sb.append("\n")
        sb.append("Now provide a clear, detailed answer to the user's original question in natural language.\n")
        sb.append("Use Korean if the user asked in Korean. Include technical details.\n")
        sb.append("사용자가 한국어로 질문했다면 한국어로 답변하세요. 기술적 세부사항을 포함하세요.")

        return localLLM.generateResponse(sb.toString())
    }
}
