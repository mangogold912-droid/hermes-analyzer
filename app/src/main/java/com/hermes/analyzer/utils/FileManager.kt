package com.hermes.analyzer.utils

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.*

class FileManager(private val contentResolver: ContentResolver) {
    companion object {
        const val CHUNK_SIZE = 8 * 1024 * 1024 // 8MB chunks
        const val MAX_MEMORY_SIZE = 50 * 1024 * 1024 // 50MB in-memory
    }

    // Unlimited file size reading (chunked)
    fun readLargeFile(uri: Uri, callback: (ByteArray, Long, Long) -> Unit) {
        contentResolver.openInputStream(uri)?.use { input ->
            val totalSize = getFileSize(uri)
            var readBytes = 0L
            val buffer = ByteArray(CHUNK_SIZE)

            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break

                val chunk = if (read == CHUNK_SIZE) buffer else buffer.copyOf(read)
                readBytes += read
                callback(chunk, readBytes, totalSize)
            }
        }
    }

    // Check file size
    fun getFileSize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIdx >= 0) cursor.getLong(sizeIdx) else -1
                } else -1
            } ?: -1
        } catch (_: Exception) { -1 }
    }

    // Streaming hash (SHA-256, no full memory load)
    fun computeStreamingHash(uri: Uri): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // External storage full access (Android 11+)
    fun getAllFilesFromExternalStorage(): List<File> {
        val files = mutableListOf<File>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dirs = arrayOf(
                Environment.getExternalStorageDirectory(),
            )
            for (dir in dirs) {
                if (dir.exists()) {
                    files.addAll(dir.walkTopDown().filter { it.isFile }.toList())
                }
            }
        }
        return files
    }

    // All file access via SAF
    fun requestAllFilesAccess(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:com.hermes.analyzer")
            }
        }
    }
}
