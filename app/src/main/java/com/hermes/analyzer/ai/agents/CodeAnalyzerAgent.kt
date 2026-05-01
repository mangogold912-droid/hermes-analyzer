package com.hermes.analyzer.ai.agents

import com.hermes.analyzer.ai.AdvancedAIEngine
import com.hermes.analyzer.ai.AdvancedCognitiveEngine
import kotlinx.coroutines.*

/**
 * Agent 1: Code Analyzer
 * 전문 역할: 소스 코드 분석, 역컴파일 출력, 정적 분석 결과 해석
 */
class CodeAnalyzerAgent(private val engine: AdvancedAIEngine) {
    data class CodeAnalysis(
        val language: String,
        val complexity: Int,
        val issues: List<CodeIssue>,
        val functions: List<FunctionInfo>,
        val dependencies: List<String>,
        val summary: String
    )

    data class CodeIssue(
        val severity: String,
        val line: Int,
        val message: String,
        val suggestion: String
    )

    data class FunctionInfo(
        val name: String,
        val params: List<String>,
        val returnType: String,
        val complexity: Int
    )

    suspend fun analyze(code: String, language: String = "auto"): CodeAnalysis {
        val detectedLang = if (language == "auto") detectLanguage(code) else language
        val lines = code.lines()
        val functions = extractFunctions(code, detectedLang)
        val issues = scanIssues(code, detectedLang)
        val deps = extractDependencies(code, detectedLang)
        
        val summary = try {
            engine.chatWithParallelAI("Summarize this $detectedLang code in 3 sentences:\n${code.take(2000)}", null)
        } catch (e: Exception) {
            "Code analysis completed locally. ${lines.size} lines, ${functions.size} functions detected."
        }

        return CodeAnalysis(
            language = detectedLang,
            complexity = calculateComplexity(code),
            issues = issues,
            functions = functions,
            dependencies = deps,
            summary = summary
        )
    }

    private fun detectLanguage(code: String): String {
        return when {
            code.contains("fun ") && code.contains("val ") -> "kotlin"
            code.contains("def ") && code.contains(":") -> "python"
            code.contains("function ") || code.contains("const ") -> "javascript"
            code.contains("public class") || code.contains("private void") -> "java"
            code.contains("#include") || code.contains("int main") -> "c"
            code.contains("package main") && code.contains("func ") -> "go"
            code.contains("fn ") && code.contains("let ") -> "rust"
            else -> "unknown"
        }
    }

    private fun extractFunctions(code: String, lang: String): List<FunctionInfo> {
        val funcs = mutableListOf<FunctionInfo>()
        val patterns = when (lang) {
            "kotlin", "java" -> Regex("""fun\s+(\w+)\s*\(([^)]*)\)""")
            "python" -> Regex("""def\s+(\w+)\s*\(([^)]*)\)""")
            "javascript" -> Regex("""function\s+(\w+)\s*\(([^)]*)\)""")
            "c", "cpp" -> Regex("""(\w+)\s+(\w+)\s*\(([^)]*)\)""")
            "go" -> Regex("""func\s+(\w+)\s*\(([^)]*)\)""")
            else -> Regex("""(\w+)\s*\(([^)]*)\)""")
        }
        patterns.findAll(code).forEach { match ->
            val groups = match.groupValues
            if (groups.size >= 2) {
                funcs.add(FunctionInfo(
                    name = groups[1],
                    params = groups.getOrNull(2)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                    returnType = "unknown",
                    complexity = 1
                ))
            }
        }
        return funcs
    }

    private fun scanIssues(code: String, lang: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val lines = code.lines()
        lines.forEachIndexed { idx, line ->
            when {
                line.contains("TODO") || line.contains("FIXME") -> issues.add(CodeIssue("info", idx + 1, "Pending task found", "Review and complete"))
                line.contains("password") || line.contains("secret") || line.contains("api_key") -> issues.add(CodeIssue("warning", idx + 1, "Potential hardcoded secret", "Move to environment variables"))
                line.contains("eval(") || line.contains("exec(") -> issues.add(CodeIssue("critical", idx + 1, "Dangerous function call", "Use safer alternatives"))
                line.contains("http://") -> issues.add(CodeIssue("warning", idx + 1, "Insecure HTTP URL", "Use HTTPS"))
            }
        }
        return issues
    }

    private fun extractDependencies(code: String, lang: String): List<String> {
        return when (lang) {
            "python" -> Regex("""import\s+(\w+)|from\s+(\w+)""").findAll(code).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.filter { it.isNotEmpty() }.distinct().toList()
            "kotlin", "java" -> Regex("""import\s+([\w.]+)""").findAll(code).map { it.groupValues[1].substringAfterLast(".") }.distinct().toList()
            "javascript" -> Regex("""require\(['"]([^'"]+)['"]\)|from\s+['"]([^'"]+)['"]""").findAll(code).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.filter { it.isNotEmpty() }.distinct().toList()
            else -> emptyList()
        }
    }

    private fun calculateComplexity(code: String): Int {
        var score = 1
        score += Regex("""if\s*\(""").findAll(code).count()
        score += Regex("""for\s*\(""").findAll(code).count()
        score += Regex("""while\s*\(""").findAll(code).count()
        score += Regex("""when\s*\(""").findAll(code).count()
        score += Regex("""catch""").findAll(code).count()
        return minOf(score, 20)
    }
}
