package com.hermes.analyzer.ai

import android.content.Context
import android.util.Log
import com.hermes.analyzer.db.ChatHistoryManager
import com.hermes.analyzer.sandbox.SandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UnifiedAIEngine(private val context: Context) {
    private val sandbox = SandboxManager(context)
    private val chatHistory = ChatHistoryManager.getInstance(context)
    private val TAG = "UnifiedAIEngine"

    fun getCapabilities(): String = buildString {
        append("Hermes AI - 사용 가능한 기능:
")
        append("- 파일 자동 분석 (업로드 시)
")
        append("- 쉘 명령어 실행
")
        append("- 문자열/URL/Endpoint 추출
")
        append("- 헥스 덤프 및 바이너리 분석
")
        append("- 네트워크/보안 스캔
")
        append("- 난독화 탐지
")
    }

    suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        try {
            val res = sandbox.execute("sandbox-binary", command, 30)
            res.stdout.ifBlank { "명령어 실행 완료 (출력 없음)" }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${e.message}")
            "명령어 실행 실패: ${e.message}"
        }
    }

    suspend fun autoAnalyze(filePath: String): String = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) return@withContext "파일을 찾을 수 없습니다: $filePath"

        val sb = StringBuilder()
        sb.append("=== 자동 분석 보고서 ===
")
        sb.append("파일: $filePath
")
        sb.append("크기: ${file.length()} bytes

")

        try {
            val typeRes = sandbox.execute("sandbox-binary", "file $filePath", 30)
            sb.append("[파일 타입]
${typeRes.stdout}

")

            val strRes = sandbox.execute("sandbox-binary", "strings -n 6 $filePath | head -100", 30)
            val lines = strRes.stdout.lines().filter { it.isNotBlank() }
            sb.append("[문자열 추출] ${lines.size}개
")
            lines.take(20).forEach { sb.append("  - $it
") }

            sb.append("
분석 완료. 추가 분석이 필요하면 자연어로 질문해 주세요.")
        } catch (e: Exception) {
            sb.append("분석 중 오류: ${e.message}")
        }
        sb.toString()
    }

    suspend fun process(userInput: String, filePath: String? = null, sessionId: String? = null): String = withContext(Dispatchers.IO) {
        val sid = sessionId ?: "default"
        chatHistory.addMessage(sid, "user", userInput)

        val response = if (filePath != null) {
            val analysis = autoAnalyze(filePath)
            "파일 분석 결과:

$analysis

추가 질문이 있으시면 입력해 주세요."
        } else if (userInput.startsWith("cmd:", ignoreCase = true)) {
            val cmd = userInput.removePrefix("cmd:").trim()
            executeCommand(cmd)
        } else {
            "입력하신 '$userInput'에 대해 분석을 시작합니다.

파일을 업로드하시면 자동 분석을 수행합니다."
        }

        chatHistory.addMessage(sid, "ai", response)
        response
    }
}
