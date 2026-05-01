package com.hermes.analyzer.ai.agents

/**
 * Agent 5: Test Runner
 * 전문 역할: 테스트 스크립트 실행, 출력 검증, 정확성 체크
 */
class TestRunnerAgent {
    data class TestResult(
        val passed: Int,
        val failed: Int,
        val skipped: Int,
        val durationMs: Long,
        val details: List<TestDetail>
    )

    data class TestDetail(
        val name: String,
        val status: String,
        val message: String,
        val output: String
    )

    fun runValidation(checks: List<() -> Boolean>): TestResult {
        val details = mutableListOf<TestDetail>()
        var passed = 0
        var failed = 0
        val start = System.currentTimeMillis()
        
        checks.forEachIndexed { idx, check ->
            val checkStart = System.currentTimeMillis()
            try {
                val result = check()
                val duration = System.currentTimeMillis() - checkStart
                if (result) {
                    passed++
                    details.add(TestDetail("Check #${idx + 1}", "PASS", "Completed in ${duration}ms", ""))
                } else {
                    failed++
                    details.add(TestDetail("Check #${idx + 1}", "FAIL", "Assertion returned false", ""))
                }
            } catch (e: Exception) {
                failed++
                details.add(TestDetail("Check #${idx + 1}", "ERROR", e.message ?: "Unknown error", e.stackTraceToString().take(200)))
            }
        }
        
        return TestResult(passed, failed, 0, System.currentTimeMillis() - start, details)
    }

    fun validateFileStructure(filePath: String, expectedType: String): TestResult {
        val file = java.io.File(filePath)
        val checks = mutableListOf<() -> Boolean>()
        
        checks.add { file.exists() }
        checks.add { file.length() > 0 }
        
        when (expectedType) {
            "apk" -> {
                checks.add { file.name.endsWith(".apk") }
                checks.add { file.length() > 1024 }
            }
            "elf" -> {
                checks.add { file.name.endsWith(".so") || file.name.endsWith(".elf") || file.name.endsWith(".o") }
            }
            "dex" -> {
                checks.add { file.name.endsWith(".dex") }
            }
        }
        
        return runValidation(checks)
    }

    fun validateAnalysisOutput(output: String): TestResult {
        val checks = listOf<() -> Boolean>(
            { output.isNotEmpty() },
            { output.length > 50 },
            { !output.contains("Error: Plugin not found") },
            { !output.contains("Failed to") || output.contains("Success") },
            { output.contains("Analysis") || output.contains("Summary") || output.contains("Result") }
        )
        return runValidation(checks)
    }
}
