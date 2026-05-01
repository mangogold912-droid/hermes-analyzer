package com.hermes.analyzer.ai

import android.content.Context
import android.util.Log
import com.hermes.analyzer.sandbox.SandboxManager
import com.hermes.analyzer.network.LinkAbsorber
import com.hermes.analyzer.AuditLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * AutonomousEngine
 * 사용자의 자연어 입력을 이해하고, 실제 리버스 엔지니어링 도구를
 * 샌드박스에서 자율적으로 실행하여 자연어로 결과를 반환하는 핵심 엔진.
 *
 * 핵심 흐름:
 *   자연어 입력 → 의도 파싱 → 액션 플랜 생성 → 샌드박스/터미널 실제 실행
 *   → 결과 수집 → 로컬 LLM으로 자연어 합성 → 응답
 */
class AutonomousEngine(private val context: Context) {
    private val TAG = "AutonomousEngine"
    private val sandbox = SandboxManager(context)
    private val localLLM = LocalLLMEngine(context)
    private val hexAnalyzer = HexAnalyzer()
    private val skillManager = SkillManager(context)
    private val linkAbsorber = LinkAbsorber()
    private val auditLog = AuditLogManager(context)

    // 세션 상태
    private var currentFilePath: String? = null
    private var currentFileType: String = "unknown"
    private val commandHistory = mutableListOf<CommandResult>()

    data class CommandResult(
        val command: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long
    )

    data class ActionPlan(
        val intent: String,
        val subIntent: String,
        val commands: List<String>,
        val description: String,
        val needsFile: Boolean,
        val expectedOutput: String
    )

    data class ParsedIntent(
        val primary: String,
        val confidence: Float,
        val entities: Map<String, String>
    )

    /**
     * 메인 진입점: 사용자 자연어 입력을 처리하고 응답을 반환
     */
    suspend fun process(userInput: String, filePath: String? = null): String = withContext(Dispatchers.IO) {
        try {
            // 1. 파일 컨텍스트 업데이트
            if (filePath != null) {
                currentFilePath = filePath
                currentFileType = detectFileType(filePath)
            }

            // 2. 의도 파싱
            val intent = parseIntent(userInput)
            Log.i(TAG, "Parsed intent: ${intent.primary} (confidence=${intent.confidence}, entities=${intent.entities})")

            // 3. URL 링크 흡수 (입력에 URL이 포함된 경우)
            val absorbedContext = if (userInput.contains("http")) {
                absorbUrls(userInput)
            } else ""

            // 4. 액션 플랜 생성
            val plan = createActionPlan(intent, currentFilePath)
            Log.i(TAG, "Action plan: ${plan.description}, commands=${plan.commands.size}")

            // 5. 플랜 실행
            val results = executePlan(plan, currentFilePath)
            commandHistory.addAll(results)

            // 6. 결과 합성 (자연어로 변환)
            val response = synthesizeResponse(results, intent, userInput, absorbedContext)

            // 7. 감사 로그
            auditLog.logAction("autonomous_process", mapOf(
                "input" to userInput.take(200),
                "intent" to intent.primary,
                "file" to (currentFilePath ?: "none"),
                "commands" to plan.commands.size.toString()
            ))

            response
        } catch (e: Exception) {
            Log.e(TAG, "Process error: ${e.message}", e)
            "분석 중 오류가 발생했습니다: ${e.message}\n\n다시 시도하거나 다른 파일을 업로드해 주세요."
        }
    }

    /**
     * 키워드 + 규칙 기반 자연어 의도 파싱
     */
    fun parseIntent(text: String): ParsedIntent {
        val lower = text.lowercase()
        val entities = mutableMapOf<String, String>()

        // 파일 경로 추출
        val fileRegex = Regex("([\\w\\-./]+\\.(apk|elf|so|dex|jar|zip|pe|exe|dll|bin|dat))")
        fileRegex.find(lower)?.let {
            entities["file"] = it.groupValues[1]
            entities["ext"] = it.groupValues[2]
        }

        // 오프셋 추출
        Regex("0x([0-9a-fA-F]+)").find(lower)?.let {
            entities["offset"] = it.groupValues[1]
        }
        Regex("offset\\s+([0-9]+)").find(lower)?.let {
            entities["offset"] = it.groupValues[1]
        }

        // 의도 분류 (다중 키워드 매칭)
        val scores = mutableMapOf<String, Float>()

        scores["decompile"] = scoreKeywords(lower, listOf("decompile", "디컴파일", "복원", "소스", "source", "역컴파일", "apk 풀기", "dex 변환"))
        scores["disassemble"] = scoreKeywords(lower, listOf("disassemble", "디스어셈블", "어셈블리", "assembly", "asm", "opcode", "명령어"))
        scores["reverse"] = scoreKeywords(lower, listOf("reverse", "리버스", "reverse engineer", "re", "엔지니어링", "내부 구조"))
        scores["security"] = scoreKeywords(lower, listOf("security", "보안", "취약점", "vulnerability", "vuln", "취약", "위험", "hack", "exploit"))
        scores["hex"] = scoreKeywords(lower, listOf("hex", "헥스", "16진수", "hexdump", "바이트", "byte", "dump", "hex dump"))
        scores["strings"] = scoreKeywords(lower, listOf("strings", "문자열", "string", "텍스트", "text", "메시지", "숨겨진 문자"))
        scores["analyze"] = scoreKeywords(lower, listOf("analyze", "분석", "analysis", "구조", "structure", "파악", "특징"))
        scores["hash"] = scoreKeywords(lower, listOf("hash", "해시", "md5", "sha", "checksum", "무결성"))
        scores["network"] = scoreKeywords(lower, listOf("network", "네트워크", "url", "endpoint", "api", "통신", "서버", "domain"))
        scores["crypto"] = scoreKeywords(lower, listOf("crypto", "암호", "encrypt", "decrypt", "암호화", "복호화", "key", "키"))
        scores["compare"] = scoreKeywords(lower, listOf("compare", "비교", "diff", "차이", "변경", "패치"))
        scores["patch"] = scoreKeywords(lower, listOf("patch", "패치", "modify", "수정", "바꾸기", "inject", "후킹"))
        scores["run"] = scoreKeywords(lower, listOf("run", "실행", "execute", "동작", "test", "테스트", "동작 확인"))
        scores["help"] = scoreKeywords(lower, listOf("help", "도움", "사용법", "what can you do", "기능", "menu"))
        scores["url_absorb"] = scoreKeywords(lower, listOf("url", "링크", "link", "페이지", "site", "website", "tutorial", "docs"))

        val best = scores.maxByOrNull { it.value } ?: ("analyze" to 0.1f)
        return ParsedIntent(best.key, best.value.coerceIn(0f, 1f), entities)
    }

    private fun scoreKeywords(text: String, keywords: List<String>): Float {
        var score = 0f
        for (kw in keywords) {
            if (text.contains(kw)) score += 0.25f
        }
        return score.coerceAtMost(1f)
    }

    /**
     * 의도와 파일 정보를 바탕으로 실제 실행할 bash 명령어 플랜 생성
     */
    fun createActionPlan(intent: ParsedIntent, filePath: String?): ActionPlan {
        val commands = mutableListOf<String>()
        val subIntent = intent.entities["ext"] ?: "unknown"
        var description = ""
        var needsFile = true
        var expected = ""

        when (intent.primary) {
            "decompile" -> {
                description = "APK/DEX 자동 디컴파일 파이프라인 실행"
                if (filePath != null) {
                    when (subIntent) {
                        "apk" -> {
                            commands.add("file \"$filePath\"")
                            commands.add("unzip -l \"$filePath\" | head -30")
                            commands.add("aapt dump badging \"$filePath\" 2>/dev/null || echo 'aapt not available'")
                            commands.add("strings \"$filePath\" | grep -E 'http|https|api|key|token|secret' | head -20")
                            expected = "APK 내부 구조, 매니페스트 정보, 문자열 추출 결과"
                        }
                        "dex" -> {
                            commands.add("file \"$filePath\"")
                            commands.add("strings \"$filePath\" | head -50")
                            expected = "DEX 파일 형식 확인 및 문자열 테이블 분석"
                        }
                        else -> {
                            commands.add("file \"$filePath\"")
                            commands.add("strings \"$filePath\" | head -50")
                            expected = "일반 바이너리의 파일 형식 및 문자열 분석"
                        }
                    }
                } else {
                    commands.add("echo '파일이 업로드되지 않았습니다. 디컴파일할 APK/DEX 파일을 업로드해 주세요.'")
                    needsFile = false
                }
            }

            "disassemble" -> {
                description = "바이너리 디스어셈블 및 오프셋 분석"
                if (filePath != null) {
                    commands.add("file \"$filePath\"")
                    commands.add("strings \"$filePath\" | head -40")
                    val offset = intent.entities["offset"]
                    if (offset != null) {
                        commands.add("xxd -s 0x$offset -l 256 \"$filePath\"")
                        description += " (오프셋 0x$offset)"
                    } else {
                        commands.add("xxd -l 512 \"$filePath\"")
                    }
                    expected = "파일 형식, 문자열, 지정 오프셋의 헥스 덤프"
                } else {
                    commands.add("echo '분석할 바이너리 파일을 업로드해 주세요.'")
                    needsFile = false
                }
            }

            "reverse" -> {
                description = "리버스 엔지니어링 종합 분석"
                if (filePath != null) {
                    commands.add("file \"$filePath\"")
                    commands.add("strings \"$filePath\" | head -60")
                    commands.add("strings \"$filePath\" | grep -E 'lib.*\\.so|JNI|native|crypt|decrypt|encode|decode' | head -20")
                    commands.add("readelf -h \"$filePath\" 2>/dev/null || echo 'Not ELF'")
                    commands.add("readelf -S \"$filePath\" 2>/dev/null || echo 'Not ELF'")
                    expected = "파일 형식, 문자열, JNI/네이티브 힌트, ELF 헤더/섹션"
                } else {
                    commands.add("echo '리버스 엔지니어링할 파일을 업로드해 주세요.'")
                    needsFile = false
                }
            }

            "security" -> {
                description = "보안 취약점 스캔"
                if (filePath != null) {
                    commands.add("file \"$filePath\"")
                    commands.add("strings \"$filePath\" | grep -iE 'password|passwd|secret|key|token|api_key|admin|root|backdoor' | head -30")
                    commands.add("strings \"$filePath\" | grep -iE 'http://|https://|ftp://|socket|curl|wget|fetch' | head -20")
                    commands.add("strings \"$filePath\" | grep -iE 'md5|sha1|des|rc4|aes|rsa|encrypt|decrypt' | head -20")
                    expected = "하드코딩된 시크릿, 네트워크 엔드포인트, 암호화 관련 문자열"
                } else {
                    commands.add("echo '보안 분석할 파일을 업로드해 주세요.'")
                    needsFile = false
                }
            }

            "hex" -> {
                description = "헥스 덤프 및 바이트 분석"
                if (filePath != null) {
                    val offset = intent.entities["offset"]
                    if (offset != null) {
                        commands.add("xxd -s 0x$offset -l 512 \"$filePath\"")
                        description += " (오프셋 0x$offset)"
                    } else {
                        commands.add("xxd -l 1024 \"$filePath\"")
                    }
                    commands.add("file \"$filePath\"")
                    expected = "바이트 단위 덤프 및 파일 매직 바이트 분석"
                } else {
                    commands.add("echo '헥스 덤프할 파일을 업로드해 주세요.'")
                    needsFile = false
                }
            }

            "strings" -> {
                description = "문자열 추출 및 분석"
                if (filePath != null) {
                    commands.add("strings \"$filePath\" | head -80")
                    commands.add("strings -n 8 \"$filePath\" | grep -E '[A-Za-z]{4,}' | head -40")
                    expected = "모든 출력 가능한 문자열, 8바이트 이상의 알파벳 문자열"
                } else {
                    commands.add("echo '문자열을 추출할 파일을 업로드해 주세요.'")
                    needsFile = false
                }
            }

            "hash" -> {
                description = "파일 해시 계산"
                if (filePath != null) {
                    commands.add("md5sum \"$filePath\"")
                    commands.add("sha1sum \"$filePath\"")
                    commands.add("sha256sum \"$filePath\"")
                    expected = "MD5, SHA1, SHA256 해시값"
                } else {
                    commands.add("echo '해시를 계산할 파일을 업로드해 주세요.'")
                    needsFile = false
                }
            }

            "network" -> {
                description = "네트워크 관련 정보 추출"
                if (filePath != null) {
                    commands.add("strings \"$filePath\" | grep -oE 'https?://[^\"\\s<>]+' | sort -u | head -20")
                    commands.add("strings \"$filePath\" | grep -oE '[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}' | sort -u | head -20")
                    commands.add("strings \"$filePath\" | grep -iE 'port|host|socket|connect|bind|listen' | head -20")
                    expected = "URL, 도메인, IP, 네트워크 API 호출 힌트"
                } else {
                    commands.add("echo '네트워크를 분석할 파일을 업로드해 주세요.'")
                    needsFile = false
                }
            }

            "crypto" -> {
                description = "암호화/난독화 관련 분석"
                if (filePath != null) {
                    commands.add("strings \"$filePath\" | grep -iE 'aes|des|rsa|rc4|chacha|blowfish|camellia|seed|aria' | head -20")
                    commands.add("strings \"$filePath\" | grep -iE 'encrypt|decrypt|cipher|hash|md5|sha|hmac|pbkdf|scrypt|bcrypt' | head -20")
                    commands.add("strings \"$filePath\" | grep -iE 'base64|hex|urlencode|urldecode|escape|unescape' | head -20")
                    expected = "암호화 알고리즘, 해시, 인코딩 관련 문자열"
                } else {
                    commands.add("echo '암호화를 분석할 파일을 업로드해 주세요.'")
                    needsFile = false
                }
            }

            "run" -> {
                description = "파일 실행/테스트 (샌드박스)"
                if (filePath != null) {
                    commands.add("echo 'Executing in sandbox: $filePath'")
                    commands.add("file \"$filePath\"")
                    commands.add("chmod +x \"$filePath\" 2>/dev/null; echo 'Permissions set'")
                    expected = "파일 형식 및 실행 가능 여부"
                } else {
                    commands.add("echo '실행할 파일을 업로드해 주세요.'")
                    needsFile = false
                }
            }

            "url_absorb" -> {
                description = "URL 링크 콘텐츠 흡수"
                val url = extractUrl(text)
                if (url != null) {
                    commands.add("echo 'Absorbing URL: $url'")
                    commands.add("curl -sL -m 10 \"$url\" | head -c 5000 || wget -qO- \"$url\" | head -c 5000 || echo 'Fetch failed'")
                    needsFile = false
                    expected = "URL에서 추출한 텍스트/코드 콘텐츠"
                } else {
                    commands.add("echo '흡수할 URL을 입력해 주세요.'")
                    needsFile = false
                }
            }

            "help" -> {
                description = "도움말"
                commands.add("echo 'Hermes Analyzer 자율 AI 도움말'")
                needsFile = false
                expected = "기능 목록"
            }

            else -> {
                description = "일반 분석"
                if (filePath != null) {
                    commands.add("file \"$filePath\"")
                    commands.add("strings \"$filePath\" | head -50")
                    commands.add("md5sum \"$filePath\"")
                    expected = "파일 형식, 문자열, 해시"
                } else {
                    commands.add("echo '파일을 업로드하시면 분석을 시작합니다.')")
                    needsFile = false
                }
            }
        }

        return ActionPlan(intent.primary, subIntent, commands, description, needsFile, expected)
    }

    /**
     * 액션 플랜의 각 명령어를 샌드박스에서 실제 실행
     */
    suspend fun executePlan(plan: ActionPlan, filePath: String?): List<CommandResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<CommandResult>()
        val sandboxId = "sandbox-binary"

        // 샌드박스가 없으면 생성
        if (sandbox.getSandbox(sandboxId) == null) {
            sandbox.createSandbox("sandbox-binary", "bash")
        }

        for (cmd in plan.commands) {
            Log.d(TAG, "Executing: $cmd")
            val start = System.currentTimeMillis()

            val result = try {
                val res = sandbox.execute(sandboxId, cmd, timeoutSec = 30)
                CommandResult(cmd, res.stdout, res.stderr, res.exitCode, System.currentTimeMillis() - start)
            } catch (e: Exception) {
                CommandResult(cmd, "", "Execution error: ${e.message}", -1, System.currentTimeMillis() - start)
            }

            Log.d(TAG, "Result: exit=${result.exitCode}, stdout=${result.stdout.length} chars")
            results.add(result)
        }

        // 실패한 명령어에 대한 fallback: 직접 Runtime.exec로 재시도
        val retryResults = results.map { res ->
            if (res.exitCode != 0 && res.stdout.isBlank() && res.command.startsWith("file ")) {
                try {
                    val direct = directExec(res.command)
                    res.copy(stdout = direct.first, stderr = direct.second, exitCode = direct.third)
                } catch (e: Exception) {
                    res
                }
            } else {
                res
            }
        }

        retryResults
    }

    /**
     * Runtime.exec를 직접 호출하는 fallback (샌드박스 밖)
     */
    private fun directExec(command: String): Triple<String, String, Int> {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", command))
            proc.waitFor()
            val stdout = proc.inputStream.bufferedReader().use { it.readText() }
            val stderr = proc.errorStream.bufferedReader().use { it.readText() }
            Triple(stdout, stderr, proc.exitValue())
        } catch (e: Exception) {
            Triple("", "Direct exec error: ${e.message}", -1)
        }
    }

    /**
     * 명령어 실행 결과를 받아 자연어 응답으로 합성
     */
    suspend fun synthesizeResponse(
        results: List<CommandResult>,
        intent: ParsedIntent,
        originalInput: String,
        absorbedContext: String
    ): String = withContext(Dispatchers.IO) {
        // 결과 텍스트 조합
        val resultText = results.joinToString("\n\n---\n\n") { res ->
            val cmd = res.command
            val out = if (res.stdout.length > 2000) res.stdout.take(2000) + "\n... (truncated)" else res.stdout
            val err = if (res.stderr.isNotBlank()) "[stderr: ${res.stderr}]" else ""
            "Command: $cmd\nExit: ${res.exitCode}\nOutput:\n$out\n$err"
        }

        // 파일 컨텍스트
        val fileContext = currentFilePath?.let { path ->
            val file = File(path)
            val size = if (file.exists()) file.length() else 0
            val md5 = computeMD5(file)
            "Current file: $path (${size} bytes, MD5: $md5, Type: $currentFileType)"
        } ?: "No file uploaded"

        // 로컬 LLM 프롬프트 구성
        val prompt = buildSynthesisPrompt(originalInput, intent, resultText, fileContext, absorbedContext)

        // LLM 생성
        val response = localLLM.generateResponse(prompt)

        // 응답이 비었거나 너무 짧으면 rule-based fallback
        if (response.length < 30) {
            return@withContext generateRuleBasedSynthesis(results, intent)
        }

        response
    }

    private fun buildSynthesisPrompt(
        input: String,
        intent: ParsedIntent,
        results: String,
        fileContext: String,
        absorbed: String
    ): String {
        return """You are Hermes Analyzer, an expert reverse engineering AI assistant.
Analyze the following command execution results and provide a detailed natural language response in the same language as the user's query (Korean or English).

User Query: $input
Intent: ${intent.primary}
$fileContext

Command Results:
$results

${if (absorbed.isNotBlank()) "Additional Context from URL:\n$absorbed\n\n" else ""}
Provide:
1. A clear summary of what was done
2. Key findings from the output
3. Security or technical insights
4. Next steps or recommendations

Response:""".trimIndent()
    }

    /**
     * 규칙 기반 결과 합성 (LLM fallback)
     */
    private fun generateRuleBasedSynthesis(results: List<CommandResult>, intent: ParsedIntent): String {
        val sb = StringBuilder()

        // 헤더
        when (intent.primary) {
            "decompile" -> sb.append("디컴파일 분석 결과:\n\n")
            "disassemble" -> sb.append("디스어셈블 분석 결과:\n\n")
            "reverse" -> sb.append("리버스 엔지니어링 분석 결과:\n\n")
            "security" -> sb.append("보안 취약점 분석 결과:\n\n")
            "hex" -> sb.append("헥스 덤프 분석 결과:\n\n")
            "strings" -> sb.append("문자열 추출 결과:\n\n")
            "hash" -> sb.append("파일 해시 계산 결과:\n\n")
            "network" -> sb.append("네트워크 분석 결과:\n\n")
            "crypto" -> sb.append("암호화 분석 결과:\n\n")
            "run" -> sb.append("실행 테스트 결과:\n\n")
            "help" -> sb.append("Hermes Analyzer 사용 가이드:\n\n")
            else -> sb.append("분석 결과:\n\n")
        }

        // 결과 정리
        for (res in results) {
            val cmdName = res.command.split(" ").firstOrNull() ?: "command"
            val outLines = res.stdout.lines().filter { it.isNotBlank() }
            val hasOutput = outLines.isNotEmpty()

            sb.append("[$cmdName] ")
            if (res.exitCode == 0 && hasOutput) {
                sb.append("성공\n")
                // 핵심 내용만 추출
                val summary = outLines.take(15).joinToString("\n")
                sb.append(summary).append("\n\n")
            } else if (res.exitCode != 0) {
                sb.append("실패 (exit ${res.exitCode})\n")
                if (res.stderr.isNotBlank()) {
                    sb.append("오류: ${res.stderr.take(200)}\n\n")
                } else {
                    sb.append("명령어 실행에 실패했습니다. 해당 도구가 설치되어 있지 않을 수 있습니다.\n\n")
                }
            } else {
                sb.append("출력 없음\n\n")
            }
        }

        // 의도별 추가 인사이트
        when (intent.primary) {
            "decompile" -> {
                sb.append("\n인사이트:\n")
                sb.append("- APK의 경우 classes.dex와 AndroidManifest.xml을 중심으로 분석하세요.\n")
                sb.append("- 네이티브 라이브러리(.so)는 lib/ 아래에 위치합니다.\n")
            }
            "security" -> {
                sb.append("\n보안 체크리스트:\n")
                sb.append("- 하드코딩된 키/토큰이 문자열에 노출되었는지 확인하세요.\n")
                sb.append("- HTTP 통신이 포함된 경우 중간자 공격 위험이 있습니다.\n")
                sb.append("- 약한 해시(MD5/SHA1) 사용 여부를 검토하세요.\n")
            }
            "reverse" -> {
                sb.append("\n리버스 엔지니어링 팁:\n")
                sb.append("- JNI 함수 시그니처를 찾아 Java-C 간 매핑을 파악하세요.\n")
                sb.append("- 동적 로딩(dlopen/dlsym) 지점은 후킹 포인트가 됩니다.\n")
            }
            "network" -> {
                sb.append("\n네트워크 분석 팁:\n")
                sb.append("- 추출된 URL/도메인이 C2 서버인지 확인하세요.\n")
                sb.append("- Certificate Pinning 여부를 strings에서 검색해보세요.\n")
            }
        }

        sb.append("\n추가로 궁금한 점이 있으시면 자연어로 물어보세요!")
        return sb.toString()
    }

    /**
     * 파일 형식 자동 감지
     */
    private fun detectFileType(path: String): String {
        val ext = path.substringAfterLast('.', "")
        if (ext.isNotBlank()) return ext.lowercase()

        // 매직 바이트로 판별
        return try {
            val file = File(path)
            if (!file.exists() || file.length() < 4) return "unknown"
            val magic = file.inputStream().use { it.readNBytes(4) }
            when {
                magic.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) -> "zip" // APK/JAR 포함
                magic.contentEquals(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())) -> "elf"
                magic.contentEquals(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte())) -> "pe"
                magic.contentEquals(byteArrayOf(0x64, 0x65, 0x78, 0x0a)) -> "dex"
                else -> "bin"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun computeMD5(file: File): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } > 0) {
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 입력 텍스트에서 URL 추출
     */
    private fun extractUrl(text: String): String? {
        val regex = Regex("(https?://[^\\s<>\"{}|\\^`\\[\\]]+)")
        return regex.find(text)?.groupValues?.get(1)
    }

    /**
     * 입력에 포함된 URL들의 콘텐츠 흡수
     */
    private fun absorbUrls(text: String): String {
        val urls = Regex("(https?://[^\\s<>\"{}|\\^`\\[\\]]+)").findAll(text).map { it.value }.toList()
        if (urls.isEmpty()) return ""
        val sb = StringBuilder()
        for (url in urls) {
            try {
                val content = linkAbsorber.absorbUrl(url)
                sb.append("URL: $url\n$content\n\n")
            } catch (e: Exception) {
                sb.append("URL: $url\n(흡수 실패: ${e.message})\n\n")
            }
        }
        return sb.toString()
    }

    /**
     * 대화형 추가 분석 (이전 결과에 이어서)
     */
    suspend fun continueAnalysis(followUpQuestion: String): String {
        return process(followUpQuestion, currentFilePath)
    }

    /**
     * 현재 세션의 파일 경로 반환
     */
    fun getCurrentFile(): String? = currentFilePath
    fun getCommandHistory(): List<CommandResult> = commandHistory.toList()

    /**
     * 세션 초기화
     */
    fun resetSession() {
        currentFilePath = null
        currentFileType = "unknown"
        commandHistory.clear()
    }
}
