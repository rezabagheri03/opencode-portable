package com.rs.ocportable

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * The subset of the engine's HTTP surface the phone and watch need.
 *
 * Everything here is loopback-only and authenticated with Basic auth using the stable password.
 * `GET /api/event` is an SSE stream that is volatile by contract: a reconnect means "state unknown
 * until `session.active` is read again".
 */
class EngineClient(
    private val port: Int = EngineLauncher.PORT,
    private val password: String,
) {
    private val base = "http://127.0.0.1:$port"
    private val authorization: String =
        "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray(Charsets.UTF_8))

    fun healthy(): Boolean {
        val connection = open("/")
        return try {
            connection.connectTimeout = 1_500
            connection.readTimeout = 1_500
            connection.responseCode in 200..399 || connection.responseCode == 401
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    fun get(path: String, readTimeoutMs: Int = 5_000): String {
        val connection = open(path)
        connection.readTimeout = readTimeoutMs
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $path: ${errorBody(connection)}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun post(path: String, body: String): Int {
        val connection = open(path)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $path: ${errorBody(connection)}")
            code
        } finally {
            connection.disconnect()
        }
    }

    fun activeSessions(): Set<String> {
        val response = get("/api/session/active")
        val data = JSONObject(response).optJSONObject("data") ?: return emptySet()
        return data.keys().asSequence().toSet()
    }

    fun replyPermission(sessionID: String, requestID: String, decision: String, message: String? = null) {
        val body = JSONObject().put("decision", decision)
        if (!message.isNullOrBlank()) body.put("message", message)
        post(
            "/api/session/${segment(sessionID)}/permission/${segment(requestID)}/reply",
            body.toString(),
        )
    }

    fun interrupt(sessionID: String) {
        post("/api/session/${segment(sessionID)}/interrupt", "{}")
    }

    fun prompt(sessionID: String, text: String) {
        post("/api/session/${segment(sessionID)}/prompt", JSONObject().put("text", text).toString())
    }

    /**
     * One SSE connection. Returns when the stream ends or throws. The caller owns reconnection.
     */
    fun stream(
        onEvent: (EngineEvent) -> Unit,
        onConnected: () -> Unit,
        onClosed: () -> Unit,
    ) {
        val connection = open("/api/event")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.readTimeout = 0
        try {
            val code = connection.responseCode
            if (code != 200) throw IOException("HTTP $code for /api/event")
            onConnected()
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                val parser = SseParser()
                while (true) {
                    val line = reader.readLine() ?: break
                    val payload = parser.feed(line) ?: continue
                    parseEngineEvent(payload)?.let(onEvent)
                }
            }
        } finally {
            connection.disconnect()
            onClosed()
        }
    }

    private fun open(path: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", authorization)
            connectTimeout = 5_000
            useCaches = false
        }

    private fun errorBody(connection: HttpURLConnection): String =
        try {
            connection.errorStream?.bufferedReader()?.use { it.readText().take(300) }.orEmpty()
        } catch (_: Exception) {
            ""
        }

    private fun segment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * Minimal SSE framing. The engine sends `data: <json>` frames terminated by a blank line and a
     * `:` heartbeat comment every 15 s.
     */
    class SseParser {
        private val data = StringBuilder()

        fun feed(line: String): String? {
            if (line.isEmpty()) {
                if (data.isEmpty()) return null
                val payload = data.toString()
                data.setLength(0)
                return payload
            }
            if (line.startsWith(":")) return null
            if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
            }
            return null
        }
    }

    companion object {
        fun parse(json: String): EngineEvent? = parseEngineEvent(json)
    }
}

fun parseEngineEvent(json: String): EngineEvent? = try {
    val root = JSONObject(json)
    val type = root.optString("type").takeIf { it.isNotBlank() }
    if (type == null) {
        null
    } else {
        EngineEvent(type, root.optJSONObject("data")?.toValueMap() ?: emptyMap())
    }
} catch (_: Exception) {
    null
}

private fun JSONObject.toValueMap(): Map<String, Any?> =
    keys().asSequence().associateWith { key -> unwrap(opt(key)) }

private fun unwrap(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> value.toValueMap()
    is JSONArray -> (0 until value.length()).map { unwrap(value.opt(it)) }
    else -> value
}
