package com.hermes.analyzer.utils

import java.io.*
import java.util.zip.*

/**
 * ArchiveExtractor
 * ZIP, TAR, GZ 압축 해제 유틸리티
 */
object ArchiveExtractor {

    data class ExtractResult(
        val success: Boolean,
        val extractedFiles: List<String>,
        val error: String?
    )

    fun extract(filePath: String, outputDir: String): ExtractResult {
        return when {
            filePath.endsWith(".zip") -> extractZip(filePath, outputDir)
            filePath.endsWith(".tar") || filePath.endsWith(".tar.gz") || filePath.endsWith(".tgz") -> extractTar(filePath, outputDir)
            filePath.endsWith(".gz") && !filePath.endsWith(".tar.gz") -> extractGzSingle(filePath, outputDir)
            else -> ExtractResult(false, emptyList(), "Unsupported archive format")
        }
    }

    fun extractZip(zipPath: String, outputDir: String): ExtractResult {
        val files = mutableListOf<String>()
        return try {
            val outDir = File(outputDir).apply { mkdirs() }
            ZipFile(zipPath).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val outFile = File(outDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        files.add(outFile.absolutePath)
                    }
                }
            }
            ExtractResult(true, files, null)
        } catch (e: Exception) {
            ExtractResult(false, files, e.message)
        }
    }

    fun extractTar(tarPath: String, outputDir: String): ExtractResult {
        val files = mutableListOf<String>()
        return try {
            val outDir = File(outputDir).apply { mkdirs() }
            val input: InputStream = if (tarPath.endsWith(".gz")) {
                GZIPInputStream(FileInputStream(tarPath))
            } else {
                FileInputStream(tarPath)
            }
            input.use { stream ->
                val buf = ByteArray(512)
                while (true) {
                    val read = stream.read(buf)
                    if (read < 512) break
                    // Simple tar header parsing
                    val name = String(buf, 0, 100).trim('\u0000')
                    val sizeStr = String(buf, 124, 12).trim('\u0000')
                    val size = if (sizeStr.isEmpty()) 0 else sizeStr.toLong(8)
                    if (name.isEmpty()) break
                    if (!name.endsWith("/")) {
                        val outFile = File(outDir, name)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var remaining = size
                            while (remaining > 0) {
                                val chunk = stream.read(buf, 0, minOf(512, remaining.toInt()))
                                if (chunk <= 0) break
                                fos.write(buf, 0, chunk)
                                remaining -= chunk
                            }
                        }
                        files.add(outFile.absolutePath)
                    }
                    // Skip to next 512 boundary
                    val skip = (512 - (size % 512)) % 512
                    if (skip > 0) stream.skip(skip)
                }
            }
            ExtractResult(true, files, null)
        } catch (e: Exception) {
            ExtractResult(false, files, e.message)
        }
    }

    private fun extractGzSingle(gzPath: String, outputDir: String): ExtractResult {
        return try {
            val outFile = File(outputDir, File(gzPath).name.removeSuffix(".gz"))
            outFile.parentFile?.mkdirs()
            GZIPInputStream(FileInputStream(gzPath)).use { gz ->
                FileOutputStream(outFile).use { out ->
                    gz.copyTo(out)
                }
            }
            ExtractResult(true, listOf(outFile.absolutePath), null)
        } catch (e: Exception) {
            ExtractResult(false, emptyList(), e.message)
        }
    }

    fun isArchive(fileName: String): Boolean {
        return fileName.endsWith(".zip") || fileName.endsWith(".tar") || fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz") || fileName.endsWith(".gz")
    }
}
