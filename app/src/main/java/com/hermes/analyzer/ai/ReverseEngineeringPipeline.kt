package com.hermes.analyzer.ai

import android.content.Context
import android.util.Log
import com.hermes.analyzer.sandbox.SandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ReverseEngineeringPipeline
 * 업로드된 파일을 자동으로 다단계 리버스 엔지니어링 분석하는 파이프라인.
 *
 * 단계:
 *   1. 파일 형식 식별 (file, magic bytes)
 *   2. 메타데이터 추출 (strings, headers)
 *   3. 구조 분석 (readelf, unzip, xxd)
 *   4. 취약점/보안 힌트 스캔
 *   5. 종합 보고서 생성 (자연어)
 */
class ReverseEngineeringPipeline(private val context: Context) {
    private val TAG = "REPipeline"
    private val sandbox = SandboxManager(context)
    private val localLLM = LocalLLMEngine(context)
    private val hexAnalyzer = HexAnalyzer()

    data class PipelineStage(
        val name: String,
        val commands: List<String>,
        val description: String
    )

    data class PipelineResult(
        val filePath: String,
        val fileType: String,
        val stages: List<StageResult>,
        val summary: String,
        val reportMarkdown: String
    )

    data class StageResult(
        val stageName: String,
        val success: Boolean,
        val output: String,
        val keyFindings: List<String>
    )

    /**
     * 파일을 받아 전체 파이프라인을 실행
     */
    suspend fun analyze(filePath: String): PipelineResult = withContext(Dispatchers.IO) {
        val file = File(filePath)
        val fileType = detectDetailedType(filePath)
        val stages = mutableListOf<StageResult>()

        Log.i(TAG, "Starting pipeline for $filePath (type=$fileType)")

        // 단계 1: 파일 형식 식별
        stages.add(runStage("Type Identification", listOf(
            "file \"$filePath\"",
            "ls -la \"$filePath\""
        ), filePath))

        // 단계 2: 문자열 추출
        stages.add(runStage("String Extraction", listOf(
            "strings \"$filePath\" | head -100",
            "strings -n 6 \"$filePath\" | grep -E '[A-Za-z0-9]{6,}' | head -50"
        ), filePath))

        // 단계 3: 구조 분석 (파일 타입별)
        when (fileType) {
            "apk" -> {
                stages.add(runStage("APK Structure", listOf(
                    "unzip -l \"$filePath\" | head -40",
                    "unzip -p \"$filePath\" AndroidManifest.xml | head -c 2000 || echo 'No manifest'",
                    "strings \"$filePath\" | grep -E 'classes\\.dex|lib/.*\\.so' | head -20"
                ), filePath))
            }
            "elf" -> {
                stages.add(runStage("ELF Structure", listOf(
                    "readelf -h \"$filePath\" 2>/dev/null || echo 'readelf not available'",
                    "readelf -S \"$filePath\" 2>/dev/null || echo 'readelf not available'",
                    "readelf -s \"$filePath\" 2>/dev/null | head -30 || echo 'readelf not available'",
                    "strings \"$filePath\" | grep -E 'JNI|Java_com|dlopen|dlsym|pthread' | head -20"
                ), filePath))
            }
            "dex" -> {
                stages.add(runStage("DEX Structure", listOf(
                    "xxd -l 128 \"$filePath\"",
                    "strings \"$filePath\" | head -60"
                ), filePath))
            }
            "pe" -> {
                stages.add(runStage("PE Structure", listOf(
                    "file \"$filePath\"",
                    "strings \"$filePath\" | head -60"
                ), filePath))
            }
            "zip" -> {
                stages.add(runStage("ZIP Structure", listOf(
                    "unzip -l \"$filePath\" | head -30",
                    "file \"$filePath\""
                ), filePath))
            }
            else -> {
                stages.add(runStage("Binary Structure", listOf(
                    "xxd -l 256 \"$filePath\"",
                    "strings \"$filePath\" | head -50"
                ), filePath))
            }
        }

        // 단계 4: 보안/취약점 스캔
        stages.add(runStage("Security Scan", listOf(
            "strings \"$filePath\" | grep -iE 'password|secret|key|token|api_key|admin|root' | head -20",
            "strings \"$filePath\" | grep -oE 'https?://[^\\s<>\"]+' | sort -u | head -15",
            "strings \"$filePath\" | grep -iE 'md5|sha1|des|rc4|encrypt|decrypt|cipher|crypto' | head -15",
            "strings \"$filePath\" | grep -iE 'exec|system|popen|eval|shell|cmd' | head -10"
        ), filePath))

        // 단계 5: 헥스 헤더 분석
        stages.add(runStage("Hex Header", listOf(
            "xxd -l 64 \"$filePath\""
        ), filePath))

        // 종합 보고서 생성
        val summary = generateSummary(stages, filePath, fileType)
        val report = generateMarkdownReport(stages, filePath, fileType, summary)

        PipelineResult(filePath, fileType, stages, summary, report)
    }

    private fun runStage(name: String, commands: List<String>, filePath: String): StageResult {
        val sb = StringBuilder()
        val findings = mutableListOf<String>()
        var allSuccess = true
        val sandboxId = "sandbox-binary"

        for (cmd in commands) {
            val res = sandbox.execute(sandboxId, cmd, 30)
            val out = res.stdout
            val err = res.stderr
            val ok = res.success

            if (!ok) allSuccess = false

            sb.append("$ $cmd\n")
            if (out.isNotBlank()) sb.append(out).append("\n")
            if (err.isNotBlank() && !err.contains("not available") && !err.contains("No such")) {
                sb.append("[stderr: $err]\n")
            }

            // 핵심 발견사항 자동 추출
            val lines = out.lines()
            for (line in lines) {
                val l = line.lowercase()
                when {
                    l.contains("password") || l.contains("secret") || l.contains("api_key") -> findings.add("Possible secret: $line")
                    l.contains("http://") || l.contains("https://") -> findings.add("URL found: $line")
                    l.contains("md5") || l.contains("sha1") || l.contains("des") || l.contains("rc4") -> findings.add("Crypto reference: $line")
                    l.contains("jni") || l.contains("dlopen") || l.contains("native") -> findings.add("Native/JNI: $line")
                    l.contains("exec") || l.contains("system(") || l.contains("eval(") -> findings.add("Dangerous call: $line")
                }
            }
        }

        return StageResult(name, allSuccess, sb.toString(), findings.take(10))
    }

    private fun generateSummary(stages: List<StageResult>, filePath: String, fileType: String): String {
        val totalFindings = stages.sumOf { it.keyFindings.size }
        val successStages = stages.count { it.success }
        val allFindings = stages.flatMap { it.keyFindings }

        val sb = StringBuilder()
        sb.append("분석 파일: $filePath\n")
        sb.append("파일 형식: ${fileType.uppercase()}\n")
        sb.append("파이프라인 단계: ${stages.size}개 중 ${successStages}개 성공\n")
        sb.append("주요 발견: ${totalFindings}개\n\n")

        if (allFindings.isNotEmpty()) {
            sb.append("핵심 인사이트:\n")
            allFindings.distinct().take(15).forEachIndexed { i, f ->
                sb.append("${i + 1}. $f\n")
            }
        } else {
            sb.append("자동 추출된 특이사항은 없습니다. 문자열 출력을 수동으로 검토하세요.\n")
        }

        return sb.toString()
    }

    private fun generateMarkdownReport(
        stages: List<StageResult>,
        filePath: String,
        fileType: String,
        summary: String
    ): String {
        val sb = StringBuilder()
        sb.append("# Hermes Analyzer - Reverse Engineering Report\n\n")
        sb.append("**File:** `$filePath`  \n")
        sb.append("**Type:** ${fileType.uppercase()}  \n")
        sb.append("**Generated:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}  \n\n")

        sb.append("## Summary\n\n")
        sb.append("```\n$summary\n```\n\n")

        for (stage in stages) {
            sb.append("## ${stage.stageName}\n\n")
            sb.append("**Status:** ${if (stage.success) "Success" else "Partial/Failed"}\n\n")
            if (stage.keyFindings.isNotEmpty()) {
                sb.append("**Key Findings:**\n")
                stage.keyFindings.forEach { sb.append("- $it\n") }
                sb.append("\n")
            }
            sb.append("**Output:**\n")
            sb.append("```\n${stage.output}\n```\n\n")
        }

        sb.append("---\n\n")
        sb.append("*Powered by Hermes Analyzer Autonomous Engine*\n")
        return sb.toString()
    }

    private fun detectDetailedType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in setOf("apk", "elf", "so", "dex", "jar", "zip", "exe", "dll", "pe")) return ext

        return try {
            val file = File(path)
            if (!file.exists() || file.length() < 4) return "unknown"
            val magic = file.inputStream().use { it.readNBytes(8) }
            when {
                magic.copyOf(4).contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) -> {
                    if (ext == "apk" || path.endsWith(".apk")) "apk"
                    else if (ext == "jar" || path.endsWith(".jar")) "jar"
                    else "zip"
                }
                magic.copyOf(4).contentEquals(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())) -> "elf"
                magic.copyOf(2).contentEquals(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte())) -> "pe"
                magic.copyOf(4).contentEquals(byteArrayOf(0x64, 0x65, 0x78, 0x0A)) -> "dex"
                else -> "bin"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 보고서를 파일로 저장
     */
    fun saveReport(report: PipelineResult, outputDir: File): File {
        val ts = System.currentTimeMillis()
        val name = "RE_Report_${report.fileType}_${ts}.md"
        val out = File(outputDir, name)
        out.writeText(report.reportMarkdown)
        return out
    }
}

