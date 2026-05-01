package com.hermes.analyzer.ai

/**
 * Reflection Engine - Self-Evaluation and Re-planning
 * 
 * 중간 결과를 평가하고, 목표 달성에 부족하면:
 * 1. 추가 도구 추천
 * 2. 실행 계횑 재수정
 * 3. 최종 목표 달성 여부 판단
 */
class ReflectionEngine(
    private val planner: AgentPlanner,
    private val pluginEngine: PluginEngine
) {

    /**
     * 평가 결과
     */
    data class Evaluation(
        val goalAchieved: Boolean,
        val confidence: Float,      // 0.0 ~ 1.0
        val gaps: List<String>,     // 부족한 부분들
        val recommendations: List<String>,  // 추가 도구 추천
        val suggestedTools: List<String>,   // 실행할 추가 도구들
        val reasoning: String       // 평가 이유
    )

    /**
     * 단계 결과 평가
     */
    fun evaluateStep(
        stepResult: ToolOrchestrator.StepResult,
        originalGoal: String
    ): Evaluation {
        val gaps = mutableListOf<String>()
        val recommendations = mutableListOf<String>()
        val suggestedTools = mutableListOf<String>()
        
        // 1. 각 도구 결과의 품질 평가
        for ((toolId, toolResult) in stepResult.toolResults) {
            if (!toolResult.success) {
                gaps.add("Tool '$toolId' failed: ${toolResult.error}")
                recommendations.add("Retry $toolId or use alternative")
                // 실패한 도구의 대안 찾기
                findAlternative(toolId)?.let { alt ->
                    suggestedTools.add(alt)
                    recommendations.add("Alternative: $alt")
                }
                continue
            }
            
            // 결과가 비어있거나 너무 짧은 경우
            if (toolResult.output.length < 50) {
                gaps.add("Tool '$toolId' returned insufficient data")
            }
            
            // 중요한 발견이 있는지 확인
            val findings = countFindings(toolResult.output)
            if (findings < 3) {
                gaps.add("Tool '$toolId' found few meaningful results ($findings items)")
            }
        }
        
        // 2. 목표별 특화 평가
        val lowerGoal = originalGoal.lowercase()
        
        // 보안 관련 목표
        if (hasSecurityGoal(lowerGoal)) {
            val hasVuln = stepResult.toolResults.values.any { 
                it.output.contains("VULNERABILITY", ignoreCase = true) ||
                it.output.contains("CVE-", ignoreCase = true) ||
                it.output.contains("CRITICAL", ignoreCase = true)
            }
            if (!hasVuln) {
                gaps.add("No critical vulnerabilities found - may need deeper scan")
                suggestedTools.add("capstone_disasm")
                suggestedTools.add("ida_mcp_bridge")
            }
        }
        
        // 암호화 관련 목표
        if (hasCryptoGoal(lowerGoal)) {
            val hasCrypto = stepResult.toolResults.values.any {
                it.output.contains("AES", ignoreCase = true) ||
                it.output.contains("RSA", ignoreCase = true) ||
                it.output.contains("cipher", ignoreCase = true) ||
                it.output.contains("key", ignoreCase = true)
            }
            if (!hasCrypto) {
                gaps.add("No crypto patterns detected - try string-level analysis")
                suggestedTools.add("string_extractor")
                suggestedTools.add("capa_floss")
            }
        }
        
        // 네트워크 관련 목표
        if (hasNetworkGoal(lowerGoal)) {
            val hasNetwork = stepResult.toolResults.values.any {
                it.output.contains("http", ignoreCase = true) ||
                it.output.contains("api", ignoreCase = true) ||
                it.output.contains("socket", ignoreCase = true) ||
                it.output.contains("domain", ignoreCase = true)
            }
            if (!hasNetwork) {
                gaps.add("Limited network findings - may need dynamic analysis")
                suggestedTools.add("objection_tool")
                suggestedTools.add("burp_zap_proxy")
            }
        }
        
        // 3. 종합 점수 계산
        val totalTools = stepResult.toolResults.size
        val successTools = stepResult.toolResults.values.count { it.success }
        val baseScore = if (totalTools > 0) successTools.toFloat() / totalTools else 0f
        
        // 갭 감점
        val gapPenalty = gaps.size * 0.15f
        val confidence = (baseScore - gapPenalty).coerceIn(0f, 1f)
        
        // 목표 달성 판정 (confidence > 0.7이고 갭이 없으면)
        val goalAchieved = confidence > 0.7f && gaps.isEmpty()
        
        // 추천 생성
        if (suggestedTools.isNotEmpty()) {
            recommendations.add("Run additional tools: ${suggestedTools.distinct().joinToString()}")
        }
        
        val reasoning = buildString {
            append("Evaluated ${stepResult.toolResults.size} tools: ")
            append("$successTools success, ${totalTools - successTools} failed. ")
            append("Found ${gaps.size} gaps. ")
            append("Confidence: ${(confidence * 100).toInt()}%. ")
            append(if (goalAchieved) "Goal appears achieved." else "Further analysis recommended.")
        }
        
        return Evaluation(
            goalAchieved = goalAchieved,
            confidence = confidence,
            gaps = gaps,
            recommendations = recommendations.distinct(),
            suggestedTools = suggestedTools.distinct(),
            reasoning = reasoning
        )
    }

    /**
     * 전체 결과 평가 및 최종 판단
     */
    fun evaluateOverall(
        allResults: List<ToolOrchestrator.StepResult>,
        originalGoal: String
    ): Evaluation {
        val allGaps = mutableListOf<String>()
        val allRecommendations = mutableListOf<String>()
        val allSuggestedTools = mutableListOf<String>()
        var totalConfidence = 0f
        
        // 각 단계 평가
        for (result in allResults) {
            val eval = evaluateStep(result, originalGoal)
            allGaps.addAll(eval.gaps)
            allRecommendations.addAll(eval.recommendations)
            allSuggestedTools.addAll(eval.suggestedTools)
            totalConfidence += eval.confidence
        }
        
        val avgConfidence = if (allResults.isNotEmpty()) 
            totalConfidence / allResults.size else 0f
        
        // 전체 목표 달성 여부
        val goalAchieved = avgConfidence > 0.7f && allGaps.isEmpty()
        
        val reasoning = buildString {
            append("Overall evaluation of ${allResults.size} steps: ")
            append("Average confidence ${(avgConfidence * 100).toInt()}%. ")
            append("Total gaps: ${allGaps.size}. ")
            append("Suggested additional tools: ${allSuggestedTools.distinct().size}. ")
            append(if (goalAchieved) "Objective ACHIEVED." 
                   else "Objective NOT fully achieved - recommend additional analysis.")
        }
        
        return Evaluation(
            goalAchieved = goalAchieved,
            confidence = avgConfidence,
            gaps = allGaps.distinct(),
            recommendations = allRecommendations.distinct(),
            suggestedTools = allSuggestedTools.distinct(),
            reasoning = reasoning
        )
    }

    /**
     * 부족한 부분을 보완하기 위한 추가 계횑 생성
     */
    fun createSupplementaryPlan(
        evaluation: Evaluation,
        originalPlan: List<AgentPlanner.PlanStep>
    ): List<AgentPlanner.PlanStep>? {
        if (evaluation.suggestedTools.isEmpty()) return null
        
        val nextStep = (originalPlan.maxOfOrNull { it.step } ?: 0) + 1
        
        return listOf(
            AgentPlanner.PlanStep(
                step = nextStep,
                description = "Supplementary analysis (recommended by reflection)",
                toolIds = evaluation.suggestedTools,
                parallel = true,
                dependsOn = originalPlan.map { it.step },
                rationale = "Additional tools to fill gaps: ${evaluation.gaps.joinToString("; ")}"
            )
        )
    }

    // === 남부 메서드들 ===
    
    private fun countFindings(output: String): Int {
        var count = 0
        val indicators = listOf("Found:", "Detected:", "[+]", "[!]", "Result:", "===", "Entry:", "Function:", "Class:")
        for (indicator in indicators) {
            count += output.split(indicator).size - 1
        }
        return count.coerceAtMost(100) // cap at 100
    }
    
    private fun findAlternative(toolId: String): String? {
        return when (toolId) {
            "ida_mcp_bridge" -> "radare2_wrapper"
            "radare2_wrapper" -> "capstone_disasm"
            "yara_scanner" -> "capa_floss"
            "mobsf_scanner" -> "quark_engine"
            "capa_floss" -> "detect_it_easy"
            "crypto_hunter" -> "string_extractor"
            "network_analyzer" -> "wireshark_analyzer"
            "objection_tool" -> "frida_generator"
            "ghidra_analyzer" -> "binary_ninja"
            "jadx_decompiler" -> "dex_decompiler"
            else -> null
        }
    }
    
    private fun hasSecurityGoal(goal: String): Boolean {
        return hasAny(goal, listOf("security", "보안", "vuln", "취약점", "exploit", "penetration", "감사"))
    }
    
    private fun hasCryptoGoal(goal: String): Boolean {
        return hasAny(goal, listOf("crypto", "암호", "encryption", "aes", "rsa", "cipher", "key"))
    }
    
    private fun hasNetworkGoal(goal: String): Boolean {
        return hasAny(goal, listOf("network", "네트워크", "traffic", "통신", "packet", "패킷", "http"))
    }
    
    private fun hasAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it.lowercase()) }
    }
}
