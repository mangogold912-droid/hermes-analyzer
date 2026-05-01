package com.hermes.analyzer.ai.agents

import com.hermes.analyzer.ai.AdvancedAIEngine
import kotlinx.coroutines.*

/**
 * Agent 2: Security Inspector
 * 전문 역할: 취약점 스캔, 암호화 오용 감지, 보안 안티패턴 탐색
 */
class SecurityInspectorAgent(private val engine: AdvancedAIEngine) {
    data class SecurityReport(
        val score: Int,
        val criticalCount: Int,
        val warningCount: Int,
        val infoCount: Int,
        val findings: List<SecurityFinding>,
        val recommendations: List<String>
    )

    data class SecurityFinding(
        val severity: String,
        val category: String,
        val description: String,
        val evidence: String,
        val fix: String,
        val cweId: String? = null
    )

    suspend fun inspect(content: String, fileType: String = "code"): SecurityReport {
        val findings = mutableListOf<SecurityFinding>()
        
        findings.addAll(scanCryptoMisuse(content))
        findings.addAll(scanNetworkIssues(content))
        findings.addAll(scanInputValidation(content))
        findings.addAll(scanHardcodedSecrets(content))
        findings.addAll(scanPermissionIssues(content, fileType))
        findings.addAll(scanNativeVulnerabilities(content))

        val critical = findings.count { it.severity == "critical" }
        val warnings = findings.count { it.severity == "warning" }
        val infos = findings.count { it.severity == "info" }
        val score = maxOf(0, 100 - critical * 20 - warnings * 5 - infos * 1)

        val recommendations = findings.map { it.fix }.distinct().take(10)

        return SecurityReport(
            score = score,
            criticalCount = critical,
            warningCount = warnings,
            infoCount = infos,
            findings = findings.sortedBy { 
                when(it.severity) { "critical" -> 0; "warning" -> 1; else -> 2 }
            },
            recommendations = recommendations
        )
    }

    private fun scanCryptoMisuse(content: String): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()
        if (content.contains("MD5") || content.contains("md5")) {
            findings.add(SecurityFinding("warning", "crypto", "Weak hash algorithm MD5 detected", "MD5 usage found", "Replace with SHA-256 or bcrypt", "CWE-328"))
        }
        if (content.contains("SHA1") || content.contains("sha1")) {
            findings.add(SecurityFinding("warning", "crypto", "Weak hash algorithm SHA-1 detected", "SHA-1 usage found", "Replace with SHA-256 or SHA-3", "CWE-328"))
        }
        if (content.contains("ECB") || content.contains("ecb")) {
            findings.add(SecurityFinding("critical", "crypto", "Insecure ECB mode detected", "ECB mode usage", "Use GCM or CBC with IV", "CWE-329"))
        }
        if (content.contains("DES") || content.contains("des")) {
            findings.add(SecurityFinding("critical", "crypto", "Weak encryption DES detected", "DES usage found", "Replace with AES-256-GCM", "CWE-326"))
        }
        if (content.contains("Random()") && !content.contains("SecureRandom")) {
            findings.add(SecurityFinding("warning", "crypto", "Insecure random number generator", "java.util.Random usage", "Use java.security.SecureRandom", "CWE-338"))
        }
        if (content.contains("setSeed")) {
            findings.add(SecurityFinding("critical", "crypto", "PRNG seed may be predictable", "Manual seeding detected", "Let system provide entropy", "CWE-335"))
        }
        return findings
    }

    private fun scanNetworkIssues(content: String): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()
        if (content.contains("http://") && !content.contains("localhost")) {
            findings.add(SecurityFinding("warning", "network", "Unencrypted HTTP communication", "http:// URL found", "Use https:// with certificate pinning", "CWE-319"))
        }
        if (content.contains("setVerifySsl") || content.contains("verifySsl") || content.contains("verify=false")) {
            findings.add(SecurityFinding("critical", "network", "SSL verification disabled", "SSL verification bypass found", "Enable full certificate chain validation", "CWE-295"))
        }
        if (content.contains("allowAllHostnames") || content.contains("HostnameVerifier") && content.contains("true")) {
            findings.add(SecurityFinding("critical", "network", "Hostname verification bypassed", "All hostnames allowed", "Implement proper hostname verification", "CWE-297"))
        }
        if (Regex("""openStream\(\)|URLConnection|Socket\(""") in content) {
            findings.add(SecurityFinding("info", "network", "Raw network connection detected", "Low-level network API usage", "Use high-level libraries with security defaults", null))
        }
        return findings
    }

    private fun scanInputValidation(content: String): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()
        if (content.contains("Runtime.getRuntime().exec") || content.contains("ProcessBuilder")) {
            findings.add(SecurityFinding("critical", "injection", "Command injection risk", "Process execution with user input", "Validate and sanitize all inputs", "CWE-78"))
        }
        if (content.contains("execSQL") || content.contains("rawQuery")) {
            if (!content.contains("?") && !content.contains("selectionArgs")) {
                findings.add(SecurityFinding("critical", "injection", "Potential SQL injection", "SQL without parameterization", "Use parameterized queries", "CWE-89"))
            }
        }
        if (content.contains("eval") || content.contains("exec(")) {
            findings.add(SecurityFinding("critical", "injection", "Code injection risk", "Dynamic code evaluation", "Avoid eval, use parsers", "CWE-94"))
        }
        if (content.contains("File(") && content.contains("..") || content.contains("../")) {
            findings.add(SecurityFinding("warning", "path", "Path traversal risk", "Relative path manipulation", "Canonicalize and validate paths", "CWE-22"))
        }
        return findings
    }

    private fun scanHardcodedSecrets(content: String): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()
        val patterns = listOf(
            Regex("""[\"']AIza[\w-]{35}[\"']""") to "Google API key",
            Regex("""[\"']ghp_[\w]{36}[\"']""") to "GitHub token",
            Regex("""[\"']sk-[\w]{48}[\"']""") to "OpenAI API key",
            Regex("""[\"']AKIA[\w]{16}[\"']""") to "AWS Access Key",
            Regex("""password\s*=\s*[\"'][^\"']{4,}[\"']""") to "Hardcoded password",
            Regex("""secret\s*=\s*[\"'][^\"']{4,}[\"']""") to "Hardcoded secret",
            Regex("""api[_-]?key\s*=\s*[\"'][^\"']{8,}[\"']""") to "API key"
        )
        patterns.forEach { (regex, type) ->
            regex.findAll(content).forEach { match ->
                findings.add(SecurityFinding("critical", "secret", "Hardcoded $type detected", match.value.take(50) + "...", "Move to environment variables or KeyStore", "CWE-798"))
            }
        }
        return findings
    }

    private fun scanPermissionIssues(content: String, fileType: String): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()
        if (fileType == "apk" || content.contains("AndroidManifest")) {
            if (content.contains("INTERNET") && content.contains("usesCleartextTraffic=\"true\"")) {
                findings.add(SecurityFinding("warning", "permission", "Cleartext traffic enabled", "usesCleartextTraffic=true", "Disable or restrict to specific domains", "CWE-319"))
            }
            if (content.contains("MANAGE_EXTERNAL_STORAGE")) {
                findings.add(SecurityFinding("info", "permission", "Broad file access requested", "MANAGE_EXTERNAL_STORAGE permission", "Use scoped storage with specific intents", null))
            }
        }
        return findings
    }

    private fun scanNativeVulnerabilities(content: String): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()
        val dangerous = listOf("strcpy", "strcat", "gets", "sprintf", "scanf", "memcpy")
        dangerous.forEach { func ->
            if (content.contains(func)) {
                findings.add(SecurityFinding("warning", "native", "Unsafe C function: $func", "$func() usage detected", "Use ${func}n_s or safer alternatives", "CWE-120"))
            }
        }
        if (content.contains("JNI") && content.contains("NewStringUTF")) {
            findings.add(SecurityFinding("warning", "native", "JNI string handling risk", "NewStringUTF usage", "Validate UTF-8 encoding", "CWE-839"))
        }
        return findings
    }
}
