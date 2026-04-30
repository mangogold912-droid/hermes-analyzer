package com.hermes.analyzer.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hermes.analyzer.model.*

class AnalysisDatabase(context: Context) : SQLiteOpenHelper(context, "hermes.db", null, 1) {

    companion object {
        const val TABLE_FILES = "analysis_files"
        const val TABLE_JOBS = "analysis_jobs"
        const val TABLE_RESULTS = "analysis_results"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_FILES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                original_name TEXT NOT NULL,
                size INTEGER NOT NULL,
                file_type TEXT NOT NULL,
                file_path TEXT NOT NULL,
                hash TEXT,
                status TEXT DEFAULT 'pending',
                created_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_JOBS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_id INTEGER NOT NULL,
                job_type TEXT NOT NULL,
                status TEXT DEFAULT 'queued',
                progress INTEGER DEFAULT 0,
                started_at INTEGER,
                completed_at INTEGER,
                error_message TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_RESULTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id INTEGER NOT NULL,
                platform_name TEXT NOT NULL,
                result_type TEXT NOT NULL,
                content TEXT NOT NULL,
                raw_text TEXT,
                confidence REAL DEFAULT 0.5,
                processing_time INTEGER DEFAULT 0
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insertFile(file: FileInfo): Long {
        val values = ContentValues().apply {
            put("name", file.name)
            put("original_name", file.originalName)
            put("size", file.size)
            put("file_type", file.fileType)
            put("file_path", file.filePath)
            put("hash", file.hash)
            put("status", file.status)
        }
        return writableDatabase.insert(TABLE_FILES, null, values)
    }

    fun insertJob(job: AnalysisJob): Long {
        val values = ContentValues().apply {
            put("file_id", job.fileId)
            put("job_type", job.jobType)
            put("status", job.status)
            put("progress", job.progress)
        }
        return writableDatabase.insert(TABLE_JOBS, null, values)
    }

    fun insertResult(result: AIResult): Long {
        val values = ContentValues().apply {
            put("job_id", result.jobId)
            put("platform_name", result.platformName)
            put("result_type", result.resultType)
            put("content", result.content)
            put("raw_text", result.rawText)
            put("confidence", result.confidence)
            put("processing_time", result.processingTime)
        }
        return writableDatabase.insert(TABLE_RESULTS, null, values)
    }

    fun updateJobStatus(jobId: Long, status: String, progress: Int = 0, error: String? = null) {
        val values = ContentValues().apply {
            put("status", status)
            put("progress", progress)
            error?.let { put("error_message", it) }
            if (status == "completed" || status == "failed" || status == "cancelled") {
                put("completed_at", System.currentTimeMillis() / 1000)
            }
        }
        writableDatabase.update(TABLE_JOBS, values, "id = ?", arrayOf(jobId.toString()))
    }

    fun getRecentFiles(limit: Int = 20): List<FileInfo> {
        val files = mutableListOf<FileInfo>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_FILES ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                files.add(FileInfo(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    originalName = cursor.getString(cursor.getColumnIndexOrThrow("original_name")),
                    size = cursor.getLong(cursor.getColumnIndexOrThrow("size")),
                    fileType = cursor.getString(cursor.getColumnIndexOrThrow("file_type")),
                    filePath = cursor.getString(cursor.getColumnIndexOrThrow("file_path")),
                    hash = cursor.getString(cursor.getColumnIndexOrThrow("hash")) ?: "",
                    status = cursor.getString(cursor.getColumnIndexOrThrow("status")) ?: "pending"
                ))
            }
        }
        return files
    }

    fun getResultsForJob(jobId: Long): List<AIResult> {
        val results = mutableListOf<AIResult>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_RESULTS WHERE job_id = ?",
            arrayOf(jobId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(AIResult(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    jobId = cursor.getLong(cursor.getColumnIndexOrThrow("job_id")),
                    platformName = cursor.getString(cursor.getColumnIndexOrThrow("platform_name")),
                    resultType = cursor.getString(cursor.getColumnIndexOrThrow("result_type")),
                    content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                    rawText = cursor.getString(cursor.getColumnIndexOrThrow("raw_text")) ?: "",
                    confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                    processingTime = cursor.getLong(cursor.getColumnIndexOrThrow("processing_time"))
                ))
            }
        }
        return results
    }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM $TABLE_RESULTS")
        writableDatabase.execSQL("DELETE FROM $TABLE_JOBS")
        writableDatabase.execSQL("DELETE FROM $TABLE_FILES")
    }
}
