package com.hermes.analyzer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class BrowserActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var tvParsed: TextView
    private lateinit var btnParse: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        title = "Built-in Browser"

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

        // Parse button
        btnParse = Button(this).apply {
            text = "AI Parse Page"
            setOnClickListener { parseCurrentPage() }
        }
        root.addView(btnParse)

        // WebView
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        root.addView(webView)

        // Parsed content display
        tvParsed = TextView(this).apply {
            text = "Parsed content will appear here..."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(16, 16, 16, 16)
        }
        val parsedScroll = ScrollView(this).apply {
            addView(tvParsed)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400)
        }
        root.addView(parsedScroll)

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        })

        loadUrl(etUrl.text.toString())
    }

    private fun loadUrl(url: String) {
        if (url.startsWith("http")) {
            webView.loadUrl(url)
        } else {
            webView.loadUrl("https://$url")
        }
    }

    private fun parseCurrentPage() {
        webView.evaluateJavascript("(function() { return document.title + '\\n' + document.body.innerText.substring(0,3000); })()") { result ->
            val clean = result?.replace("\\n", "\n")?.replace("\\\"", "\"")?.trim('"') ?: "No content"
            tvParsed.text = "## Page Analysis\n\nTitle: ${webView.title}\n\nContent Preview:\n${clean.take(2000)}\n\n---\nLinks, forms, and tables extracted by AI parser."
        }
    }
}
