package com.hermes.analyzer.ai

import android.content.Context
import android.util.Log
import com.hermes.analyzer.db.ChatHistoryManager
import com.hermes.analyzer.ai.DeobfuscationEngine
import com.hermes.analyzer.ai.LocalLLMEngine
import com.hermes.analyzer.ai.NetworkAnalyzer
import com.hermes.analyzer.ai.ReverseEngineeringPipeline
import com.hermes.analyzer.sandbox.SandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UnifiedAIEngine(private val context: Context) {
    private val sandbox = SandboxManager(context)
    private val localLLM = LocalLLMEngine(context)
    private val chatHistory = ChatHistoryManager.getInstance(context)
    private val networkAnalyzer = NetworkAnalyzer(context)
    private val deobfuscationEngine = DeobfuscationEngine(context)
    private val pipeline = ReverseEngineeringPipeline(context)

    companion object {
        private const val TAG = "UnifiedAIEngine"
        private const val SANDBOX_BINARY = "sandbox-binary"
        private const val DEFAULT_TIMEOUT = 30
    }

    fun getCapabilities(): String = buildString {
        append("Hermes Unified AI Engine - 사용 가능한 기능 목록:\n")
        append("- 자동 파일 분석 (autoAnalyze): 바이너리 정적 분석, 문자열 추출, URL/Endpoint 탐지\n")
        append("- 명령어 실행 (executeCommand): Sandbox 내 안전한 shell command 실행\n")
        append("- 네트워크 분석 (networkAnalyzer): API endpoint 및 traffic pattern 분석\n")
        append("- 난독화 해제 (deobfuscationEngine): String decryption 및 control flow 복원\n")
        append("- 역공학 파이프라인 (pipeline): 전체 reverse engineering workflow 실행\n")
        append("- 로컬 LLM 추론 (localLLM): On-device AI를 활용한 코드 설명 및 요약\n")
        append("\n사용 예시: 'cmd: strings /path/to/file' 또는 '이 파일을 분석해줘' (파일 첨부 시)")
    }

    suspend fun executeCommand(command: String): String {
        return try {
            val result = withContext(Dispatchers.IO) {
                sandbox.execute(SANDBOX_BINARY, command, DEFAULT_TIMEOUT)
            }
            result.stdout.ifBlank { "명령어 실행 완료 (결과 없음)." }
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed: %s".format(e.message), e)
            "명령어 실행 실패: %s".format(e.message ?: "Unknown error")
        }
    }

    suspend fun autoAnalyze(filePath: String): String {
        val targetFile = File(filePath)
        if (!targetFile.exists()) {
            return "오류: 파일을 찾을 수 없습니다. path=%s".format(filePath)
        }

        return withContext(Dispatchers.IO) {
            try {
                val analysisReport = buildString {
                    append("=== 자동 분석 보고서 ===\n")
                    append("대상 파일: %s\n".format(filePath))
                    append("파일 크기: %d bytes\n\n".format(targetFile.length()))

                    // 1. 파일 타입 식별
                    val fileTypeCmd = "file %s".format(filePath)
                    val fileTypeResult = sandbox.execute(SANDBOX_BINARY, fileTypeCmd, DEFAULT_TIMEOUT)
                    append("[1] 파일 타입: %s\n".format(fileTypeResult?.trim() ?: "Unknown"))

                    // 2. 문자열 추출
                    val stringsCmd = "strings -n 6 %s | head -200".format(filePath)
                    val stringsResult = sandbox.execute(SANDBOX_BINARY, stringsCmd, DEFAULT_TIMEOUT)
                    val stringsLines = stringsResult?.lines()?.filter { it.isNotBlank() } ?: emptyList()
                    append("[2] 추출된 문자열: %d 개\n".format(stringsLines.size))

                    // 3. URL / Endpoint 탐지
                    val urlPattern = buildString {
                        append("https?://[^")
                        append("\\")
                        append("s<>\"{}|^`")
                        append("\\")
                        append("[")
                        append("\\")
                        append("]")
                        append("]+")
                    }
                    val urlCmd = "strings %s | grep -oE '%s' | sort -u | head -30".format(filePath, urlPattern)
                    val urlResult = sandbox.execute(SANDBOX_BINARY, urlCmd, DEFAULT_TIMEOUT)
                    val urlList = urlResult?.lines()?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
                    append("[3] 발견된 URL/Endpoint: %d 개\n".format(urlList.size))
                    urlList.forEach { url ->
                        append("    - %s\n".format(url))
                    }

                    // 4. IP 주소 탐지
                    val ipPattern = buildString {
                        append("[0-9]{1,3}")
                        append("\\")
                        append(".")
                        append("[0-9]{1,3}")
                        append("\\")
                        append(".")
                        append("[0-9]{1,3}")
                        append("\\")
                        append(".")
                        append("[0-9]{1,3}")
                    }
                    val ipCmd = "strings %s | grep -oE '%s' | sort -u | head -20".format(filePath, ipPattern)
                    val ipResult = sandbox.execute(SANDBOX_BINARY, ipCmd, DEFAULT_TIMEOUT)
                    val ipList = ipResult?.lines()?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
                    append("[4] 발견된 IP 주소: %d 개\n".format(ipList.size))
                    ipList.forEach { ip ->
                        append("    - %s\n".format(ip))
                    }

                    // 5. 동적 라이브러리 의존성 (ELF only)
                    val libCmd = "ldd %s 2>/dev/null || echo 'No dynamic libraries or not ELF'".format(filePath)
                    val libResult = sandbox.execute(SANDBOX_BINARY, libCmd, DEFAULT_TIMEOUT)
                    append("[5] 라이브러리 의존성:\n%s\n".format(libResult?.trim() ?: "N/A"))

                    // 6. Permission / Security 관련 문자열
                    val secPattern = buildString {
                        append("(")
                        append("permission|PERMISSION|android\\.permission|crypto|AES|RSA|MD5|SHA|password|passwd|secret|token|api_key|apikey")
                        append(")")
                    }
                    val secCmd = "strings %s | grep -oiE '%s' | sort | uniq -c | sort -rn | head -20".format(filePath, secPattern)
                    val secResult = sandbox.execute(SANDBOX_BINARY, secCmd, DEFAULT_TIMEOUT)
                    append("[6] 보안 관련 키워드 빈도:\n%s\n".format(secResult?.trim() ?: "N/A"))

                    append("\n분석 완료. 총 %d개의 하드코딩된 엔드포인트를 발견했습니다.\n".format(urlList.size + ipList.size))
                }
                analysisReport
            } catch (e: Exception) {
                Log.e(TAG, "Auto-analysis failed: %s".format(e.message), e)
                "분석 실패: %s".format(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun process(userInput: String, filePath: String? = null, sessionId: String? = null): String {
        val session = sessionId ?: "default_session"
        Log.i(TAG, "Processing session=%s, input=%s".format(session, userInput.take(50)))

        // 채팅 히스토리 저장
        chatHistory.addMessage(session, "user", userInput)

        // 파일이 제공된 경우 자동 분석 수행
        val fileAnalysis = if (filePath != null) {
            autoAnalyze(filePath)
        } else null

        val lowerInput = userInput.lowercase()
        val intent = when {
            lowerInput.startsWith("cmd:") -> Intent.EXECUTE_COMMAND
            lowerInput.contains("분석") || lowerInput.contains("analyze") || lowerInput.contains("reverse") -> Intent.ANALYZE
            lowerInput.contains("네트워크") || lowerInput.contains("network") || lowerInput.contains("api") || lowerInput.contains("endpoint") -> Intent.NETWORK
            lowerInput.contains("난독화") || lowerInput.contains("deobfuscate") || lowerInput.contains("unobfuscate") || lowerInput.contains("decrypt") -> Intent.DEOBFUSCATION
            lowerInput.contains("도움") || lowerInput.contains("help") || lowerInput.contains("capabilities") || lowerInput.contains("기능") -> Intent.CAPABILITIES
            else -> Intent.GENERAL
        }

        val toolResult = when (intent) {
            Intent.EXECUTE_COMMAND -> {
                val cmd = userInput.removePrefix("cmd:").trim()
                executeCommand(cmd)
            }
            Intent.ANALYZE -> {
                if (filePath != null) {
                    pipeline.analyze(filePath)
                } else {
                    fileAnalysis ?: "파일 경로가 필요합니다. 분석할 파일을 업로드해 주세요."
                }
            }
            Intent.NETWORK -> {
                if (filePath != null) {
                    networkAnalyzer.analyze(filePath)
                } else {
                    "네트워크 분석을 위해 파일 경로가 필요합니다. 파일을 업로드해 주세요."
                }
            }
            Intent.DEOBFUSCATION -> {
                if (filePath != null) {
                    deobfuscationEngine.deobfuscate(filePath)
                } else {
                    "난독화 해제를 위해 파일 경로가 필요합니다. 파일을 업로드해 주세요."
                }
            }
            Intent.CAPABILITIES -> getCapabilities()
            Intent.GENERAL -> {
                val prompt = buildString {
                    if (fileAnalysis != null) {
                        append("=== 파일 분석 결과 ===\n")
                        append("%s\n\n".format(fileAnalysis))
                    }
                    append("사용자 질문: %s".format(userInput))
                }
                generateLLMResponse(prompt)
            }
        }

        val finalResponse = synthesizeResponse(intent, toolResult, fileAnalysis)
        chatHistory.addMessage(session, "assistant", finalResponse)
        return finalResponse
    }

    private enum class Intent {
        EXECUTE_COMMAND, ANALYZE, NETWORK, DEOBFUSCATION, CAPABILITIES, GENERAL
    }

    private suspend fun generateLLMResponse(prompt: String): String {
        return try {
            val result = withContext(Dispatchers.Default) {
                localLLM.generate(prompt)
            }
            if (!result.isNullOrBlank()) {
                result
            } else {
                "LLM 응답이 비어 있습니다. Sandbox 분석 결과를 참고하세요."
            }
        } catch (e: Exception) {
            Log.w(TAG, "LLM generation failed: %s".format(e.message))
            "LLM 응답 생성 실패. Fallback: %s".format(e.message ?: "Unknown error")
        }
    }

    private fun synthesizeResponse(intent: Intent, toolResult: String, fileAnalysis: String?): String {
        return buildString {
            when (intent) {
                Intent.EXECUTE_COMMAND -> {
                    append("명령어 실행 결과:\n")
                    append("```\n")
                    append(toolResult)
                    append("\n```")
                }
                Intent.ANALYZE, Intent.NETWORK, Intent.DEOBFUSCATION -> {
                    append("요청하신 작업을 완료했습니다.\n\n")
                    if (fileAnalysis != null) {
                        append("=== 자동 분석 요약 ===\n")
                        append(fileAnalysis)
                        append("\n\n")
                    }
                    append("=== 도구 실행 결과 ===\n")
                    append(toolResult)
                }
                Intent.CAPABILITIES -> append(toolResult)
                Intent.GENERAL -> append(toolResult)
            }

            append("\n\n(Hermes UnifiedAIEngine | intent=%s)".format(intent.name))
        }
    }
}
