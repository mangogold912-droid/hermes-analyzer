package com.hermes.analyzer.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * ModelDownloadManager
 * 대용량 AI 모델(4GB+) 분할 다운로드 및 OBB 스타일 저장 관리
 * HuggingFace에서 오픈소스 모델을 멀티파트로 다운로드
 */
class ModelDownloadManager(private val context: Context) {
    private val TAG = "ModelDownloadManager"
    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_model_downloads", Context.MODE_PRIVATE)
    private val modelsDir = File(context.getExternalFilesDir(null), "large_models").apply { mkdirs() }
    private val obbDir = File(context.obbDir?.absolutePath ?: "${context.filesDir}/obb").apply { mkdirs() }

    data class ModelPart(
        val index: Int,
        val url: String,
        val expectedSize: Long,
        val md5Hash: String?
    )

    data class DownloadProgress(
        val modelId: String,
        val partIndex: Int,
        val totalParts: Int,
        val percent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedKbps: Double,
        val status: String // downloading, paused, completed, failed
    )

    data class LargeModel(
        val id: String,
        val name: String,
        val description: String,
        val baseUrl: String,
        val totalSizeGb: Float,
        val parts: Int,
        val format: String, // gguf, safetensors, onnx, tflite
        val quantized: Boolean,
        val contextLength: Int
    )

    /**
     * 추천 대용량 모델 목록 (4GB+)
     */
    fun getLargeModels(): List<LargeModel> {
        return listOf(
            LargeModel(
                id = "Llama-3.1-8B-Instruct-GGUF",
                name = "Llama 3.1 8B Instruct (Q4)",
                description = "Meta의 8B 파라미터 지시 튜닝 모델. Q4 양자화로 4.5GB. 리버스 엔지니어링, 코드 분석, 보고서 작성에 최적.",
                baseUrl = "https://huggingface.co/lmstudio-community/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/",
                totalSizeGb = 4.5f,
                parts = 5,
                format = "gguf",
                quantized = true,
                contextLength = 131072
            ),
            LargeModel(
                id = "Mistral-7B-Instruct-v0.3-GGUF",
                name = "Mistral 7B Instruct v0.3 (Q4)",
                description = "Mistral AI의 7B 모델. Q4 양자화로 4.1GB. 코드 생성, 분석, 자연어 대화에 강력.",
                baseUrl = "https://huggingface.co/lmstudio-community/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/",
                totalSizeGb = 4.1f,
                parts = 4,
                format = "gguf",
                quantized = true,
                contextLength = 32768
            ),
            LargeModel(
                id = "CodeLlama-7B-Instruct-GGUF",
                name = "CodeLlama 7B Instruct (Q4)",
                description = "코드 특화 Llama 모델. 역컴파일, 취약점 분석, 어셈블리→C 변환 전문.",
                baseUrl = "https://huggingface.co/TheBloke/CodeLlama-7B-Instruct-GGUF/resolve/main/",
                totalSizeGb = 3.9f,
                parts = 4,
                format = "gguf",
                quantized = true,
                contextLength = 16384
            ),
            LargeModel(
                id = "DeepSeek-Coder-V2-Lite-Instruct-GGUF",
                name = "DeepSeek Coder V2 Lite (Q4)",
                description = "DeepSeek의 코드 모델. 16B MoE, Q4로 4.2GB. 대규모 코드베이스 분석에 최적.",
                baseUrl = "https://huggingface.co/lmstudio-community/DeepSeek-Coder-V2-Lite-Instruct-GGUF/resolve/main/",
                totalSizeGb = 4.2f,
                parts = 5,
                format = "gguf",
                quantized = true,
                contextLength = 128000
            ),
            LargeModel(
                id = "Hermes-2-Pro-Llama-3-8B-GGUF",
                name = "Hermes 2 Pro Llama 3 8B (Q4)",
                description = "Nous Research의 함수 호출 특화 모델. 에이전트 도구 호출, JSON 출력, 코드 실행에 최적.",
                baseUrl = "https://huggingface.co/NousResearch/Hermes-2-Pro-Llama-3-8B-GGUF/resolve/main/",
                totalSizeGb = 4.7f,
                parts = 5,
                format = "gguf",
                quantized = true,
                contextLength = 8192
            )
        )
    }

    /**
     * 단일 파일 대용량 다운로드 (멀티파트 분할)
     */
    suspend fun downloadLargeModel(
        model: LargeModel,
        onProgress: (DownloadProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val partSize = (model.totalSizeGb * 1024 * 1024 * 1024 / model.parts).toLong()
        val modelFile = File(obbDir, "${model.id}.gguf")

        if (modelFile.exists() && modelFile.length() > partSize * model.parts * 0.95) {
            onProgress(DownloadProgress(model.id, model.parts, model.parts, 100, modelFile.length(), modelFile.length(), 0.0, "completed"))
            markModelDownloaded(model.id, true)
            return@withContext true
        }

        // Create or append to model file
        RandomAccessFile(modelFile, "rw").use { raf ->
            var totalDownloaded: Long = 0
            val totalBytes = (model.totalSizeGb * 1024 * 1024 * 1024).toLong()
            val startTime = System.currentTimeMillis()

            for (partIndex in 0 until model.parts) {
                val partUrl = "${model.baseUrl}${model.id.toLowerCase().replace("-", "_")}.gguf.part${partIndex + 1}"
                val startOffset = partIndex * partSize

                try {
                    val conn = URL(partUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 60000
                    conn.readTimeout = 60000
                    conn.setRequestProperty("Range", "bytes=$startOffset-${startOffset + partSize - 1}")
                    conn.setRequestProperty("User-Agent", "Hermes-Analyzer/1.0")
                    conn.connect()

                    if (conn.responseCode !in 200..299) {
                        // Try without Range header
                        conn.disconnect()
                        val conn2 = URL(partUrl).openConnection() as HttpURLConnection
                        conn2.connectTimeout = 60000
                        conn2.readTimeout = 60000
                        conn2.connect()

                        if (conn2.responseCode !in 200..299) {
                            Log.e(TAG, "Failed to download part ${partIndex + 1}: HTTP ${conn2.responseCode}")
                            onProgress(DownloadProgress(model.id, partIndex, model.parts, 0, totalDownloaded, totalBytes, 0.0, "failed: HTTP ${conn2.responseCode}"))
                            conn2.disconnect()
                            return@withContext false
                        }

                        conn2.inputStream.use { input ->
                            raf.seek(startOffset)
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var partDownloaded: Long = 0

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                raf.write(buffer, 0, bytesRead)
                                partDownloaded += bytesRead
                                totalDownloaded += bytesRead

                                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                                val speed = if (elapsedSec > 0) totalDownloaded / 1024.0 / elapsedSec else 0.0
                                val percent = ((totalDownloaded * 100) / totalBytes).toInt()

                                onProgress(DownloadProgress(model.id, partIndex + 1, model.parts, percent, totalDownloaded, totalBytes, speed, "downloading"))
                            }
                        }
                        conn2.disconnect()
                    } else {
                        conn.inputStream.use { input ->
                            raf.seek(startOffset)
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var partDownloaded: Long = 0

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                raf.write(buffer, 0, bytesRead)
                                partDownloaded += bytesRead
                                totalDownloaded += bytesRead

                                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                                val speed = if (elapsedSec > 0) totalDownloaded / 1024.0 / elapsedSec else 0.0
                                val percent = ((totalDownloaded * 100) / totalBytes).toInt()

                                onProgress(DownloadProgress(model.id, partIndex + 1, model.parts, percent, totalDownloaded, totalBytes, speed, "downloading"))
                            }
                        }
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Part ${partIndex + 1} download error: ${e.message}")
                    onProgress(DownloadProgress(model.id, partIndex, model.parts, 0, totalDownloaded, totalBytes, 0.0, "failed: ${e.message}"))
                    return@withContext false
                }
            }
        }

        markModelDownloaded(model.id, true)
        onProgress(DownloadProgress(model.id, model.parts, model.parts, 100, modelFile.length(), modelFile.length(), 0.0, "completed"))
        true
    }

    /**
     * OBB 디렉터리에서 모델 로드
     */
    fun getModelFile(modelId: String): File? {
        val file = File(obbDir, "$modelId.gguf")
        return if (file.exists() && file.length() > 0) file else null
    }

    fun isModelDownloaded(modelId: String): Boolean {
        return prefs.getBoolean("downloaded_$modelId", false)
    }

    fun markModelDownloaded(modelId: String, downloaded: Boolean) {
        prefs.edit().putBoolean("downloaded_$modelId", downloaded).apply()
    }

    fun deleteModel(modelId: String) {
        File(obbDir, "$modelId.gguf").delete()
        markModelDownloaded(modelId, false)
    }

    fun getTotalModelStorage(): Long {
        return obbDir.listFiles()?.filter { it.name.endsWith(".gguf") }?.sumOf { it.length() } ?: 0
    }

    fun getDownloadedModels(): List<LargeModel> {
        return getLargeModels().filter { isModelDownloaded(it.id) }
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes > 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes > 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f KB".format(bytes / 1024.0)
        }
    }
}
