package com.hermes.analyzer.ai

import android.content.Context
import android.util.Log
import com.hermes.analyzer.sandbox.SandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DeobfuscationEngine
 * 바이너리/코드에서 난독화를 탐지하고 해제를 시도하는 엔진.
 *
 * 기능:
 *   1. 제어 흐름 난독화 탐지 (Control Flow Flattening, Opaque Predicate, 불필요한 JMP)
 *   2. 문자열 난독화 분석 및 해제 (XOR, Base64, ROT13, Split String, Custom Encoding)
 *   3. 패킹/압축 탐지 (UPX, Themida, VMProtect, Custom Packer)
 *   4. 가상화 난독화 흔적 탐지 (VM-based obfuscation trace)
 *   5. 난독화 해제 시도 (brute-force XOR key, Base64 decode, string reconstruct)
 *   6. 자연어 보고서 출력
 */
class DeobfuscationEngine(private val context: Context) {
    private val TAG = "DeobfuscationEngine"
    private val sandbox = SandboxManager(context)

    data class ObfuscationFinding(
        val type: String,           // control_flow, string_obfuscation, packing, virtualization
        val subtype: String,        // flattening, opaque_predicate, xor, base64, upx, etc.
        val confidence: Float,      // 0.0 ~ 1.0
        val evidence: String,
        val offset: String?,        // 파일 오프셋 (있는 경우)
        val severity: String        // Critical, High, Medium, Low
    )

    data class DeobfuscatedString(
        val original: String,
        val decoded: String,
        val method: String,         // xor, base64, rot13, reversed, split
        val key: String?,           // XOR key 등
        val offset: String?,
        val isReadable: Boolean     // 사람이 읽을 수 있는지
    )

    data class DeobfuscationReport(
        val filePath: String,
        val fileType: String,
        val findings: List<ObfuscationFinding>,
        val deobfuscatedStrings: List<DeobfuscatedString>,
        val isPacked: Boolean,
        val packerName: String?,
        val controlFlowObfuscated: Boolean,
        val stringObfuscated: Boolean,
        val summaryMarkdown: String,
        val rawOutput: String
    )

    // 패커 시그니처 (매직 바이트/섹션 이름)
    private val packerSignatures = mapOf(
        "UPX" to listOf("UPX", "UPX0", "UPX1", "UPX!", "upx"),
        "Themida" to listOf("Themida", ".themida", "WinLicense"),
        "VMProtect" to listOf("VMProtect", "vmp", "vmp0", "vmp1"),
        "Enigma" to listOf("Enigma", ".enigma"),
        "ASPacker" to listOf("aspack", "ASPack"),
        "Petite" to listOf("petite", ".petite"),
        "PECompact" to listOf("pecompact", "PEC2"),
        "MPRESS" to listOf("MPRESS", "mpress"),
        "ConfuserEx" to listOf("ConfuserEx", "confuser", "Confuser"),
        "Dotfuscator" to listOf("Dotfuscator", "dotfuscator"),
        "ProGuard" to listOf("ProGuard", "proguard", "PG", "R8"),
        "DexGuard" to listOf("DexGuard", "dexguard", "Dexprotector"),
        "OLLVM" to listOf("obfuscator", "ollvm", "fla", "sub", "bcf"),
        "Hikari" to listOf("Hikari", "hikari"),
        "Tigress" to listOf("Tigress", "tigress")
    )

    // 제어 흐름 난독화 키워드
    private val cfObfuscationIndicators = listOf(
        "jmp", "jz", "jnz", "je", "jne", "ja", "jb", "jl", "jg",
        "call", "ret", "nop", "push", "pop",
        "dispatcher", "flatten", "switch", "state_machine",
        "__stack_chk_guard", "__stack_chk_fail"
    )

    /**
     * 메인 진입점: 파일 분석
     */
    suspend fun analyze(filePath: String): DeobfuscationReport = withContext(Dispatchers.IO) {
        val file = File(filePath)
        val fileType = filePath.substringAfterLast('.', "bin")

        // 1. 샌드박스 명령어 실행
        val rawOutput = runDetectionCommands(filePath, fileType)

        // 2. 패킹 탐지
        val (isPacked, packerName) = detectPacker(filePath, rawOutput)

        // 3. 제어 흐름 난독화 탐지
        val cfFindings = detectControlFlowObfuscation(rawOutput, fileType)

        // 4. 문자열 난독화 탐지 및 해제
        val (stringFindings, deobfuscated) = analyzeStringObfuscation(filePath, rawOutput)

        // 5. 가상화 탐지
        val vmFindings = detectVirtualization(rawOutput, fileType)

        // 6. 종합
        val allFindings = cfFindings + stringFindings + vmFindings
        val cfObf = cfFindings.any { it.confidence > 0.5f }
        val strObf = stringFindings.isNotEmpty()

        val report = generateReport(filePath, fileType, allFindings, deobfuscated, isPacked, packerName, cfObf, strObf)

        DeobfuscationReport(
            filePath = filePath,
            fileType = fileType,
            findings = allFindings,
            deobfuscatedStrings = deobfuscated,
            isPacked = isPacked,
            packerName = packerName,
            controlFlowObfuscated = cfObf,
            stringObfuscated = strObf,
            summaryMarkdown = report,
            rawOutput = rawOutput
        )
    }

    /**
     * 샌드박스에서 탐지 명령어 실행
     */
    private fun runDetectionCommands(filePath: String, fileType: String): String {
        val sb = StringBuilder()
        val sandboxId = "sandbox-binary"

        val baseCmds = listOf(
            "file \"$filePath\"",
            "strings \"$filePath\" | head -150",
            "strings \"$filePath\" | grep -iE 'upx|themida|vmprotect|packer|protect|obfusc|compress|crypt' | head -30",
            "xxd -l 512 \"$filePath\""
        )

        val typeCmds = when (fileType) {
            "elf", "so" -> listOf(
                "readelf -h \"$filePath\" 2>/dev/null || echo 'no readelf'",
                "readelf -S \"$filePath\" 2>/dev/null || echo 'no readelf'",
                "readelf -s \"$filePath\" 2>/dev/null | head -40 || echo 'no readelf'",
                "objdump -d \"$filePath\" 2>/dev/null | head -80 || echo 'no objdump'",
                "nm -D \"$filePath\" 2>/dev/null | head -30 || echo 'no nm'"
            )
            "dex" -> listOf(
                "strings \"$filePath\" | grep -iE 'class|method|field|proguard|R8|r8' | head -30",
                "strings \"$filePath\" | grep -iE 'a\\.b\\.c|aaa|bbb|obfusc|shrink|minify' | head -20"
            )
            "apk" -> listOf(
                "unzip -l \"$filePath\" | grep -iE 'lib|dex|so|assets|classes'",
                "strings \"$filePath\" | grep -iE 'proguard|R8|dexguard|obfusc|mapping' | head -20",
                "strings \"$filePath\" | grep -oE 'classes[0-9]*\\.dex' | sort -u"
            )
            "pe", "exe", "dll" -> listOf(
                "strings \"$filePath\" | grep -iE 'upx|themida|vmprotect|petite|aspack|enigma|mpress' | head -20",
                "strings \"$filePath\" | grep -iE '.text|.code|entry|start|main' | head -20"
            )
            else -> listOf(
                "strings \"$filePath\" | grep -iE 'obfusc|pack|protect|crypt|encode|jmp|nop|flatten' | head -30",
                "xxd -l 1024 \"$filePath\""
            )
        }

        for (cmd in baseCmds + typeCmds) {
            val res = sandbox.execute(sandboxId, cmd, 30)
            sb.append("### CMD: ${cmd.take(80)}...\n")
            if (res.stdout.isNotBlank()) sb.append(res.stdout).append("\n")
            if (res.stderr.isNotBlank() && !res.stderr.contains("not available") && !res.stderr.contains("No such")) {
                sb.append("[stderr: ${res.stderr.take(200)}]\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * 패킹/압축 탐지
     */
    private fun detectPacker(filePath: String, rawOutput: String): Pair<Boolean, String?> {
        val lowerOutput = rawOutput.lowercase()
        for ((packer, signatures) in packerSignatures) {
            for (sig in signatures) {
                if (lowerOutput.contains(sig.lowercase())) {
                    Log.i(TAG, "Packer detected: $packer (signature=$sig)")
                    return true to packer
                }
            }
        }

        // ELF 섹션 수 이상 징후 (패킹된 ELF는 섹션이 적음)
        val sectionCount = Regex("There are (\\d+) section headers").find(rawOutput)?.groupValues?.get(1)?.toIntOrNull()
        if (sectionCount != null && sectionCount < 5) {
            return true to "Unknown (suspicious: $sectionCount sections)"
        }

        // 엔트로피 기반 흉내 (고엔트로피 = 암호화/압축)
        val file = File(filePath)
        if (file.exists() && file.length() > 0) {
            val entropy = calculateShannonEntropy(file)
            if (entropy > 7.5) {
                return true to "Unknown (high entropy: %.2f)".format(entropy)
            }
        }

        return false to null
    }

    /**
     * 제어 흐름 난독화 탐지
     */
    private fun detectControlFlowObfuscation(rawOutput: String, fileType: String): List<ObfuscationFinding> {
        val findings = mutableListOf<ObfuscationFinding>()
        val lines = rawOutput.lines()

        // O-LLVM / Control Flow Flattening 징후
        val flatteningPatterns = listOf(
            "state_machine", "dispatcher", "flatten", "switch_table",
            "reached_maximal", "llvm", "obfuscator", "bcf", "fla", "sub"
        )
        var flatteningHits = 0
        for (line in lines) {
            if (flatteningPatterns.any { line.lowercase().contains(it) }) flatteningHits++
        }
        if (flatteningHits >= 2) {
            findings.add(ObfuscationFinding(
                type = "control_flow",
                subtype = "flattening",
                confidence = (flatteningHits * 0.15f).coerceAtMost(1f),
                evidence = "$flatteningHits flattening indicators found in strings/disassembly",
                offset = null,
                severity = "High"
            ))
        }

        // 불필요한 분기/Opaque Predicate (x86/ARM)
        val jmpCount = lines.count { it.trim().startsWith("jmp") || it.trim().startsWith("b ") || it.contains("nop") }
        if (jmpCount > 20) {
            findings.add(ObfuscationFinding(
                type = "control_flow",
                subtype = "opaque_predicate",
                confidence = (jmpCount / 100f).coerceAtMost(1f),
                evidence = "$jmpCount jump/branch instructions in disassembly output",
                offset = null,
                severity = "Medium"
            ))
        }

        // 함수명 난독화 (짧은 이름, 패턴)
        val obfuscatedNames = lines.filter {
            val l = it.trim()
            l.matches(Regex("[a-zA-Z]{1,3}_[a-zA-Z0-9]{1,4}")) ||
            l.matches(Regex("[a-z]{1,2}[A-Z][a-z]{1,2}[0-9]{1,3}")) ||
            l.matches(Regex("func_[0-9a-fA-F]+")) ||
            l.matches(Regex("sub_[0-9a-fA-F]+"))
        }
        if (obfuscatedNames.size > 10) {
            findings.add(ObfuscationFinding(
                type = "control_flow",
                subtype = "symbol_obfuscation",
                confidence = (obfuscatedNames.size / 50f).coerceAtMost(1f),
                evidence = "${obfuscatedNames.size} obfuscated symbol names detected",
                offset = null,
                severity = "Medium"
            ))
        }

        return findings
    }

    /**
     * 문자열 난독화 분석 및 해제 시도
     */
    private fun analyzeStringObfuscation(filePath: String, rawOutput: String): Pair<List<ObfuscationFinding>, List<DeobfuscatedString>> {
        val findings = mutableListOf<ObfuscationFinding>()
        val deobfuscated = mutableListOf<DeobfuscatedString>()
        val lines = rawOutput.lines().filter { it.isNotBlank() }
        val allStrings = lines.filter { it.length in 8..200 }

        // 1. Base64 난독화 탐지 및 해제
        val b64Regex = Regex("([A-Za-z0-9+/]{20,}={0,2})")
        val b64Candidates = allStrings.mapNotNull { b64Regex.find(it)?.value }
        for (candidate in b64Candidates.distinct().take(50)) {
            val decoded = tryDecodeBase64(candidate)
            if (decoded != null && decoded.length > 5 && decoded.isReadable()) {
                deobfuscated.add(DeobfuscatedString(
                    original = candidate,
                    decoded = decoded,
                    method = "base64",
                    key = null,
                    offset = null,
                    isReadable = true
                ))
            }
        }
        if (deobfuscated.any { it.method == "base64" }) {
            findings.add(ObfuscationFinding(
                type = "string_obfuscation",
                subtype = "base64",
                confidence = 0.8f,
                evidence = "${deobfuscated.count { it.method == "base64" }} Base64-encoded strings decoded",
                offset = null,
                severity = "Medium"
            ))
        }

        // 2. XOR 난독화 탐지 (hex pattern + common keys)
        val hexStrings = allStrings.filter { it.matches(Regex("[0-9a-fA-F]{16,}")) }
        val xorKeys = listOf(0x55, 0xAA, 0xFF, 0x12, 0x34, 0x56, 0x78, 0x9A, 0xAB, 0xCD, 0xEF, 0x11, 0x22, 0x33, 0x44, 0x77, 0x88, 0x99, 0xBB, 0xCC, 0xDD, 0xEE)
        for (hexStr in hexStrings.take(20)) {
            for (key in xorKeys) {
                val decoded = tryXorHexDecode(hexStr, key)
                if (decoded != null && decoded.isReadable() && decoded.length > 4) {
                    deobfuscated.add(DeobfuscatedString(
                        original = hexStr,
                        decoded = decoded,
                        method = "xor",
                        key = "0x%02X".format(key),
                        offset = null,
                        isReadable = true
                    ))
                    break
                }
            }
        }
        if (deobfuscated.any { it.method == "xor" }) {
            findings.add(ObfuscationFinding(
                type = "string_obfuscation",
                subtype = "xor",
                confidence = 0.7f,
                evidence = "${deobfuscated.count { it.method == "xor" }} XOR-obfuscated strings decoded",
                offset = null,
                severity = "High"
            ))
        }

        // 3. ROT13 탐지
        for (str in allStrings.take(100)) {
            if (str.length > 10 && str.all { it.isLetter() || it.isDigit() || it in "+/=" }) {
                val rot13 = rot13Decode(str)
                if (rot13.contains("http") || rot13.contains(".") || rot13.contains("api")) {
                    deobfuscated.add(DeobfuscatedString(
                        original = str,
                        decoded = rot13,
                        method = "rot13",
                        key = null,
                        offset = null,
                        isReadable = true
                    ))
                }
            }
        }
        if (deobfuscated.any { it.method == "rot13" }) {
            findings.add(ObfuscationFinding(
                type = "string_obfuscation",
                subtype = "rot13",
                confidence = 0.6f,
                evidence = "ROT13 encoding detected",
                offset = null,
                severity = "Low"
            ))
        }

        // 4. 분할 문자열 (Split String) 탐지
        val splitCandidates = lines.filter {
            it.matches(Regex("([a-zA-Z0-9]{1,3})\\+([a-zA-Z0-9]{1,3})\\+([a-zA-Z0-9]{1,3})")) ||
            it.contains("concat") || it.contains("append") || it.contains("StringBuilder")
        }
        if (splitCandidates.size > 5) {
            findings.add(ObfuscationFinding(
                type = "string_obfuscation",
                subtype = "split_string",
                confidence = 0.5f,
                evidence = "${splitCandidates.size} split-string concatenation patterns",
                offset = null,
                severity = "Medium"
            ))
        }

        // 5. 문자열 전체가 난독화된 징후 (짧은 문자열 비율)
        val veryShortStrings = allStrings.count { it.length <= 3 }
        val totalStrings = allStrings.size.coerceAtLeast(1)
        if (veryShortStrings.toFloat() / totalStrings > 0.3f && totalStrings > 50) {
            findings.add(ObfuscationFinding(
                type = "string_obfuscation",
                subtype = "fragmentation",
                confidence = 0.6f,
                evidence = "${(veryShortStrings * 100 / totalStrings)}% of strings are <=3 chars (fragmentation)",
                offset = null,
                severity = "Medium"
            ))
        }

        return findings to deobfuscated.distinctBy { it.original }
    }

    /**
     * 가상화 난독화(VM-based) 탐지
     */
    private fun detectVirtualization(rawOutput: String, fileType: String): List<ObfuscationFinding> {
        val findings = mutableListOf<ObfuscationFinding>()
        val lower = rawOutput.lowercase()

        val vmIndicators = listOf("vmprotect", "vm_enter", "vm_exit", "vm_dispatch", "vm_handler", "vmrun", "virtual machine", "bytecode", "vm_interp", "handler_table")
        val vmHits = vmIndicators.count { lower.contains(it) }

        if (vmHits >= 1) {
            findings.add(ObfuscationFinding(
                type = "virtualization",
                subtype = "vmprotect",
                confidence = (vmHits * 0.25f + 0.5f).coerceAtMost(1f),
                evidence = "$vmHits virtualization indicators found",
                offset = null,
                severity = "Critical"
            ))
        }

        // Tigress 가상화
        if (lower.contains("tigress") || lower.contains("virt") && lower.contains("obf")) {
            findings.add(ObfuscationFinding(
                type = "virtualization",
                subtype = "tigress",
                confidence = 0.7f,
                evidence = "Tigress virtualization obfuscation indicators",
                offset = null,
                severity = "High"
            ))
        }

        return findings
    }

    /**
     * 보고서 생성
     */
    private fun generateReport(
        filePath: String,
        fileType: String,
        findings: List<ObfuscationFinding>,
        deobfuscated: List<DeobfuscatedString>,
        isPacked: Boolean,
        packerName: String?,
        cfObfuscated: Boolean,
        strObfuscated: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("난독화 분석 및 해제 보고서\n")
        sb.append("=" .repeat(40)).append("\n\n")
        sb.append("분석 파일: $filePath\n")
        sb.append("파일 형식: ${fileType.uppercase()}\n")

        // 패킹 상태
        sb.append("\n패킹 상태: ")
        if (isPacked) {
            sb.append("패킹됨 (${packerName ?: "Unknown Packer"})\n")
            sb.append("패킹 해제를 먼저 수행해야 내부 분석이 가능합니다.\n")
        } else {
            sb.append("패킹되지 않음 (원본 코드 직접 분석 가능)\n")
        }

        // 난독화 종합 평가
        sb.append("\n난독화 종합 평가:\n")
        sb.append("-".repeat(40)).append("\n")
        sb.append("제어 흐름 난독화: ${if (cfObfuscated) "감지됨" else "미감지"}\n")
        sb.append("문자열 난독화: ${if (strObfuscated) "감지됨" else "미감지"}\n")
        sb.append("패킹: ${if (isPacked) "감지됨" else "미감지"}\n")
        sb.append("총 탐지 항목: ${findings.size}개\n")

        // 탐지된 난독화 상세
        if (findings.isNotEmpty()) {
            sb.append("\n\n난독화 탐지 상세:\n")
            sb.append("-".repeat(40)).append("\n")
            findings.sortedByDescending { it.confidence }.forEach { f ->
                val icon = when (f.severity) {
                    "Critical" -> ""
                    "High" -> ""
                    "Medium" -> ""
                    else -> ""
                }
                sb.append("[$icon ${f.severity}] ${f.type} / ${f.subtype}\n")
                sb.append("  신뢰도: ${(f.confidence * 100).toInt()}%\n")
                sb.append("  근거: ${f.evidence}\n")
                if (f.offset != null) sb.append("  오프셋: ${f.offset}\n")
                sb.append("\n")
            }
        }

        // 해제된 문자열
        if (deobfuscated.isNotEmpty()) {
            sb.append("\n\n해제된 문자열 (${deobfuscated.size}개):\n")
            sb.append("-".repeat(40)).append("\n")
            deobfuscated.take(30).forEach { ds ->
                sb.append("[${ds.method}${ds.key?.let { ", key=$it" } ?: ""}]\n")
                sb.append("  원본: ${ds.original.take(60)}\n")
                sb.append("  해제: ${ds.decoded.take(100)}\n\n")
            }
            if (deobfuscated.size > 30) {
                sb.append("... 외 ${deobfuscated.size - 30}개 더 존재\n")
            }
        }

        // 권장 해제 전략
        sb.append("\n\n난독화 해제 전략:\n")
        sb.append("-".repeat(40)).append("\n")
        if (isPacked) {
            sb.append("1. 패킹 해제: UPX의 경우 'upx -d', 다른 패커는 디버거로 메모리 덤프 필요\n")
        }
        if (cfObfuscated) {
            sb.append("2. 제어 흐복 해제:\n")
            sb.append("   - O-LLVM flattening: symbolic execution 또는 dynamic taint analysis 권장\n")
            sb.append("   - Opaque Predicate: SMT solver (Z3)로 가짜 분기 제거\n")
        }
        if (strObfuscated) {
            sb.append("3. 문자열 해제:\n")
            sb.append("   - XOR: brute-force key 탐색 (이 엔진이 자동으로 시도함)\n")
            sb.append("   - Base64: 표준 decode로 복원 가능\n")
            sb.append("   - Split string: runtime hooking으로 조합 지점 추적\n")
        }
        if (findings.any { it.type == "virtualization" }) {
            sb.append("4. VM-based obfuscation:\n")
            sb.append("   - VM handler table 복원 필요 (고급 분석)\n")
            sb.append("   - Trace-based devirtualization 또는 VM Profiler 사용 권장\n")
        }
        sb.append("\n권장 도구:\n")
        sb.append("  - 정적: IDA Pro/Ghidra + decompiler, Binary Ninja\n")
        sb.append("  - 동적: Frida, Xposed, debugger attach\n")
        sb.append("  - 자동화: Triton, Angr, Miasm\n")

        return sb.toString()
    }

    // 유틸리티

    private fun tryDecodeBase64(s: String): String? {
        return try {
            val clean = s.replace(Regex("[^A-Za-z0-9+/=]"), "")
            if (clean.length % 4 != 0) return null
            val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    private fun tryXorHexDecode(hex: String, key: Int): String? {
        return try {
            val bytes = hex.chunked(2).map { it.toInt(16) }.toIntArray()
            val decoded = bytes.map { (it xor key).toChar() }.joinToString("")
            decoded
        } catch (e: Exception) { null }
    }

    private fun rot13Decode(s: String): String {
        return s.map { c ->
            when {
                c in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                c in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                else -> c
            }
        }.joinToString("")
    }

    private fun String.isReadable(): Boolean {
        return this.all { it in '\u0020'..'\u007E' || it == '\n' || it == '\t' }
    }

    /**
     * 샤논 엔트로피 계산 (패킹 탐지용)
     */
    private fun calculateShannonEntropy(file: File): Double {
        return try {
            val bytes = file.inputStream().use { it.readNBytes(8192.coerceAtMost(file.length().toInt())) }
            if (bytes.isEmpty()) return 0.0
            val freq = IntArray(256)
            for (b in bytes) freq[b.toInt() and 0xFF]++
            val len = bytes.size.toDouble()
            var entropy = 0.0
            for (count in freq) {
                if (count == 0) continue
                val p = count / len
                entropy -= p * kotlin.math.log2(p)
            }
            entropy
        } catch (e: Exception) {
            0.0
        }
    }
}
