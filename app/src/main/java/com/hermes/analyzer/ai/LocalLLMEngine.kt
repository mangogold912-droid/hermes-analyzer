package com.hermes.analyzer.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * LocalLLMEngine - On-device Large Language Model
 *
 * Uses MediaPipe LLM Inference API (via reflection) to run Gemma 2B IT locally.
 * Falls back to rule-based analysis if MediaPipe is not available.
 * No internet required after model download.
 */
class LocalLLMEngine(private val context: Context) {

    companion object {
        private const val TAG = "LocalLLMEngine"
        private const val MODEL_FILE = "gemma-2b-it-gpu.bin"
        private const val MODEL_URL = "https://storage.googleapis.com/mediapipe-models/gemma-2b-it-gpu/gemma-2b-it-gpu.bin"
        private const val PREFS_NAME = "hermes_local_llm"
        private const val PREFS_KEY_DOWNLOADED = "model_downloaded"

        const val MODEL_SIZE_GB = 1.3
        const val MODEL_SIZE_BYTES = 1395864371L

        // MediaPipe class names (loaded via reflection)
        private const val MEDIAPIPE_LLM_CLASS = "com.google.mediapipe.tasks.genai.llminference.LlmInference"
        private const val MEDIAPIPE_OPTIONS_CLASS = "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // MediaPipe instance (loaded via reflection)
    private var llmInstance: Any? = null
    private var generateResponseMethod: java.lang.reflect.Method? = null

    // Status callbacks
    var onStatusUpdate: ((String) -> Unit)? = null
    var onDownloadProgress: ((Int, Long, Long) -> Unit)? = null

    /** Model file location */
    val modelFile: File
        get() = File(context.getExternalFilesDir(null), "models/$MODEL_FILE")

    /** Check if model is downloaded */
    fun isModelReady(): Boolean {
        return modelFile.exists() && modelFile.length() > MODEL_SIZE_BYTES / 2
    }

    /**
     * Initialize LLM engine via reflection.
     * This avoids compile-time dependency on MediaPipe.
     */
    fun initialize(): Boolean {
        if (!isModelReady()) {
            Log.w(TAG, "Model not found at ${modelFile.absolutePath}")
            return false
        }
        return try {
            // Load MediaPipe classes via reflection
            val llmClass = Class.forName(MEDIAPIPE_LLM_CLASS)
            val optionsClass = Class.forName(MEDIAPIPE_OPTIONS_CLASS)
            val builderClass = Class.forName("${MEDIAPIPE_OPTIONS_CLASS}\$Builder")

            // Build options using reflection
            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("setModelPath", String::class.java).invoke(builder, modelFile.absolutePath)
            builderClass.getMethod("setMaxTokens", Int::class.java).invoke(builder, 2048)
            builderClass.getMethod("setTopK", Int::class.java).invoke(builder, 40)
            builderClass.getMethod("setTemperature", Float::class.java).invoke(builder, 0.7f)
            builderClass.getMethod("setRandomSeed", Int::class.java).invoke(builder, 101)

            val options = builderClass.getMethod("build").invoke(builder)

            // Create LlmInference instance
            val createMethod = llmClass.getMethod("createFromOptions", Context::class.java, optionsClass)
            llmInstance = createMethod.invoke(null, context, options)

            // Cache the generateResponse method
            generateResponseMethod = llmClass.getMethod("generateResponse", String::class.java)

            Log.i(TAG, "Local LLM initialized via reflection")
            true
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "MediaPipe not available: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LLM: ${e.message}")
            false
        }
    }

    /** Generate text response from prompt */
    fun generateResponse(prompt: String): String {
        val method = generateResponseMethod ?: return "[Local LLM not initialized]"
        val instance = llmInstance ?: return "[Local LLM not initialized]"
        return try {
            method.invoke(instance, prompt) as String
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed: ${e.message}")
            "[Local LLM error: ${e.message}]"
        }
    }

    /** Generate text response asynchronously */
    suspend fun generateResponseAsync(prompt: String): String = withContext(Dispatchers.IO) {
        generateResponse(prompt)
    }

    /** Download model file */
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

                conn.inputStream.use { input ->
                    FileOutputStream(modelFile).use { output ->
                        val buffer = ByteArray(65536)
                        var downloadedBytes = 0L
                        var lastProgress = 0

                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
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
                    .apply()

                mainHandler.post { onComplete(true, "Model downloaded successfully") }
                onStatusUpdate?.invoke("Model download complete. Initializing...")
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

    /** Delete model */
    fun deleteModel() {
        llmInstance?.let {
            try {
                it.javaClass.getMethod("close").invoke(it)
            } catch (_: Exception) {}
        }
        llmInstance = null
        generateResponseMethod = null
        modelFile.delete()
        prefs.edit().remove(PREFS_KEY_DOWNLOADED).apply()
        Log.i(TAG, "Model deleted")
    }

    /** Get download info */
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
        llmInstance?.let {
            try {
                it.javaClass.getMethod("close").invoke(it)
            } catch (_: Exception) {}
        }
        llmInstance = null
        generateResponseMethod = null
        scope.cancel()
    }
}
