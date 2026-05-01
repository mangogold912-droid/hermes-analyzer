package com.hermes.analyzer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hermes.analyzer.browser.BrowserEngine

/**
 * EnhancedBrowserActivity
 * 향상된 내장 브라우저 (AI HTML 파싱, 링크/표/코드 추출)
 */
class EnhancedBrowserActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var tvAnalysis: TextView
    private lateinit var etUrl: EditText
    private val engine = BrowserEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        title = "AI Browser"

        // URL bar
        val urlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etUrl = EditText(this).apply {
            hint = "https://..."
            setText("https://github.com/mangogold912-droid/hermes-analyzer")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnGo = Button(this).apply {
            text = "Go"
            setOnClickListener { loadUrl(etUrl.text.toString()) }
        }
        urlRow.addView(etUrl)
        urlRow.addView(btnGo)
        root.addView(urlRow)

        // Action buttons
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnParse = Button(this).apply {
            text = "AI Parse"
            setOnClickListener { aiParsePage() }
        }
        val btnLinks = Button(this).apply {
            text = "Links"
            setOnClickListener { extractLinks() }
        }
        val btnTables = Button(this).apply {
            text = "Tables"
            setOnClickListener { extractTables() }
        }
        val btnCode = Button(this).apply {
            text = "Code"
            setOnClickListener { extractCode() }
        }
        btnRow.addView(btnParse)
        btnRow.addView(btnLinks)
        btnRow.addView(btnTables)
        btnRow.addView(btnCode)
        root.addView(btnRow)

        // WebView
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 600)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        root.addView(webView)

        // Analysis display
        tvAnalysis = TextView(this).apply {
            text = "AI analysis results will appear here..."
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(16, 16, 16, 16)
        }
        val scroll = ScrollView(this).apply {
            addView(tvAnalysis)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400)
        }
        root.addView(scroll)

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        })

        loadUrl(etUrl.text.toString())
    }

    private fun loadUrl(url: String) {
        val u = if (url.startsWith("http")) url else "https://$url"
        webView.loadUrl(u)
    }

    private fun aiParsePage() {
        webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
            val clean = html?.replace("\\u003C", "<")?.replace("\\\"", "\"")?.replace("\\n", "\n") ?: ""
            val parsed = engine.parseHTML(clean, webView.url ?: "")
            tvAnalysis.text = engine.formatAnalysis(parsed)
        }
    }

    private fun extractLinks() {
        webView.evaluateJavascript("""
            (function() {
                var links = [];
                document.querySelectorAll('a[href]').forEach(function(a) {
                    links.push({text: a.innerText.trim().substring(0,50), href: a.href});
                });
                return JSON.stringify(links.slice(0,30));
            })()
        """) { result ->
            tvAnalysis.text = "## Links Found\n\n${result?.take(3000) ?: "None"}"
        }
    }

    private fun extractTables() {
        webView.evaluateJavascript("""
            (function() {
                var tables = [];
                document.querySelectorAll('table').forEach(function(t) {
                    var rows = [];
                    t.querySelectorAll('tr').forEach(function(r) {
                        var cells = [];
                        r.querySelectorAll('td, th').forEach(function(c) {
                            cells.push(c.innerText.trim());
                        });
                        rows.push(cells);
                    });
                    tables.push(rows);
                });
                return JSON.stringify(tables.slice(0,5));
            })()
        """) { result ->
            tvAnalysis.text = "## Tables Found\n\n${result?.take(3000) ?: "None"}"
        }
    }

    private fun extractCode() {
        webView.evaluateJavascript("""
            (function() {
                var blocks = [];
                document.querySelectorAll('pre, code').forEach(function(c) {
                    blocks.push(c.innerText.substring(0,200));
                });
                return JSON.stringify(blocks.slice(0,20));
            })()
        """) { result ->
            tvAnalysis.text = "## Code Blocks Found\n\n${result?.take(3000) ?: "None"}"
        }
    }
}
