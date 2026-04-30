package com.hermes.analyzer.model

data class AnalysisJob(
    val id: Long = 0,
    val fileId: Long,
    val jobType: String,
    val status: String = "queued",
    val progress: Int = 0,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val errorMessage: String? = null
)
