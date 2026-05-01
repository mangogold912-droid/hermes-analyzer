package com.hermes.analyzer

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * TerminalActivity - Built-in Terminal using WebView + xterm.js
 *
 * Runs /system/bin/sh directly via Runtime.exec
 * No external Termux app required
 */
@SuppressLint("SetJavaScriptEnabled")
class TerminalActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var shellProcess: Process? = null
    private var outputWriter: OutputStreamWriter? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prompt = "\u001B[32mhermes\u001B[0m:\u001B[34m~\u001B[0m$ "

    companion object {
        const val EXTRA_COMMANDS = "commands"
        const val EXTRA_TITLE = "title"
    }

    @SuppressLint("AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        webView = findViewById(R.id.terminalWebView)
        setupWebView()

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Terminal"
        supportActionBar?.title = title

        startShell()

        // Auto-execute commands if provided
        val commands = intent.getStringArrayListExtra(EXTRA_COMMANDS)
        if (commands != null && commands.isNotEmpty()) {
            handler.postDelayed({
                executeCommandsSequentially(commands)
            }, 1000)
        }
    }

    @SuppressLint("JavascriptInterface")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()

        webView.addJavascriptInterface(TerminalInterface(), "terminal")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                appendTerminalText("Hermes Built-in Terminal\nType commands below...\n\n$prompt", false)
            }
        }

        // Load minimal terminal HTML
        val terminalHtml = buildTerminalHtml()
        webView.loadDataWithBaseURL(null, terminalHtml, "text/html", "UTF-8", null)
    }

    private fun startShell() {
        try {
            shellProcess = Runtime.getRuntime().exec("/system/bin/sh")
            outputWriter = OutputStreamWriter(shellProcess!!.outputStream)

            // Read stdout
            Thread {
                val reader = BufferedReader(InputStreamReader(shellProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: ""
                    handler.post { appendTerminalText(text + "\n", false) }
                }
            }.start()

            // Read stderr
            Thread {
                val reader = BufferedReader(InputStreamReader(shellProcess!!.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: ""
                    handler.post { appendTerminalText(text + "\n", true) }
                }
            }.start()

            // Wait for process exit
            Thread {
                shellProcess?.waitFor()
                handler.post {
                    appendTerminalText("\n[Process exited]\n", true)
                }
            }.start()

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start shell: ${e.message}", Toast.LENGTH_LONG).show()
            appendTerminalText("Error: ${e.message}\n", true)
        }
    }

    private fun executeCommandsSequentially(commands: ArrayList<String>) {
        Thread {
            for (cmd in commands) {
                handler.post { appendTerminalText("$ $cmd\n", false) }
                try {
                    outputWriter?.write(cmd + "\n")
                    outputWriter?.flush()
                } catch (e: Exception) {
                    handler.post { appendTerminalText("Error: ${e.message}\n", true) }
                }
                Thread.sleep(500)
            }
        }.start()
    }

    private fun appendTerminalText(text: String, isError: Boolean) {
        val color = if (isError) "#ff5555" else "#e2e8f0"
        val escaped = text.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "<br>")
            .replace("\r", "")

        val js = "appendOutput('$escaped', '$color');"
        webView.evaluateJavascript(js, null)
    }

    private fun buildTerminalHtml(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
        body { background: #0f172a; color: #e2e8f0; font-family: monospace; font-size: 13px; margin: 0; padding: 8px; }
        #output { white-space: pre-wrap; word-break: break-all; min-height: 80vh; }
        .error { color: #ff5555; }
        #inputLine { display: flex; align-items: center; margin-top: 4px; }
        #prompt { color: #22c55e; margin-right: 4px; }
        #cmdInput { background: transparent; border: none; color: #e2e8f0; font-family: monospace; font-size: 13px; flex: 1; outline: none; }
        </style>
        </head>
        <body>
        <div id="output"></div>
        <div id="inputLine">
        <span id="prompt">hermes:~$ </span>
        <input type="text" id="cmdInput" autofocus autocomplete="off" />
        </div>
        <script>
        var output = document.getElementById('output');
        var input = document.getElementById('cmdInput');
        
        function appendOutput(text, color) {
            var div = document.createElement('div');
            div.style.color = color || '#e2e8f0';
            div.innerHTML = text;
            output.appendChild(div);
            window.scrollTo(0, document.body.scrollHeight);
        }
        
        input.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                var cmd = input.value;
                appendOutput('hermes:~$ ' + cmd + '\n', '#22c55e');
                terminal.sendCommand(cmd);
                input.value = '';
            }
        });
        
        document.body.addEventListener('click', function() {
            input.focus();
        });
        </script>
        </body>
        </html>
        """.trimIndent()
    }

    private inner class TerminalInterface {
        @JavascriptInterface
        fun sendCommand(command: String) {
            try {
                outputWriter?.write(command + "\n")
                outputWriter?.flush()
            } catch (e: Exception) {
                handler.post {
                    appendTerminalText("Error: ${e.message}\n", true)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            outputWriter?.write("exit\n")
            outputWriter?.flush()
            outputWriter?.close()
            shellProcess?.destroy()
        } catch (_: Exception) {}
    }
}
