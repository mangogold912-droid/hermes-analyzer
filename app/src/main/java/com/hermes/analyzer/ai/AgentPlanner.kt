package com.hermes.analyzer.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Autonomous AI Agent Planner
 * 
 * 사용자의 자연어 명령을 분석하여, 목표 달성을 위한
 * 병렬/순차 도구 실행 계횑을 생성합니다.
 * 
 * 예시 입력: "이 APK의 보안 취약점을 찾고 네트워크 통신을 분석하며
 *          암호화 로직을 추출하고, JNI 네이티브 함수를 확인해줘"
 * 
 * 예시 출력: 4단계 병렬 실행 계획
 */
class AgentPlanner {

    /**
     * 실행 계횑의 각 단계
     */
    data class PlanStep(
        val step: Int,
        val description: String,
        val toolIds: List<String>,
        val parallel: Boolean = true,
        val dependsOn: List<Int> = emptyList(),
        val rationale: String = ""
    )

    /**
     * 분석 목표 카테고리
     */
    enum class GoalType {
        SECURITY_AUDIT,      // 보안 감사
        REVERSE_ENGINEERING, // 리버스 엔지니어링
        MALWARE_ANALYSIS,    // 악성코드 분석
        NETWORK_ANALYSIS,    // 네트워크 분석
        CRYPTO_ANALYSIS,     // 암호화 분석
        COMPREHENSIVE,       // 종합 분석
        UNKNOWN
    }

    /**
     * 목표 분석 → 실행 계횑 생성 (메인 진입점)
     */
    fun createPlan(command: String, filePath: String, fileType: String): List<PlanStep> {
        val goal = analyzeGoal(command, fileType)
        val tools = selectTools(goal, command, fileType)
        return buildExecutionPlan(tools, goal, command, filePath, fileType)
    }

    /**
     * 1. 목표 분석 - 사용자 명령에서意圖 파악
     */
    private fun analyzeGoal(command: String, fileType: String): GoalType {
        val lowerCmd = command.lowercase()
        
        // 종합 분석 키워드
        if (hasAny(lowerCmd, listOf("전체", "종합", "모두", "모든", "comprehensive", "all", "full", "전부")))
            return GoalType.COMPREHENSIVE
        
        // 보안 감사
        if (hasAny(lowerCmd, listOf("보안", "취약점", "vuln", "exploit", "security", "penetration", "감사")))
            return GoalType.SECURITY_AUDIT
        
        // 악성코드 분석
        if (hasAny(lowerCmd, listOf("악성", "malware", "virus", "백도어", "backdoor", "트로이", "troyan")))
            return GoalType.MALWARE_ANALYSIS
        
        // 네트워크 분석
        if (hasAny(lowerCmd, listOf("네트워크", "통신", "패킷", "pcap", "network", "traffic", "packet", "url", "서버")))
            return GoalType.NETWORK_ANALYSIS
        
        // 암호화 분석
        if (hasAny(lowerCmd, listOf("암호", "암호화", "crypto", "aes", "rsa", "encryption", "cipher", "key", "인증서", "cert")))
            return GoalType.CRYPTO_ANALYSIS
        
        // 리버스 엔지니어링 (기본)
        return GoalType.REVERSE_ENGINEERING
    }

    /**
     * 2. 도구 선택 - 목표에 맞는 도구들을 지능적으로 선택
     */
    private fun selectTools(goal: GoalType, command: String, fileType: String): List<String> {
        val lowerCmd = command.lowercase()
        val selected = mutableListOf<String>()
        
        // === 파일 타입별 기본 도구 ===
        when (fileType.lowercase()) {
            "apk" -> {
                selected.add("apk_deep_scan")
                selected.add("dex_decompiler")
            }
            "elf", "so", "o" -> {
                selected.add("elf_analyzer")
                selected.add("capstone_disasm")
            }
            "dex" -> {
                selected.add("dex_decompiler")
            }
        }
        
        // === 목표별 도구 매핑 ===
        when (goal) {
            GoalType.SECURITY_AUDIT -> {
                selected.addAll(listOf(
                    "vuln_scanner", "crypto_hunter", "network_analyzer",
                    "yara_scanner", "capa_floss", "quark_engine"
                ))
                if (fileType == "apk") selected.add("mobsf_scanner")
            }
            
            GoalType.MALWARE_ANALYSIS -> {
                selected.addAll(listOf(
                    "yara_scanner", "crypto_hunter", "string_extractor",
                    "network_analyzer", "quark_engine", "capa_floss"
                ))
            }
            
            GoalType.NETWORK_ANALYSIS -> {
                selected.addAll(listOf(
                    "network_analyzer", "wireshark_analyzer",
                    "burp_zap_proxy", "objection_tool"
                ))
            }
            
            GoalType.CRYPTO_ANALYSIS -> {
                selected.addAll(listOf(
                    "crypto_hunter", "string_extractor", "capa_floss",
                    "detect_it_easy"
                ))
            }
            
            GoalType.REVERSE_ENGINEERING -> {
                selected.addAll(listOf(
                    "capstone_disasm", "string_extractor", "ida_mcp_bridge",
                    "radare2_wrapper"
                ))
            }
            
            GoalType.COMPREHENSIVE -> {
                // 모든 주요 도구 포함
                selected.addAll(listOf(
                    "vuln_scanner", "crypto_hunter", "network_analyzer",
                    "string_extractor", "yara_scanner", "jni_analyzer",
                    "capa_floss", "quark_engine", "capstone_disasm"
                ))
                if (fileType == "apk") {
                    selected.addAll(listOf("mobsf_scanner", "objection_tool", "jadx_decompiler"))
                }
            }
            
            GoalType.UNKNOWN -> {
                selected.addAll(listOf("string_extractor", "yara_scanner"))
            }
        }
        
        // === 명령어 기반 추가 도우 (키워드 트리거) ===
        if (hasAny(lowerCmd, listOf("jni", "native", "so", "네이티브")))
            if (!selected.contains("jni_analyzer")) selected.add("jni_analyzer")
            
        if (hasAny(lowerCmd, listOf("frida", "hook", "후킹", "인젝션")))
            if (!selected.contains("frida_generator")) selected.add("frida_generator")
            
        if (hasAny(lowerCmd, listOf("ghidra", "ghidra script", "fox")))
            if (!selected.contains("ghidra_fox")) selected.add("ghidra_fox")
            
        if (hasAny(lowerCmd, listOf("objc", "objective-c", "ios")))
            if (!selected.contains("ida_objc_types")) selected.add("ida_objc_types")
            
        if (hasAny(lowerCmd, listOf("react", "react native", "rn")))
            if (!selected.contains("heresy_react_native")) selected.add("heresy_react_native")
            
        if (hasAny(lowerCmd, listOf("flutter", "dart")))
            if (!selected.contains("reflutter_ssl")) selected.add("reflutter_ssl")
            
        if (hasAny(lowerCmd, listOf("ebpft", "ebpf", "bpf")))
            if (!selected.contains("bpfroid_trace")) selected.add("bpfroid_trace")
            
        if (hasAny(lowerCmd, listOf("coruna", "exploit kit", "jailbreak")))
            if (!selected.contains("coruna_ios")) selected.add("coruna_ios")
        
        // === 중복 제거 ===
        return selected.distinct()
    }

    /**
     * 3. 실행 계횑 구성 - 병렬/순차 그룹핑
     */
    private fun buildExecutionPlan(
        tools: List<String>,
        goal: GoalType,
        command: String,
        filePath: String,
        fileType: String
    ): List<PlanStep> {
        val plan = mutableListOf<PlanStep>()
        var stepNum = 1
        
        // === Step 1: 파일 타입별 기본 분석 (병렬) ===
        val baseTools = tools.filter { it in listOf("apk_deep_scan", "elf_analyzer", "dex_decompiler") }
        if (baseTools.isNotEmpty()) {
            plan.add(PlanStep(
                step = stepNum++,
                description = "File structure analysis",
                toolIds = baseTools,
                parallel = true,
                rationale = "Understand the basic file structure first"
            ))
        }
        
        // === Step 2: 보안 스캔 (병렬) ===
        val securityTools = tools.filter { it in listOf(
            "vuln_scanner", "yara_scanner", "quark_engine", "mobsf_scanner", "capa_floss"
        )}
        if (securityTools.isNotEmpty()) {
            plan.add(PlanStep(
                step = stepNum++,
                description = "Security vulnerability scan",
                toolIds = securityTools,
                parallel = true,
                dependsOn = if (baseTools.isNotEmpty()) listOf(1) else emptyList(),
                rationale = "Scan for known vulnerabilities and malware patterns"
            ))
        }
        
        // === Step 3: 암호화/문자열 분석 (병렬) ===
        val cryptoTools = tools.filter { it in listOf(
            "crypto_hunter", "string_extractor", "capa_floss", "detect_it_easy"
        )}
        if (cryptoTools.isNotEmpty()) {
            plan.add(PlanStep(
                step = stepNum++,
                description = "Cryptographic and string analysis",
                toolIds = cryptoTools,
                parallel = true,
                dependsOn = listOf(1),
                rationale = "Find encryption logic and sensitive strings"
            ))
        }
        
        // === Step 4: 네트워크 분석 ===
        val networkTools = tools.filter { it in listOf(
            "network_analyzer", "wireshark_analyzer", "burp_zap_proxy", "objection_tool"
        )}
        if (networkTools.isNotEmpty()) {
            plan.add(PlanStep(
                step = stepNum++,
                description = "Network communication analysis",
                toolIds = networkTools,
                parallel = true,
                dependsOn = listOf(1),
                rationale = "Analyze network traffic and API endpoints"
            ))
        }
        
        // === Step 5: 고급 분석 (병렬) ===
        val advancedTools = tools.filter { it in listOf(
            "jni_analyzer", "frida_generator", "capstone_disasm", "ida_mcp_bridge",
            "radare2_wrapper", "ghidra_fox", "ida_objc_types", "heresy_react_native",
            "reflutter_ssl", "bpfroid_trace", "edbg_ebpf", "coruna_ios",
            "jadx_decompiler", "dnspy_decompiler", "binary_ninja"
        )}
        if (advancedTools.isNotEmpty()) {
            plan.add(PlanStep(
                step = stepNum++,
                description = "Advanced reverse engineering analysis",
                toolIds = advancedTools,
                parallel = true,
                dependsOn = listOf(1),
                rationale = "Deep analysis with specialized tools"
            ))
        }
        
        // Note: Final AI synthesis is handled by AdvancedAIEngine.chatWithParallelAI()
        // after plugin results are collected. No separate plugin step needed.
        return plan
    }

    /**
     * 실행 계횑을 JSON 문자열로 변환
     */
    fun planToJson(plan: List<PlanStep>): String {
        val json = JSONArray()
        for (step in plan) {
            val obj = JSONObject()
            obj.put("step", step.step)
            obj.put("description", step.description)
            obj.put("tools", JSONArray(step.toolIds))
            obj.put("parallel", step.parallel)
            obj.put("depends_on", JSONArray(step.dependsOn))
            obj.put("rationale", step.rationale)
            json.put(obj)
        }
        return json.toString(2)
    }

    /**
     * 계횑을 사람이 읽기 쉬운 텍스트로 변환
     */
    fun planToMarkdown(plan: List<PlanStep>): String {
        val sb = StringBuilder()
        sb.appendLine("## Analysis Execution Plan")
        sb.appendLine("Total steps: ${plan.size}")
        sb.appendLine()
        
        for (step in plan) {
            val icon = if (step.parallel) "||" else "->"
            sb.appendLine("### Step ${step.step} $icon ${step.description}")
            sb.appendLine("- **Tools**: ${step.toolIds.joinToString(", ")}")
            if (step.dependsOn.isNotEmpty()) {
                sb.appendLine("- **Depends on**: Step ${step.dependsOn.joinToString(", ")}")
            }
            sb.appendLine("- **Why**: ${step.rationale}")
            sb.appendLine()
        }
        
        return sb.toString()
    }

    // === 헬퍼 메서드들 ===
    
    private fun hasAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it.lowercase()) }
    }
}

