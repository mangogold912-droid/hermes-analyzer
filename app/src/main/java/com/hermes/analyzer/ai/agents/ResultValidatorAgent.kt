package com.hermes.analyzer.ai.agents

import com.hermes.analyzer.ai.AdvancedAIEngine

/**
 * Agent 8: Result Validator
 * 전문 역할: 모든 보조 AI 출력의 일관성 검증, 최종 보고서 종합, 교차 검증
 */
class ResultValidatorAgent(private val engine: AdvancedAIEngine) {
    data class ValidationReport(
        val consistencyScore: Int,
        val conflicts: List<Conflict>,
        val mergedReport: String,
        val confidence: Double
    )

    data class Conflict(
        val agents: List<String>,
        val topic: String,
        val resolutions: List<String>
    )

    fun validateAndMerge(agentResults: Map<String, String>): ValidationReport {
        val conflicts = detectConflicts(agentResults)
        val consistency = calculateConsistency(agentResults, conflicts)
        val merged = mergeResults(agentResults, conflicts)
        
        return ValidationReport(
            consistencyScore = consistency,
            conflicts = conflicts,
            mergedReport = merged,
            confidence = consistency / 100.0
        )
    }

    private fun detectConflicts(results: Map<String, String>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        val entries = results.toList()
        
        for (i in entries.indices) {
            for (j in i + 1 until entries.size) {
                val (name1, result1) = entries[i]
                val (name2, result2) = entries[j]
                
                // Check for contradictory severity ratings
                val sev1 = extractSeverity(result1)
                val sev2 = extractSeverity(result2)
                if (sev1 != null && sev2 != null && sev1 != sev2) {
                    conflicts.add(Conflict(
                        listOf(name1, name2),
                        "Severity mismatch",
                        listOf("$name1: $sev1", "$name2: $sev2", "Take highest severity")
                    ))
                }
                
                // Check for duplicate findings
                val finding1 = extractFinding(result1)
                val finding2 = extractFinding(result2)
                if (finding1.isNotEmpty() && finding1 == finding2) {
                    conflicts.add(Conflict(
                        listOf(name1, name2),
                        "Duplicate finding: $finding1",
                        listOf("Merge into single report")
                    ))
                }
            }
        }
        return conflicts
    }

    private fun calculateConsistency(results: Map<String, String>, conflicts: List<Conflict>): Int {
        val baseScore = 100
        val penalty = conflicts.size * 15
        val emptyPenalty = results.count { it.value.isEmpty() } * 20
        return maxOf(0, baseScore - penalty - emptyPenalty)
    }

    private fun mergeResults(results: Map<String, String>, conflicts: List<Conflict>): String {
        val sb = StringBuilder()
        sb.append("# Final Consolidated Report\n\n")
        sb.append("## Agent Results Summary\n\n")
        sb.append("| Agent | Status | Size |\n")
        sb.append("|-------|--------|------|\n")
        results.forEach { (name, result) ->
            val status = if (result.isEmpty()) "⚠️ Empty" else "✅ OK"
            sb.append("| $name | $status | ${result.length} chars |\n")
        }
        sb.append("\n")
        
        if (conflicts.isNotEmpty()) {
            sb.append("## Cross-Agent Conflicts\n\n")
            conflicts.forEach { c ->
                sb.append("**${c.topic}** (between ${c.agents.joinToString(" & ")})\n")
                c.resolutions.forEach { sb.append("- $it\n") }
                sb.append("\n")
            }
        }
        
        sb.append("## Detailed Results\n\n")
        results.forEach { (name, result) ->
            if (result.isNotEmpty()) {
                sb.append("### $name\n")
                sb.append("${result.take(2000)}\n\n")
            }
        }
        
        sb.append("---\n*Validated and merged by Result Validator Agent*")
        return sb.toString()
    }

    private fun extractSeverity(text: String): String? {
        return when {
            text.contains("critical", ignoreCase = true) -> "critical"
            text.contains("warning", ignoreCase = true) -> "warning"
            text.contains("info", ignoreCase = true) -> "info"
            else -> null
        }
    }

    private fun extractFinding(text: String): String {
        val keywords = listOf("vulnerability", "issue", "bug", "flaw", "exposure", "injection", "overflow")
        return keywords.firstOrNull { text.contains(it, ignoreCase = true) } ?: ""
    }
}
