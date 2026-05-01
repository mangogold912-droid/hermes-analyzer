package com.hermes.analyzer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hermes.analyzer.memory.MemoryCore

class MemoryManagerActivity : AppCompatActivity() {
    private lateinit var memory: MemoryCore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)
        title = "Memory & Projects"

        memory = MemoryCore(this)

        root.addView(TextView(this).apply {
            text = "Memory & Project Manager"
            textSize = 20f
            setPadding(32, 48, 32, 16)
            gravity = Gravity.CENTER
        })

        // Projects section
        root.addView(TextView(this).apply {
            text = "Projects"
            textSize = 16f
            setTextColor(Color.parseColor("#00D4AA"))
            setPadding(32, 24, 32, 8)
        })

        val projects = memory.getProjects()
        if (projects.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "No projects yet. Create one below."
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(32, 8, 32, 16)
            })
        }
        projects.forEach { proj ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
                setBackgroundColor(Color.parseColor("#1E1E1E"))
            }
            card.addView(TextView(this@MemoryManagerActivity).apply {
                text = proj.name
                textSize = 16f
                setTextColor(Color.WHITE)
            })
            card.addView(TextView(this@MemoryManagerActivity).apply {
                text = "${proj.files.size} files | ${proj.goals.size} goals"
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            root.addView(card)
        }

        val etProjectName = EditText(this).apply { hint = "New project name" }
        root.addView(etProjectName)
        root.addView(Button(this).apply {
            text = "Create Project"
            setOnClickListener {
                val name = etProjectName.text.toString()
                if (name.isNotBlank()) {
                    memory.createProject(name)
                    Toast.makeText(this@MemoryManagerActivity, "Created project: $name", Toast.LENGTH_SHORT).show()
                    recreate()
                }
            }
        })

        // Conversations section
        root.addView(TextView(this).apply {
            text = "Conversations"
            textSize = 16f
            setTextColor(Color.parseColor("#00D4AA"))
            setPadding(32, 32, 32, 8)
        })

        val convs = memory.listConversations()
        root.addView(TextView(this).apply {
            text = "${convs.size} conversation sessions stored"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(32, 8, 32, 16)
        })

        root.addView(Button(this).apply {
            text = "New Conversation"
            setOnClickListener {
                val id = memory.createConversation()
                Toast.makeText(this@MemoryManagerActivity, "New session: $id", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
            setPadding(32, 16, 32, 48)
        })
    }
}
