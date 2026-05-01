package com.hermes.analyzer.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * RealTerminal
 * 실제 Android Runtime.exec() 기반 터미널 세션.
 * /system/bin/sh를 사용하여 실제 쉘 명령어를 실행하고,
 * 출력/에러 스트림을 실시간으로 캡처합니다.
 *
 * TerminalActivity의 백엔드 엔진 역할.
 */
class RealTerminal(private val context: Context) {
    private val TAG = "RealTerminal"
    private val history = mutableListOf<TerminalCommand>()
    private var currentSession: ShellSession? = null
    private val sessions = mutableMapOf<String, ShellSession>()

    data class TerminalCommand(
        val id: Int,
        val command: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val timestamp: Long,
        val durationMs: Long
    )

    data class ShellSession(
        val id: String,
        val name: String,
        val workingDir: File,
        var process: Process? = null,
        var stdin: OutputStreamWriter? = null,
        var stdoutReader: BufferedReader? = null,
        var stderrReader: BufferedReader? = null,
        var isActive: Boolean = false
    )

    /**
     * 단일 명령어 실행 (블로킹, 안전)
     */
    suspend fun execute(command: String, workingDir: File? = null, timeoutSec: Long = 30): TerminalCommand = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val id = history.size + 1
        val dir = workingDir ?: File(context.getExternalFilesDir(null), "sandbox/sandbox-binary")
        dir.mkdirs()

        Log.d(TAG, "Executing [$id]: $command (dir=${dir.absolutePath})")

        val result = try {
            val process = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", command),
                null,
                dir
            )

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            val duration = System.currentTimeMillis() - start

            val stdout = if (finished) {
                process.inputStream.bufferedReader().use { it.readText() }
            } else {
                process.destroyForcibly()
                "[TIMEOUT after ${timeoutSec}s]"
            }

            val stderr = if (finished) {
                process.errorStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }

            val exitCode = if (finished) process.exitValue() else -1
            TerminalCommand(id, command, stdout, stderr, exitCode, start, duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            Log.e(TAG, "Execution error: ${e.message}")
            TerminalCommand(id, command, "", "Error: ${e.message}", -1, start, duration)
        }

        history.add(result)
        result
    }

    /**
     * 배치 명령어 실행 (파이프라인)
     */
    suspend fun executeBatch(commands: List<String>, workingDir: File? = null, timeoutSec: Long = 60): List<TerminalCommand> = withContext(Dispatchers.IO) {
        val results = mutableListOf<TerminalCommand>()
        var lastDir = workingDir
        for (cmd in commands) {
            val res = execute(cmd, lastDir, timeoutSec / commands.size.coerceAtLeast(1))
            results.add(res)
            // cd 명령어가 성공하면 디렉터리 추적
            if (cmd.startsWith("cd ") && res.exitCode == 0) {
                val newPath = cmd.removePrefix("cd ").trim()
                lastDir = File(newPath).takeIf { it.isAbsolute } ?: File(lastDir ?: File("."), newPath)
            }
        }
        results
    }

    /**
     * 대화형 세션 시작 (stdin/stdout 연결)
     */
    fun startSession(sessionId: String = "default", name: String = "Terminal", workingDir: File? = null): ShellSession {
        val dir = workingDir ?: File(context.getExternalFilesDir(null), "sandbox/sandbox-binary")
        dir.mkdirs()

        val session = ShellSession(sessionId, name, dir)
        try {
            val process = ProcessBuilder("/system/bin/sh")
                .directory(dir)
                .redirectErrorStream(false)
                .start()

            session.process = process
            session.stdin = OutputStreamWriter(process.outputStream)
            session.stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            session.stderrReader = BufferedReader(InputStreamReader(process.errorStream))
            session.isActive = true

            sessions[sessionId] = session
            currentSession = session
            Log.i(TAG, "Started interactive session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session: ${e.message}")
        }
        return session
    }

    /**
     * 대화형 세션에 명령어 입력
     */
    fun sendToSession(sessionId: String, command: String): String {
        val session = sessions[sessionId] ?: return "Session not found: $sessionId"
        if (!session.isActive) return "Session $sessionId is not active"

        return try {
            session.stdin?.write(command + "\n")
            session.stdin?.flush()

            // 짧은 출력 읽기 (비동기 세션에서는 polling 방식 권장)
            val sb = StringBuilder()
            var line: String?
            val start = System.currentTimeMillis()
            while (session.stdoutReader?.ready() == true && System.currentTimeMillis() - start < 5000) {
                line = session.stdoutReader?.readLine()
                if (line != null) sb.append(line).append("\n")
            }
            sb.toString()
        } catch (e: Exception) {
            "Error sending command: ${e.message}"
        }
    }

    /**
     * 세션 종료
     */
    fun stopSession(sessionId: String) {
        sessions[sessionId]?.let { session ->
            try {
                session.stdin?.close()
                session.stdoutReader?.close()
                session.stderrReader?.close()
                session.process?.destroyForcibly()
                session.isActive = false
                Log.i(TAG, "Stopped session $sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping session: ${e.message}")
            }
            sessions.remove(sessionId)
        }
    }

    /**
     * 현재 세션의 출력 읽기 (polling)
     */
    fun readSessionOutput(sessionId: String): String {
        val session = sessions[sessionId] ?: return ""
        val sb = StringBuilder()
        try {
            while (session.stdoutReader?.ready() == true) {
                val line = session.stdoutReader?.readLine() ?: break
                sb.append(line).append("\n")
            }
        } catch (e: Exception) {
            sb.append("[Read error: ${e.message}]\n")
        }
        return sb.toString()
    }

    /**
     * 명령어 히스토리 반환
     */
    fun getHistory(): List<TerminalCommand> = history.toList()
    fun clearHistory() = history.clear()

    /**
     * 사용 가능한 쉘 도구 목록 확인
     */
    suspend fun detectAvailableTools(): List<String> = withContext(Dispatchers.IO) {
        val tools = listOf(
            "file", "strings", "xxd", "hexdump", "readelf", "objdump", "nm", "ldd",
            "md5sum", "sha1sum", "sha256sum", "base64", "unzip", "tar", "grep", "awk", "sed",
            "curl", "wget", "nc", "ping", "netstat", "ps", "top", "ls", "cat", "find"
        )
        val available = mutableListOf<String>()
        for (tool in tools) {
            val res = execute("which $tool 2>/dev/null || command -v $tool 2>/dev/null || type $tool 2>/dev/null", timeoutSec = 5)
            if (res.stdout.isNotBlank() && !res.stdout.contains("not found")) {
                available.add(tool)
            }
        }
        available
    }

    /**
     * 히스토리에서 특정 패턴 검색
     */
    fun searchHistory(keyword: String): List<TerminalCommand> {
        return history.filter {
            it.command.contains(keyword, ignoreCase = true) ||
            it.stdout.contains(keyword, ignoreCase = true) ||
            it.stderr.contains(keyword, ignoreCase = true)
        }
    }
}
