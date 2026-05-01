package com.hermes.analyzer.ai

import android.content.Context
import android.util.Log
import com.hermes.analyzer.sandbox.SandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.util.regex.Pattern

/**
 * NetworkAnalyzer
 * 바이너리/코드 내 하드코딩된 서버 주소(Endpoint), URL, IP, 도메인을
 * 샌드박스에서 실제 명령어로 추출하고 위험도를 평가하여 자연어 보고서를 생성.
 *
 * 기능:
 *   1. 문자열 테이블에서 네트워크 관련 문자열 추출 (strings + grep)
 *   2. 하드코딩된 URL/IP/도메인/포트/API 경로 탐지
 *   3. 인코딩/난독화된 엔드포인트 탐지 (Base64 URL, split strings)
 *   4. 서버 역할 분류 (C2, CDN, 업데이트, 광고, 분석, API)
 *   5. 위험도 평가 (HTTP/HTTPS, 하드코딩/동적, 알려진 도메인 여부)
 *   6. 자연어 보고서 출력
 */
class NetworkAnalyzer(private val context: Context) {
    private val TAG = "NetworkAnalyzer"
    private val sandbox = SandboxManager(context)

    data class Endpoint(
        val rawValue: String,
        val type: String,           // url, ip, domain, api_path, port
        val protocol: String?,      // http, https, ws, wss, tcp, udp
        val host: String?,
        val port: Int?,
        val path: String?,
        val isHardcoded: Boolean,
        val encodingType: String?,  // plain, base64, xor, split, obfuscated
        val decodedValue: String?,
        val category: String,       // c2, cdn, update, ad, analytics, api, unknown
        val riskLevel: String       // Critical, High, Medium, Low, Info
    )

    data class NetworkReport(
        val filePath: String,
        val fileType: String,
        val totalEndpoints: Int,
        val endpoints: List<Endpoint>,
        val hardcodedCount: Int,
        val dynamicCount: Int,
        val httpCount: Int,
        val httpsCount: Int,
        val highRiskEndpoints: List<Endpoint>,
        val summaryMarkdown: String,
        val rawOutput: String
    )

    // 알려진 도메인 카테고리 DB
    private val knownDomains = mapOf(
        // C2 / 악성 가능성
        "pastes.io" to "c2", "pastebin.com" to "c2", "rentry.co" to "c2",
        "ghostbin.co" to "c2", "termbin.com" to "c2", "transfer.sh" to "c2",
        // CDN
        "cloudfront.net" to "cdn", "akamai.net" to "cdn", "fastly.net" to "cdn",
        "googleusercontent.com" to "cdn", "amazonaws.com" to "cdn",
        // 광고
        "googleadservices.com" to "ad", "doubleclick.net" to "ad",
        "googlesyndication.com" to "ad", "facebook.com/tr" to "ad",
        "appsflyer.com" to "ad", "adjust.com" to "ad",
        // 분석
        "google-analytics.com" to "analytics", "firebaseio.com" to "analytics",
        "crashlytics.com" to "analytics", "mixpanel.com" to "analytics",
        "amplitude.com" to "analytics", "sentry.io" to "analytics",
        // 업데이트
        "github.com" to "update", "gitlab.com" to "update",
        "raw.githubusercontent.com" to "update", "bitbucket.org" to "update"
    )

    /**
     * 파일에서 네트워크 엔드포인트 전체 분석
     */
    suspend fun analyze(filePath: String): NetworkReport = withContext(Dispatchers.IO) {
        val file = File(filePath)
        val fileType = filePath.substringAfterLast('.', "bin")

        // 1. 기본 명령어 실행
        val rawOutput = extractNetworkStrings(filePath)

        // 2. 엔드포인트 파싱
        val endpoints = parseEndpoints(rawOutput)

        // 3. 카테고리 분류 및 위험도 평가
        val classified = endpoints.map { classifyAndScore(it) }

        // 4. 통계
        val hardcoded = classified.count { it.isHardcoded }
        val dynamic = classified.count { !it.isHardcoded }
        val http = classified.count { it.protocol == "http" }
        val https = classified.count { it.protocol == "https" }
        val highRisk = classified.filter { it.riskLevel in listOf("Critical", "High") }

        // 5. 보고서 생성
        val report = generateReport(filePath, fileType, classified, rawOutput)

        NetworkReport(
            filePath = filePath,
            fileType = fileType,
            totalEndpoints = classified.size,
            endpoints = classified,
            hardcodedCount = hardcoded,
            dynamicCount = dynamic,
            httpCount = http,
            httpsCount = https,
            highRiskEndpoints = highRisk,
            summaryMarkdown = report,
            rawOutput = rawOutput
        )
    }

    /**
     * 샌드박스에서 네트워크 문자열 추출
     */
    private fun extractNetworkStrings(filePath: String): String {
        val sb = StringBuilder()
        val sandboxId = "sandbox-binary"
        val cmds = listOf(
            // 기본 URL/도메인 추출
            "strings \"$filePath\" | grep -oE 'https?://[^\\s<>\"{}|^`\\[\\]]+' | sort -u",
            // IP 주소 추출
            "strings \"$filePath\" | grep -oE '([0-9]{1,3}\\.){3}[0-9]{1,3}(:[0-9]+)?' | sort -u",
            // 도메인 추출 (TLD 패턴)
            "strings \"$filePath\" | grep -oE '[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(:[0-9]+)?' | sort -u | head -100",
            // API 경로 패턴 (/api/, /v1/, /rest/ 등)
            "strings \"$filePath\" | grep -oE '/api/[a-zA-Z0-9/_-]*|/v[0-9]+/[a-zA-Z0-9/_-]*|/rest/[a-zA-Z0-9/_-]*' | sort -u | head -50",
            // WebSocket
            "strings \"$filePath\" | grep -iE 'wss?://|websocket|socket.io' | sort -u",
            // FTP/기타 프로토콜
            "strings \"$filePath\" | grep -oE 'ftp://[^\\s<>\"{}|^`\\[\\]]+' | sort -u",
            // Base64-like strings that decode to URLs (heuristic)
            "strings \"$filePath\" | grep -oE '[A-Za-z0-9+/]{40,}={0,2}' | head -50",
            // Network API 함수/라이브러리 참조
            "strings \"$filePath\" | grep -iE 'curl|wget|fetch|okhttp|retrofit|volley|axios|urllib|requests|httpclient|socket|connect|bind|send|recv' | head -30",
            // 하드코딩된 키와 함께 있는 URL (API key + endpoint)
            "strings \"$filePath\" | grep -iE 'api[_-]?key|token|secret|auth|bearer' | head -30"
        )

        for (cmd in cmds) {
            val res = sandbox.execute(sandboxId, cmd, 30)
            sb.append("### CMD: ${cmd.take(80)}...\n")
            if (res.stdout.isNotBlank()) sb.append(res.stdout).append("\n")
            if (res.stderr.isNotBlank()) sb.append("[stderr: ${res.stderr.take(200)}]\n")
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * 원시 출력에서 엔드포인트 파싱
     */
    private fun parseEndpoints(rawOutput: String): List<Endpoint> {
        val endpoints = mutableListOf<Endpoint>()
        val lines = rawOutput.lines()
        val seen = mutableSetOf<String>()

        // URL 패턴
        val urlRegex = Regex("(https?://[^\\s<>\"{}|^`\\[\\]]+)")
        // IP:Port 패턴
        val ipRegex = Regex("([0-9]{1,3}\\.){3}[0-9]{1,3}(:[0-9]+)?")
        // 도메인 패턴
        val domainRegex = Regex("([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}")
        // API 경로 패턴
        val apiPathRegex = Regex("(/(?:api|v\\d+|rest|graphql|webhook|callback|auth|login|sync)[a-zA-Z0-9/_-]*)")
        // WebSocket 패턴
        val wsRegex = Regex("(wss?://[^\\s<>\"{}|^`\\[\\]]+)")
        // Base64 가능성 (40자 이상의 알파벳+숫자+/+=)
        val b64Regex = Regex("([A-Za-z0-9+/]{40,}={0,2})")

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("### CMD") || trimmed.startsWith("[stderr")) continue
            if (trimmed in seen) continue
            seen.add(trimmed)

            // URL
            urlRegex.findAll(trimmed).forEach { match ->
                val url = match.value
                val parsed = parseUrl(url)
                endpoints.add(Endpoint(
                    rawValue = url, type = "url", protocol = parsed.first,
                    host = parsed.second, port = parsed.third,
                    path = parsed.fourth, isHardcoded = true,
                    encodingType = "plain", decodedValue = null,
                    category = "unknown", riskLevel = "Medium"
                ))
            }

            // WebSocket
            wsRegex.findAll(trimmed).forEach { match ->
                val url = match.value
                endpoints.add(Endpoint(
                    rawValue = url, type = "websocket", protocol = "ws",
                    host = url.removePrefix("ws://").removePrefix("wss://").substringBefore('/'),
                    port = null, path = null, isHardcoded = true,
                    encodingType = "plain", decodedValue = null,
                    category = "unknown", riskLevel = "Medium"
                ))
            }

            // IP:Port
            ipRegex.findAll(trimmed).forEach { match ->
                val ip = match.value
                val parts = ip.split(':')
                val port = parts.getOrNull(1)?.toIntOrNull()
                endpoints.add(Endpoint(
                    rawValue = ip, type = "ip", protocol = null,
                    host = parts[0], port = port, path = null,
                    isHardcoded = true, encodingType = "plain", decodedValue = null,
                    category = "unknown", riskLevel = if (port != null && port !in listOf(80,443,8080,8443)) "High" else "Medium"
                ))
            }

            // 도메인 (URL에 이미 포함된 것 제외)
            domainRegex.findAll(trimmed).forEach { match ->
                val domain = match.value
                if (!trimmed.contains("http") && !trimmed.contains("ws")) {
                    endpoints.add(Endpoint(
                        rawValue = domain, type = "domain", protocol = null,
                        host = domain, port = null, path = null,
                        isHardcoded = true, encodingType = "plain", decodedValue = null,
                        category = "unknown", riskLevel = "Medium"
                    ))
                }
            }

            // API 경로
            apiPathRegex.findAll(trimmed).forEach { match ->
                val path = match.value
                endpoints.add(Endpoint(
                    rawValue = path, type = "api_path", protocol = null,
                    host = null, port = null, path = path,
                    isHardcoded = true, encodingType = "plain", decodedValue = null,
                    category = "api", riskLevel = "Low"
                ))
            }

            // Base64 디코딩 시도
            b64Regex.findAll(trimmed).forEach { match ->
                val b64 = match.value
                val decoded = tryDecodeBase64(b64)
                if (decoded != null && (decoded.contains("http") || decoded.contains(".") || ipRegex.containsMatchIn(decoded))) {
                    endpoints.add(Endpoint(
                        rawValue = b64, type = "url", protocol = null,
                        host = null, port = null, path = null,
                        isHardcoded = true, encodingType = "base64",
                        decodedValue = decoded, category = "unknown",
                        riskLevel = "High" // 난독화된 엔드포인트 = 의심
                    ))
                }
            }
        }

        return endpoints.distinctBy { it.rawValue }
    }

    /**
     * 엔드포인트 카테고리 분류 및 위험도 재평가
     */
    private fun classifyAndScore(ep: Endpoint): Endpoint {
        val host = ep.host ?: ep.rawValue
        var category = "unknown"
        var risk = ep.riskLevel

        // 알려진 도메인 매칭
        for ((domain, cat) in knownDomains) {
            if (host.contains(domain, ignoreCase = true)) {
                category = cat
                break
            }
        }

        // 카테고리별 위험도 조정
        when (category) {
            "c2" -> risk = "Critical"
            "update" -> {
                if (ep.protocol == "http") risk = "High"
                else risk = "Medium"
            }
            "ad" -> risk = "Low"
            "analytics" -> risk = "Low"
            "cdn" -> risk = "Info"
        }

        // 프로토콜 위험도
        if (ep.protocol == "http") {
            risk = when (risk) {
                "Info" -> "Low"
                "Low" -> "Medium"
                "Medium" -> "High"
                "High" -> "Critical"
                else -> risk
            }
        }

        // 난독화된 엔드포인트는 위험도 상향
        if (ep.encodingType != null && ep.encodingType != "plain") {
            risk = when (risk) {
                "Info" -> "Low"
                "Low" -> "Medium"
                "Medium" -> "High"
                "High" -> "Critical"
                else -> risk
            }
        }

        // Private IP는 내부 통신으로 낮은 위험도
        if (isPrivateIp(host)) {
            risk = "Info"
            category = "internal"
        }

        return ep.copy(category = category, riskLevel = risk)
    }

    private fun parseUrl(url: String): Quadruple<String?, String?, Int?, String?> {
        val protocol = when {
            url.startsWith("https://") -> "https"
            url.startsWith("http://") -> "http"
            else -> null
        }
        val withoutProto = url.removePrefix("https://").removePrefix("http://")
        val hostPart = withoutProto.substringBefore('/')
        val path = "/" + withoutProto.substringAfter('/', "")
        val host = hostPart.substringBefore(':')
        val port = hostPart.substringAfter(':', "").toIntOrNull() ?: if (protocol == "https") 443 else if (protocol == "http") 80 else null
        return Quadruple(protocol, host, port, if (path == "/") null else path)
    }

    private fun tryDecodeBase64(s: String): String? {
        return try {
            val clean = s.replace(Regex("[^A-Za-z0-9+/=]"), "")
            if (clean.length % 4 != 0) return null
            val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() && it.all { c -> c.isPrintable() } }
        } catch (e: Exception) {
            null
        }
    }

    private fun Char.isPrintable(): Boolean = this in '\u0020'..'\u007E' || this == '\n' || this == '\t'

    private fun isPrivateIp(host: String?): Boolean {
        if (host == null) return false
        return host.startsWith("10.") ||
               host.startsWith("192.168.") ||
               host.startsWith("172.") && host.split('.').getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true ||
               host == "127.0.0.1" || host == "localhost"
    }

    /**
     * 자연어 보고서 생성 (한국어)
     */
    private fun generateReport(
        filePath: String,
        fileType: String,
        endpoints: List<Endpoint>,
        rawOutput: String
    ): String {
        val sb = StringBuilder()
        sb.append("네트워크 분석 보고서\n")
        sb.append("=" .repeat(40)).append("\n\n")
        sb.append("분석 파일: $filePath\n")
        sb.append("파일 형식: ${fileType.uppercase()}\n")
        sb.append("총 엔드포인트: ${endpoints.size}개\n\n")

        // 위험도 분포
        val riskCounts = endpoints.groupingBy { it.riskLevel }.eachCount()
        sb.append("위험도 분포:\n")
        listOf("Critical", "High", "Medium", "Low", "Info").forEach { level ->
            val count = riskCounts[level] ?: 0
            val icon = when (level) {
                "Critical" -> ""
                "High" -> ""
                "Medium" -> ""
                "Low" -> ""
                else -> ""
            }
            sb.append("  $icon $level: $count\n")
        }
        sb.append("\n")

        // 카테고리 분포
        val catCounts = endpoints.groupingBy { it.category }.eachCount()
        sb.append("서버 역할 분류:\n")
        catCounts.toList().sortedByDescending { it.second }.forEach { (cat, count) ->
            val label = when (cat) {
                "c2" -> "C2 서버 (명령제어)"
                "cdn" -> "CDN"
                "ad" -> "광고/트래킹"
                "analytics" -> "분석/통계"
                "update" -> "업데이트"
                "api" -> "API 서버"
                "internal" -> "내부 네트워크"
                else -> "미분류"
            }
            sb.append("  $label: $count\n")
        }
        sb.append("\n")

        // 하드코딩된 엔드포인트 상세
        val hardcoded = endpoints.filter { it.isHardcoded }
        if (hardcoded.isNotEmpty()) {
            sb.append("하드코딩된 엔드포인트 (${hardcoded.size}개):\n")
            sb.append("-".repeat(40)).append("\n")
            hardcoded.sortedByDescending { it.riskLevel }.take(30).forEach { ep ->
                sb.append("[${ep.riskLevel}] ")
                if (ep.encodingType != "plain") sb.append("(${ep.encodingType} 난독화) ")
                sb.append("${ep.rawValue}")
                if (ep.decodedValue != null) sb.append(" -> 디코딩: ${ep.decodedValue}")
                sb.append("\n")
                if (ep.host != null) sb.append("    호스트: ${ep.host}${ep.port?.let { ":$it" } ?: ""}\n")
                if (ep.path != null) sb.append("    경로: ${ep.path}\n")
                sb.append("    분류: ${ep.category}\n\n")
            }
        }

        // 동적/난독화된 엔드포인트
        val obfuscated = endpoints.filter { it.encodingType != "plain" }
        if (obfuscated.isNotEmpty()) {
            sb.append("\n난독화된 엔드포인트 (${obfuscated.size}개):\n")
            sb.append("이러한 엔드포인트는 의도적으로 숨겨져 있어 악성코드 가능성이 높습니다.\n\n")
        }

        // 보안 권장사항
        sb.append("\n보안 권장사항:\n")
        sb.append("-".repeat(40)).append("\n")
        if (endpoints.any { it.protocol == "http" }) {
            sb.append("- HTTP(암호화되지 않은) 통신이 감지되었습니다. 중간자 공격에 취약합니다.\n")
        }
        if (endpoints.any { it.category == "c2" }) {
            sb.append("- C2 서버와의 통신이 감지되었습니다. 악성코드일 가능성이 매우 높습니다.\n")
        }
        if (endpoints.any { it.encodingType != "plain" }) {
            sb.append("- 난독화된 네트워크 주소가 발견되었습니다. 난독화 해제 분석을 권장합니다.\n")
        }
        if (hardcoded.isNotEmpty()) {
            sb.append("- 하드코딩된 서버 주소 ${hardcoded.size}개가 발견되었습니다. 서버 변경 시 재배포가 필요합니다.\n")
        }
        sb.append("- 모든 외부 통신은 HTTPS를 사용하고, Certificate Pinning을 적용하세요.\n")
        sb.append("- API 키/토큰은 code가 아닌 안전한 저장소(KeyStore, Keystore)에 보관하세요.\n")

        return sb.toString()
    }

    /**
     * Quadruple 데이터 클래스 (4개 값 반환용)
     */
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /**
     * 단일 문자열에서 엔드포인트 빠른 검사
     */
    fun quickScan(text: String): List<String> {
        val urls = Regex("(https?://[^\\s<>\"{}|^`\\[\\]]+)").findAll(text).map { it.value }.toList()
        val ips = Regex("([0-9]{1,3}\\.){3}[0-9]{1,3}").findAll(text).map { it.value }.toList()
        return (urls + ips).distinct()
    }
}
