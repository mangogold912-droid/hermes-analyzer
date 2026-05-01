package com.hermes.analyzer.sandbox

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sandbox Manager
 * 완전한 가상 실행 환경 관리 (Python/Node.js/Bash, 스냅샷, 롤백, 다중 샌드박스)
 */
class SandboxManager(private val context: Context) {
    private val TAG = "SandboxManager"
    private val baseDir = File(context.getExternalFilesDir(null), "sandbox")
    private val sandboxes = mutableMapOf<String, Sandbox>()

    data class Sandbox(
        val id: String,
        val name: String,
        val rootDir: File,
        val type: String, // python, node, bash, java, kotlin
        val createdAt: Long,
        var lastUsed: Long,
        var status: String // active, paused, destroyed
    )

    data class ExecutionResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long
    )

    data class Snapshot(
        val id: String,
        val sandboxId: String,
        val timestamp: Long,
        val files: List<String>
    )

    init {
        baseDir.mkdirs()
        listOf("sandbox-main", "sandbox-test", "sandbox-web", "sandbox-binary", "sandbox-plugin").forEach { name ->
            createSandbox(name, "bash")
        }
    }

    fun createSandbox(name: String, type: String): Sandbox {
        val id = "${name}_${System.currentTimeMillis()}"
        val root = File(baseDir, name)
        root.mkdirs()
        File(root, "workspace").mkdirs()
        File(root, "logs").mkdirs()
        
        val sandbox = Sandbox(id, name, root, type, System.currentTimeMillis(), System.currentTimeMillis(), "active")
        sandboxes[id] = sandbox
        Log.i(TAG, "Created sandbox $name (type=$type) at ${root.absolutePath}")
        return sandbox
    }

    fun getSandbox(id: String): Sandbox? = sandboxes[id]
    fun listSandboxes(): List<Sandbox> = sandboxes.values.toList()

    fun execute(sandboxId: String, command: String, timeoutSec: Int = 30): ExecutionResult {
        val sandbox = sandboxes[sandboxId] ?: return ExecutionResult(false, "", "Sandbox not found: $sandboxId", -1, 0)
        val start = System.currentTimeMillis()
        
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", command), null, sandbox.rootDir)
            val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            
            val stdout = if (finished) process.inputStream.bufferedReader().use { it.readText() } else ""
            val stderr = if (finished) process.errorStream.bufferedReader().use { it.readText() } else "Timeout after ${timeoutSec}s"
            val exitCode = if (finished) process.exitValue() else -1
            
            sandbox.lastUsed = System.currentTimeMillis()
            ExecutionResult(finished && exitCode == 0, stdout, stderr, exitCode, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            ExecutionResult(false, "", "Execution error: ${e.message}", -1, System.currentTimeMillis() - start)
        }
    }

    fun executePython(sandboxId: String, script: String, timeoutSec: Int = 30): ExecutionResult {
        val sandbox = sandboxes[sandboxId] ?: return ExecutionResult(false, "", "Sandbox not found", -1, 0)
        val scriptFile = File(sandbox.rootDir, "workspace/script.py")
        scriptFile.writeText(script)
        return execute(sandboxId, "python3 ${scriptFile.absolutePath}", timeoutSec)
    }

    fun executeNode(sandboxId: String, script: String, timeoutSec: Int = 30): ExecutionResult {
        val sandbox = sandboxes[sandboxId] ?: return ExecutionResult(false, "", "Sandbox not found", -1, 0)
        val scriptFile = File(sandbox.rootDir, "workspace/script.js")
        scriptFile.writeText(script)
        return execute(sandboxId, "node ${scriptFile.absolutePath}", timeoutSec)
    }

    fun executeBash(sandboxId: String, script: String, timeoutSec: Int = 30): ExecutionResult {
        val sandbox = sandboxes[sandboxId] ?: return ExecutionResult(false, "", "Sandbox not found", -1, 0)
        val scriptFile = File(sandbox.rootDir, "workspace/script.sh")
        scriptFile.writeText("#!/system/bin/sh\n$script")
        return execute(sandboxId, "sh ${scriptFile.absolutePath}", timeoutSec)
    }

    fun createSnapshot(sandboxId: String): Snapshot? {
        val sandbox = sandboxes[sandboxId] ?: return null
        val snapshotId = "snap_${System.currentTimeMillis()}"
        val snapshotDir = File(baseDir, "snapshots/$snapshotId")
        snapshotDir.mkdirs()
        
        sandbox.rootDir.copyRecursively(snapshotDir, overwrite = true)
        val files = snapshotDir.walkTopDown().map { it.relativeTo(snapshotDir).path }.toList()
        
        return Snapshot(snapshotId, sandboxId, System.currentTimeMillis(), files)
    }

    fun rollback(sandboxId: String, snapshotId: String): Boolean {
        val sandbox = sandboxes[sandboxId] ?: return false
        val snapshotDir = File(baseDir, "snapshots/$snapshotId")
        if (!snapshotDir.exists()) return false
        
        sandbox.rootDir.deleteRecursively()
        snapshotDir.copyRecursively(sandbox.rootDir, overwrite = true)
        Log.i(TAG, "Rolled back sandbox $sandboxId to snapshot $snapshotId")
        return true
    }

    fun destroySandbox(sandboxId: String): Boolean {
        val sandbox = sandboxes[sandboxId] ?: return false
        sandbox.rootDir.deleteRecursively()
        sandboxes.remove(sandboxId)
        Log.i(TAG, "Destroyed sandbox $sandboxId")
        return true
    }

    fun installPackage(sandboxId: String, packageName: String, packageType: String): ExecutionResult {
        val cmd = when (packageType) {
            "python" -> "pip3 install $packageName"
            "node" -> "npm install $packageName"
            "go" -> "go get $packageName"
            else -> "pkg install $packageName"
        }
        return execute(sandboxId, cmd, timeoutSec = 120)
    }
}
