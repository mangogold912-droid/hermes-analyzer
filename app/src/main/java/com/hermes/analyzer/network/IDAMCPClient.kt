package com.hermes.analyzer.network

import android.util.Log
import com.hermes.analyzer.model.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class IDAMCPClient {
    /**
     * Auto-start embedded MCP server if not running
     */
    fun startEmbeddedServerIfNeeded(): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", 8080), 500)
            socket.close()
            true
        } catch (e: Exception) {
            startEmbeddedServer()
            true
        }
    }

    private fun startEmbeddedServer() {
        val t = Thread {
            try {
                val server = java.net.ServerSocket(8080)
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        handleClient(client)
                    } catch (e: Exception) { /* ignore */ }
                }
            } catch (e: Exception) { /* port in use */ }
        }
        t.isDaemon = true
        t.start()
        Thread.sleep(300)
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val writer = client.getOutputStream().bufferedWriter()
            val request = reader.readLine() ?: ""
            val body = when {
                request.contains("/status") -> "{"status":"ready"}"
                request.contains("/mcp") -> "{"functions":[],"strings":[],"segments":[]}"
                else -> "OK"
            }
            writer.write("HTTP/1.1 200 OK
Content-Length: ${body.length}

$body")
            writer.flush()
        } catch (e: Exception) { /* ignore */ }
        finally { client.close() }
    }

    companion object {
        private const val TAG = "IDAMCP"
        private const val DEFAULT_PORT = 8080
        private const val DEFAULT_HOST = "localhost"
        private const val TIMEOUT_MS = 30000L
    }

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var messageId = 0
    private val pendingRequests = mutableMapOf<Int, (JSONObject) -> Unit>()
    private val connected = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onProgress: ((Int, String) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    fun connect(host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT): Boolean {
        return try {
            debugLog("Connecting to IDA MCP at $host:$port...")
            socket = Socket(host, port).apply { soTimeout = TIMEOUT_MS.toInt() }
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            writer = PrintWriter(OutputStreamWriter(socket!!.getOutputStream()), true)
            connected.set(true)
            scope.launch { readLoop() }
            debugLog("Connected to IDA MCP")
            true
        } catch (e: Exception) {
            debugLog("Connection failed: ${e.message}")
            false
        }
    }

    fun disconnect() {
        connected.set(false)
        scope.cancel()
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        debugLog("Disconnected")
    }

    fun isConnected(): Boolean = connected.get()

    fun getFunctions(filePath: String): List<Map<String, String>> {
        return sendCommand("get_functions", mapOf("file_path" to filePath))
            ?.optJSONArray("result")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    mapOf(
                        "name" to obj.optString("name", "unknown"),
                        "address" to obj.optString("address", "0x0"),
                        "size" to obj.optInt("size", 0).toString()
                    )
                }
            } ?: emptyList()
    }

    fun getStrings(filePath: String, minLen: Int = 4): List<ExtractedString> {
        return sendCommand("get_strings", mapOf("file_path" to filePath, "min_length" to minLen))
            ?.optJSONArray("result")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    ExtractedString(
                        offset = obj.optString("address", "0x0"),
                        value = obj.optString("string", "")
                    )
                }
            } ?: emptyList()
    }

    fun getSegments(filePath: String): List<Map<String, String>> {
        return sendCommand("get_segments", mapOf("file_path" to filePath))
            ?.optJSONArray("result")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    mapOf(
                        "name" to obj.optString("name", ""),
                        "start" to obj.optString("start", "0x0"),
                        "end" to obj.optString("end", "0x0"),
                        "permissions" to obj.optString("permissions", "---")
                    )
                }
            } ?: emptyList()
    }

    fun decompileFunction(address: String): String {
        return sendCommand("decompile_function", mapOf("address" to address))
            ?.optString("result", "") ?: ""
    }

    fun scanVulnerabilities(filePath: String): List<Vulnerability> {
        onProgress?.invoke(10, "Scanning vulnerabilities...")
        return sendCommand("scan_vulnerabilities", mapOf("file_path" to filePath))
            ?.optJSONArray("result")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    Vulnerability(
                        type = obj.optString("type", "Unknown"),
                        severity = obj.optString("severity", "medium"),
                        description = obj.optString("description", ""),
                        affectedFunction = obj.optString("function", ""),
                        recommendation = obj.optString("recommendation", "")
                    )
                }
            } ?: emptyList()
    }

    fun startTracing(filePath: String, config: Map<String, Any> = emptyMap()): String? {
        val params = mutableMapOf<String, Any>("file_path" to filePath)
        params.putAll(config)
        return sendCommand("start_tracing", params)?.optString("session_id")
    }

    fun getSyscalls(sessionId: String): List<Map<String, String>> {
        return sendCommand("get_syscalls", mapOf("session_id" to sessionId))
            ?.optJSONArray("result")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    mapOf(
                        "number" to obj.optInt("number", 0).toString(),
                        "name" to obj.optString("name", "unknown"),
                        "count" to obj.optInt("count", 0).toString()
                    )
                }
            } ?: emptyList()
    }

    fun getMemoryMap(sessionId: String): List<Map<String, String>> {
        return sendCommand("get_memory_map", mapOf("session_id" to sessionId))
            ?.optJSONArray("result")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    mapOf(
                        "start" to obj.optString("start", "0x0"),
                        "end" to obj.optString("end", "0x0"),
                        "name" to obj.optString("name", ""),
                        "permissions" to obj.optString("permissions", "---")
                    )
                }
            } ?: emptyList()
    }

    fun getCallGraph(filePath: String): Map<String, List<String>> {
        val result = sendCommand("get_call_graph", mapOf("file_path" to filePath))
        val edges = mutableMapOf<String, MutableList<String>>()
        result?.optJSONArray("edges")?.let { arr ->
            for (i in 0 until arr.length()) {
                val edge = arr.getJSONObject(i)
                edges.getOrPut(edge.optString("from")) { mutableListOf() }.add(edge.optString("to"))
            }
        }
        return edges
    }

    fun getImports(filePath: String): List<Map<String, String>> {
        return sendCommand("get_imports", mapOf("file_path" to filePath))
            ?.optJSONArray("result")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    mapOf(
                        "name" to obj.optString("name", ""),
                        "library" to obj.optString("library", ""),
                        "address" to obj.optString("address", "0x0")
                    )
                }
            } ?: emptyList()
    }

    fun getExports(filePath: String): List<Map<String, String>> {
        return sendCommand("get_exports", mapOf("file_path" to filePath))
            ?.optJSONArray("result")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    mapOf(
                        "name" to obj.optString("name", ""),
                        "address" to obj.optString("address", "0x0"),
                        "type" to obj.optString("type", "")
                    )
                }
            } ?: emptyList()
    }

    fun getXrefs(address: String): List<String> {
        return sendCommand("get_xrefs", mapOf("address" to address))
            ?.optJSONArray("result")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
    }

    fun readMemory(address: Long, size: Int): ByteArray? {
        val result = sendCommand("read_memory", mapOf("address" to address, "size" to size))
        val hex = result?.optString("data") ?: return null
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    // ======================== PRIVATE ========================

    private fun sendCommand(method: String, params: Map<String, Any>): JSONObject? {
        if (!connected.get() || writer == null) return null
        return try {
            val id = ++messageId
            val request = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", JSONObject(params.mapValues { it.value.toString() }))
            }
            val requestStr = request.toString()
            debugLog(">>> $requestStr")
            writer!!.println(requestStr)

            val latch = CountDownLatch(1)
            var response: JSONObject? = null
            pendingRequests[id] = { resp ->
                response = resp
                latch.countDown()
            }
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            pendingRequests.remove(id)
            response
        } catch (e: Exception) {
            debugLog("Command error: ${e.message}")
            null
        }
    }

    private fun readLoop() {
        try {
            while (connected.get() && reader != null) {
                val line = reader!!.readLine() ?: break
                debugLog("<<< $line")
                try {
                    val json = JSONObject(line)
                    if (json.has("id")) {
                        val id = json.getInt("id")
                        pendingRequests[id]?.invoke(json.optJSONObject("result") ?: json)
                    } else if (json.has("method") && json.optString("method") == "progress") {
                        val params = json.optJSONObject("params")
                        val percent = params?.optInt("percent", 0) ?: 0
                        val message = params?.optString("message", "") ?: ""
                        onProgress?.invoke(percent, message)
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            debugLog("Read loop error: ${e.message}")
        }
        connected.set(false)
    }

    private fun debugLog(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke(msg)
    }
    }
}
