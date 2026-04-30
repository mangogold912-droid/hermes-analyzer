package com.hermes.analyzer.model

data class FileInfo(
    val id: Long = 0,
    val name: String,
    val originalName: String,
    val size: Long,
    val fileType: String,
    val filePath: String,
    val hash: String = "",
    val status: String = "pending",
    val createdAt: Long = System.currentTimeMillis()
)
