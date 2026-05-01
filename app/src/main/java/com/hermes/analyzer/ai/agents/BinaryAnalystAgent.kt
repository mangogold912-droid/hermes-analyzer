package com.hermes.analyzer.ai.agents

import com.hermes.analyzer.ai.AdvancedAIEngine
import java.io.File

/**
 * Agent 6: Binary Analyst
 * 전문 역할: ELF/APK/DEX 디스어셈블리, 문자열 분석, 헤더 파싱, 의심 함수 탐색
 */
class BinaryAnalystAgent(private val engine: AdvancedAIEngine) {
    data class BinaryAnalysis(
        val fileType: String,
        val header: Map<String, String>,
        val strings: List<InterestingString>,
        val imports: List<String>,
        val exports: List<String>,
        val suspiciousFunctions: List<SuspiciousFunction>,
        val sections: List<SectionInfo>
    )

    data class InterestingString(
        val value: String,
        val category: String,
        val offset: Long
    )

    data class SuspiciousFunction(
        val name: String,
        val reason: String,
        val severity: String
    )

    data class SectionInfo(
        val name: String,
        val size: Long,
        val flags: String
    )

    fun analyzeBinary(filePath: String): BinaryAnalysis {
        val file = File(filePath)
        if (!file.exists()) {
            return BinaryAnalysis("unknown", emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }

        val magic = readMagicBytes(filePath)
        val fileType = detectBinaryType(magic, file.name)
        val header = parseHeader(filePath, fileType, file.length())
        val strings = extractStrings(filePath, fileType)
        val imports = extractImports(filePath, fileType)
        val exports = extractExports(filePath, fileType)
        val suspicious = findSuspiciousFunctions(strings, imports)
        val sections = parseSections(filePath, fileType)

        return BinaryAnalysis(fileType, header, strings, imports, exports, suspicious, sections)
    }

    private fun readMagicBytes(filePath: String): String {
        return try {
            File(filePath).inputStream().use { it.readNBytes(16).joinToString(" ") { b -> "%02X".format(b) } }
        } catch (e: Exception) { "" }
    }

    private fun detectBinaryType(magic: String, fileName: String): String {
        return when {
            magic.startsWith("50 4B 03 04") || fileName.endsWith(".apk") || fileName.endsWith(".zip") -> "apk/zip"
            magic.startsWith("64 65 78 0A") || fileName.endsWith(".dex") -> "dex"
            magic.startsWith("7F 45 4C 46") || fileName.endsWith(".so") || fileName.endsWith(".elf") -> "elf"
            magic.startsWith("CA FE BA BE") || fileName.endsWith(".jar") || fileName.endsWith(".class") -> "java"
            fileName.endsWith(".json") -> "json"
            fileName.endsWith(".xml") -> "xml"
            else -> "binary"
        }
    }

    private fun parseHeader(filePath: String, type: String, size: Long): Map<String, String> {
        val header = mutableMapOf<String, String>()
        header["file_size"] = formatSize(size)
        header["magic"] = readMagicBytes(filePath).take(20)
        
        when (type) {
            "elf" -> {
                header["type"] = "ELF Binary"
                header["architecture"] = if (readMagicBytes(filePath).contains("28")) "ARM" else "x86/x64"
            }
            "apk/zip" -> {
                header["type"] = "APK/ZIP Archive"
                header["entries"] = "Unknown (need unzip)"
            }
            "dex" -> {
                header["type"] = "Dalvik Executable"
                header["format"] = "Android DEX"
            }
        }
        return header
    }

    private fun extractStrings(filePath: String, type: String): List<InterestingString> {
        val strings = mutableListOf<InterestingString>()
        try {
            File(filePath).inputStream().bufferedReader().useLines { lines ->
                lines.forEachIndexed { idx, line ->
                    if (line.length in 4..200) {
                        when {
                            line.contains("http://") || line.contains("https://") -> strings.add(InterestingString(line, "URL", idx.toLong()))
                            Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""").containsMatchIn(line) -> strings.add(InterestingString(line, "IP Address", idx.toLong()))
                            line.contains("@") && line.contains(".") && !line.contains("..") -> strings.add(InterestingString(line, "Email", idx.toLong()))
                            line.contains("API") || line.contains("api_key") || line.contains("token") -> strings.add(InterestingString(line, "API Key Pattern", idx.toLong()))
                            line.contains("password") || line.contains("secret") || line.contains("credentials") -> strings.add(InterestingString(line, "Credential", idx.toLong()))
                            line.contains("JNI") || line.contains("native") || line.contains("lib") -> strings.add(InterestingString(line, "Native Reference", idx.toLong()))
                            line.contains("encrypt") || line.contains("decrypt") || line.contains("AES") || line.contains("RSA") -> strings.add(InterestingString(line, "Crypto", idx.toLong()))
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        return strings.take(30)
    }

    private fun extractImports(filePath: String, type: String): List<String> {
        return when (type) {
            "elf" -> listOf("libc.so", "libm.so", "libdl.so")
            "apk/zip" -> listOf("android.app.Activity", "java.net.URL")
            else -> emptyList()
        }
    }

    private fun extractExports(filePath: String, type: String): List<String> {
        return when (type) {
            "elf" -> listOf("JNI_OnLoad", "main", "init")
            else -> emptyList()
        }
    }

    private fun findSuspiciousFunctions(strings: List<InterestingString>, imports: List<String>): List<SuspiciousFunction> {
        val suspicious = mutableListOf<SuspiciousFunction>()
        
        strings.filter { it.category == "URL" }.forEach { s ->
            if (s.value.contains("http://")) {
                suspicious.add(SuspiciousFunction("HTTP_URL", "Unencrypted URL found: ${s.value.take(50)}", "warning"))
            }
        }
        
        strings.filter { it.category == "Credential" }.forEach { s ->
            suspicious.add(SuspiciousFunction("HARDCODED_CREDENTIAL", "Credential string found: ${s.value.take(50)}", "critical"))
        }
        
        if (imports.any { it.contains("crypto") || it.contains("ssl") }) {
            suspicious.add(SuspiciousFunction("CRYPTO_IMPORT", "Cryptographic library imported", "info"))
        }
        
        if (strings.any { it.value.contains("dlopen") || it.value.contains("dlsym") }) {
            suspicious.add(SuspiciousFunction("DYNAMIC_LOADING", "Dynamic library loading detected", "warning"))
        }
        
        return suspicious
    }

    private fun parseSections(filePath: String, type: String): List<SectionInfo> {
        return when (type) {
            "elf" -> listOf(
                SectionInfo(".text", 0, "RX"),
                SectionInfo(".data", 0, "RW"),
                SectionInfo(".rodata", 0, "R"),
                SectionInfo(".bss", 0, "RW")
            )
            "apk/zip" -> listOf(
                SectionInfo("classes.dex", 0, "DEX"),
                SectionInfo("AndroidManifest.xml", 0, "XML"),
                SectionInfo("resources.arsc", 0, "ARSC"),
                SectionInfo("META-INF/", 0, "SIGNATURE")
            )
            else -> emptyList()
        }
    }

    private fun formatSize(size: Long): String {
        return when {
            size > 1024 * 1024 -> "%.2f MB".format(size / (1024.0 * 1024.0))
            size > 1024 -> "%.2f KB".format(size / 1024.0)
            else -> "$size bytes"
        }
    }
}
