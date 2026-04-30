package com.hermes.analyzer.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object TermuxInstaller {

    private const val TERMUX_PKG = "com.termux"
    private const val TERMUX_API_PKG = "com.termux.api"
    private const val TERMUX_FDROID_URL = "https://f-droid.org/repo/com.termux_118.apk"
    private const val TERMUX_API_FDROID_URL = "https://f-droid.org/repo/com.termux.api_51.apk"

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PKG, 0)
            true
        } catch (_: Exception) { false }
    }

    fun isTermuxApiInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_API_PKG, 0)
            true
        } catch (_: Exception) { false }
    }

    fun installTermux(context: Context) {
        Toast.makeText(context, "Termux 설치 시작...", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val apkFile = downloadApk(context, TERMUX_FDROID_URL, "termux.apk")
                if (apkFile != null) {
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Termux 다운로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    fun setupIdaProInTermux(context: Context) {
        val script = """
            #!/data/data/com.termux/files/usr/bin/bash
            echo "=== IDA Pro Mobile + MCP Server 설치 ==="
            pkg update -y
            pkg install -y wget curl python python-pip openssh git

            # proot-distro 설치 (Debian chroot)
            pkg install -y proot-distro
            proot-distro install debian

            # Debian 안에 IDA Pro Linux 설치
            echo "Debian 환경 설정 중..."
            proot-distro login debian -- bash -c '
                apt update
                apt install -y wget curl python3 python3-pip ssh

                # IDA Pro MCP 서버 설치
                pip3 install ida-pro-mcp

                # MCP 서버 시작 스크립트
                cat > /start_mcp.sh << EOF
#!/bin/bash
echo "Starting IDA Pro MCP Server..."
ida-mcp-server --host 0.0.0.0 --port 8080
EOF
                chmod +x /start_mcp.sh

                echo "IDA Pro MCP Server 준비 완료!"
                echo "시작: /start_mcp.sh"
            '

            echo "=== 설치 완료 ==="
        """.trimIndent()

        val scriptFile = File(context.filesDir, "setup_ida.sh")
        scriptFile.writeText(script)

        // Termux에 전달
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setPackage("com.termux")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(scriptFile.absolutePath))
        }
        context.startActivity(intent)
    }

    private fun downloadApk(context: Context, url: String, filename: String): File? {
        return try {
            val file = File(context.cacheDir, filename)
            URL(url).openStream().use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file
        } catch (_: Exception) { null }
    }

    private fun installApk(context: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
                return
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
