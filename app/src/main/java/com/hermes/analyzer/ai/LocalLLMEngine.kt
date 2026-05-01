package com.hermes.analyzer.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * LocalLLMEngine - On-device Large Language Model
 *
 * Uses MediaPipe LLM Inference API to run Gemma 2B IT locally.
 * No internet required after model download.
 * All inference happens on-device (GPU/CPU).
 */
class LocalLLMEngine(private val context: Context) {

    companion object {
        private const val TAG = "LocalLLMEngine"
        private const val MODEL_FILE = "gemma-2b-it-gpu.bin"
        private const val MODEL_URL = "https://storage.googleapis.com/mediapipe-models/gemma-2b-it-gpu/gemma-2b-it-gpu.bin"
        private const val PREFS_NAME = "hermes_local_llm"
        private const val PREFS_KEY_DOWNLOADED = "model_downloaded"
        private const val PREFS_KEY_VERSION = "model_version"

        // Model info
        const val MODEL_SIZE_GB = 1.3
        const val MODEL_SIZE_BYTES = 1395864371L
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var llmInference: LlmInference? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Status callback
    var onStatusUpdate: ((String) -> Unit)? = null
    var onDownloadProgress: ((Int, Long, Long) -> Unit)? = null

    /** Model file location */
    val modelFile: File
        get() = File(context.getExternalFilesDir(null), "models/$MODEL_FILE")

    /** Check if model is downloaded and ready */
    fun isModelReady(): Boolean {
        return modelFile.exists() && modelFile.length() > MODEL_SIZE_BYTES / 2
    }

    /** Initialize LLM inference engine (call after model is downloaded) */
    fun initialize(): Boolean {
        if (!isModelReady()) {
            Log.w(TAG, "Model not found at ${modelFile.absolutePath}")
            return false
        }
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(2048)
                .setTopK(40)
                .setTemperature(0.7f)
                .setRandomSeed(101)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            Log.i(TAG, "Local LLM initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LLM: ${e.message}")
            false
        }
    }

    /** Generate text response from prompt (blocking, use with withContext) */
    fun generateResponse(prompt: String): String {
        val inference = llmInference ?: return "[Local LLM not initialized. Download model first.]"
        return try {
            val result = inference.generateResponse(prompt)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed: ${e.message}")
            "[Local LLM generation error: ${e.message}]"
        }
    }

    /** Generate text response asynchronously */
    suspend fun generateResponseAsync(prompt: String): String = withContext(Dispatchers.IO) {
        generateResponse(prompt)
    }

    /** Download model file from Google Cloud */
    fun downloadModel(
        onProgress: (Int, Long, Long) -> Unit = { _, _, _ -> },
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        scope.launch {
            try {
                onStatusUpdate?.invoke("Downloading Gemma 2B IT model...")
                modelFile.parentFile?.mkdirs()

                val url = URL(MODEL_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "Hermes-Analyzer/1.0")

                val totalBytes = conn.contentLength.toLong().coerceAtLeast(MODEL_SIZE_BYTES)
                var downloadedBytes = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(modelFile).use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        var lastProgress = 0

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onDownloadProgress?.invoke(progress, downloadedBytes, totalBytes)
                                onProgress(progress, downloadedBytes, totalBytes)
                            }
                        }
                    }
                }

                prefs.edit()
                    .putBoolean(PREFS_KEY_DOWNLOADED, true)
                    .putString(PREFS_KEY_VERSION, "1.0")
                    .apply()

                mainHandler.post { onComplete(true, "Model downloaded successfully") }
                onStatusUpdate?.invoke("Model download complete. Initializing...")

                // Auto-initialize after download
                initialize()

            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                modelFile.delete()
                mainHandler.post { onComplete(false, "Download failed: ${e.message}") }
                onStatusUpdate?.invoke("Download failed: ${e.message}")
            }
        }
    }

    /** Cancel download */
    fun cancelDownload() {
        scope.cancel()
    }

    /** Delete model to free space */
    fun deleteModel() {
        llmInference?.close()
        llmInference = null
        modelFile.delete()
        prefs.edit().remove(PREFS_KEY_DOWNLOADED).remove(PREFS_KEY_VERSION).apply()
        Log.i(TAG, "Model deleted")
    }

    /** Get download progress info */
    fun getDownloadInfo(): String {
        if (isModelReady()) {
            val sizeMB = modelFile.length() / (1024 * 1024)
            return "Model ready ($sizeMB MB)"
        }
        if (modelFile.exists()) {
            val progress = (modelFile.length() * 100 / MODEL_SIZE_BYTES).toInt()
            return "Download incomplete ($progress%)"
        }
        return "Model not downloaded (${MODEL_SIZE_GB} GB required)"
    }

    /** Cleanup */
    fun destroy() {
        llmInference?.close()
        llmInference = null
        scope.cancel()
    }
}
