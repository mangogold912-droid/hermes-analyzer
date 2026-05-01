package com.hermes.analyzer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val isThinking: Boolean = false
)

class ChatGPTActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var attachButton: ImageButton
    private lateinit var typingIndicator: ProgressBar
    private lateinit var fileChip: TextView
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private val aiEngine = UnifiedAIEngine(this)
    private var currentFilePath: String? = null
    private val chatHistory = ChatHistoryManager.getInstance(this)
    private var currentSessionId: String? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onFileSelected(it.toString()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val margin16 = (16 * density).toInt()
        val margin8 = (8 * density).toInt()
        val padding16 = (16 * density).toInt()
        val padding12 = (12 * density).toInt()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))
        }

        val toolbar = Toolbar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            title = "Hermes AI"
            setTitleTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#202123"))
        }
        rootLayout.addView(toolbar)

        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(this@ChatGPTActivity)
            setPadding(0, margin8, 0, margin8)
            clipToPadding = false
        }
        rootLayout.addView(recyclerView)

        typingIndicator = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(margin16, margin8, margin16, margin8)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#10A37F"))
        }
        rootLayout.addView(typingIndicator)

        fileChip = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(margin16, 0, margin16, margin8)
            }
            visibility = View.GONE
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#10A37F"))
            setPadding(padding16, padding12, padding16, padding12)
            background = GradientDrawable().apply {
                cornerRadius = 32f * density
                setColor(android.graphics.Color.parseColor("#E6F9F3"))
            }
        }
        rootLayout.addView(fileChip)

        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(margin16, 0, margin16, margin16)
            }
            setPadding(padding16, padding12, padding16, padding12)
            background = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(android.graphics.Color.parseColor("#F7F7F8"))
            }
        }

        attachButton = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            setImageResource(android.R.drawable.ic_menu_upload)
            contentDescription = "파일 첨부"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setColorFilter(android.graphics.Color.parseColor("#6B7280"))
        }
        inputContainer.addView(attachButton)

        inputEditText = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            hint = "메시지를 입력하세요"
            setHintTextColor(android.graphics.Color.parseColor("#9CA3AF"))
            setTextColor(android.graphics.Color.parseColor("#374151"))
            background = null
            setPadding(padding16, 0, padding16, 0)
            textSize = 15f
        }
        inputContainer.addView(inputEditText)

        sendButton = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            setImageResource(android.R.drawable.ic_menu_send)
            contentDescription = "보내기"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setColorFilter(android.graphics.Color.parseColor("#10A37F"))
        }
        inputContainer.addView(sendButton)

        rootLayout.addView(inputContainer)
        setContentView(rootLayout)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        adapter = ChatAdapter()
        recyclerView.adapter = adapter

        currentSessionId = chatHistory.createSession()

        sendButton.setOnClickListener { sendMessage() }
        attachButton.setOnClickListener { pickFile() }
    }

    private fun sendMessage() {
        val text = inputEditText.text.toString().trim()
        if (text.isEmpty() && currentFilePath == null) {
            return
        }

        val fullMessage = if (currentFilePath != null) {
            val path = currentFilePath!!
            currentFilePath = null
            fileChip.visibility = View.GONE
            val fileName = path.substringAfterLast("/")
            if (text.isNotEmpty()) {
                "[$fileName] $text"
            } else {
                "첨부 파일을 분석해주세요: $fileName"
            }
        } else {
            text
        }

        inputEditText.text.clear()
        addMessage("user", fullMessage)
        receiveAIResponse(fullMessage)
    }

    private fun receiveAIResponse(userMessage: String) {
        lifecycleScope.launch {
            showTyping(true)
            addMessage("ai", "...", isThinking = true)

            val response = withContext(Dispatchers.IO) {
                aiEngine.process(userMessage)
            }

            val thinkingIndex = messages.indexOfLast { it.isThinking }
            if (thinkingIndex != -1) {
                messages.removeAt(thinkingIndex)
                adapter.notifyItemRemoved(thinkingIndex)
            }

            addMessage("ai", response)
            showTyping(false)
        }
    }

    private fun addMessage(role: String, content: String, isThinking: Boolean = false) {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = role,
            content = content,
            timestamp = System.currentTimeMillis(),
            isThinking = isThinking
        )
        messages.add(message)
        currentSessionId?.let { chatHistory.saveMessage(it, message) }
        adapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
    }

    private fun pickFile() {
        filePickerLauncher.launch("*/*")
    }

    private fun onFileSelected(path: String) {
        currentFilePath = path
        val fileName = path.substringAfterLast("/")
        fileChip.text = "첨부: $fileName"
        fileChip.visibility = View.VISIBLE
    }

    private fun showTyping(show: Boolean) {
        typingIndicator.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun scrollToBottom() {
        recyclerView.post {
            if (messages.isNotEmpty()) {
                recyclerView.smoothScrollToPosition(messages.size - 1)
            }
        }
    }

    inner class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

        companion object {
            const val VIEW_TYPE_USER = 1
            const val VIEW_TYPE_AI = 2
            const val VIEW_TYPE_THINKING = 3
        }

        inner class ViewHolder(itemView: View, val textView: TextView) : RecyclerView.ViewHolder(itemView)

        override fun getItemViewType(position: Int): Int {
            val msg = messages[position]
            return when {
                msg.isThinking -> VIEW_TYPE_THINKING
                msg.role == "user" -> VIEW_TYPE_USER
                else -> VIEW_TYPE_AI
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val density = parent.resources.displayMetrics.density
            val padding16 = (16 * density).toInt()
            val padding12 = (12 * density).toInt()

            val wrapper = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val textView = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    val margin = (16 * density).toInt()
                    setMargins(margin, (8 * density).toInt(), margin, (8 * density).toInt())
                }
                setPadding(padding16, padding12, padding16, padding12)
                textSize = 15f
                maxWidth = (280 * density).toInt()
            }

            when (viewType) {
                VIEW_TYPE_USER -> {
                    textView.setTextColor(android.graphics.Color.WHITE)
                    textView.background = GradientDrawable().apply {
                        cornerRadii = floatArrayOf(
                            20f * density, 20f * density,
                            4f * density, 4f * density,
                            20f * density, 20f * density,
                            20f * density, 20f * density
                        )
                        setColor(android.graphics.Color.parseColor("#10A37F"))
                    }
                }
                VIEW_TYPE_AI, VIEW_TYPE_THINKING -> {
                    textView.setTextColor(android.graphics.Color.parseColor("#374151"))
                    textView.background = GradientDrawable().apply {
                        cornerRadii = floatArrayOf(
                            4f * density, 4f * density,
                            20f * density, 20f * density,
                            20f * density, 20f * density,
                            20f * density, 20f * density
                        )
                        setColor(android.graphics.Color.parseColor("#F7F7F8"))
                    }
                }
            }

            wrapper.addView(textView)
            return ViewHolder(wrapper, textView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = messages[position]
            holder.textView.text = msg.content

            val lp = holder.textView.layoutParams as LinearLayout.LayoutParams
            if (msg.role == "user") {
                lp.gravity = android.view.Gravity.END
            } else {
                lp.gravity = android.view.Gravity.START
            }
            holder.textView.layoutParams = lp
        }

        override fun getItemCount(): Int = messages.size
    }
}

// Standalone stubs for project dependencies (remove if these classes already exist)
class UnifiedAIEngine(private val context: Context) {
    suspend fun process(message: String): String {
        kotlinx.coroutines.delay(1200)
        return "AI가 메시지를 분석했습니다: $message"
    }
}

class ChatHistoryManager private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: ChatHistoryManager? = null
        fun getInstance(context: Context): ChatHistoryManager {
            return instance ?: synchronized(this) {
                instance ?: ChatHistoryManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun createSession(): String = UUID.randomUUID().toString()
    fun saveMessage(sessionId: String, message: ChatMessage) {}
    fun getMessages(sessionId: String): List<ChatMessage> = emptyList()
}
