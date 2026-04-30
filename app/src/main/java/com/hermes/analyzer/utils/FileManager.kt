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

    // 무제한 크기 파일 읽기 (chunked)
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

    // 파일 크기 확인
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

    // 스트리밍 해시 계산 (SHA-256, 메모리에 전체 로드하지 않음)
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

    // 외부 저장소 모든 파일 접근 (Android 11+)
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

    // SAF (Storage Access Framework)를 통한 모든 파일 접근
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
