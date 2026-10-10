package gemma.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection

/** A server address the person typed, checked and tidied. */
object ServerAddress {
    sealed class Check {
        data class Ok(val url: String, val cleartext: Boolean) : Check()
        data class Bad(val reason: String) : Check()
    }

    fun check(input: String): Check {
        var text = input.trim()
        if (text.isEmpty()) return Check.Bad("Enter Vision's address, for example http://192.168.1.20:7860")
        if (!text.contains("://")) text = "http://$text"
        val uri = try {
            java.net.URI(text)
        } catch (e: Exception) {
            return Check.Bad("That doesn't look like an address.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Check.Bad("The address must start with http:// or https://")
        if (uri.host.isNullOrBlank()) return Check.Bad("The address needs a host name or IP.")
        if (uri.userInfo != null) return Check.Bad("Don't put a name or key in the address. The access key has its own field.")
        if (!uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) return Check.Bad("Give only the server's address, without ? or #.")
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = (uri.path ?: "").trimEnd('/')
        return Check.Ok("$scheme://${uri.host}$port$path", scheme == "http")
    }
}

/** A turn that is being streamed. `cancelLocal` only drops this phone's connection; stopping Gemma is a server call. */
class TurnCall internal constructor(val turnId: String) {
    @Volatile internal var connection: HttpURLConnection? = null
    @Volatile var closedLocally: Boolean = false
        private set

    fun cancelLocal() {
        closedLocally = true
        try {
            connection?.disconnect()
        } catch (e: Exception) {
        }
    }
}

/**
 * Talks to Gemma's application server and to nothing else. All calls block:
 * run them off the main thread. The access key is only ever put in the
 * Authorization header, never in a URL, a log line or an error message.
 */
class GemmaApi(
    baseUrl: String,
    private val token: () -> String?,
    private val connectTimeoutMs: Int = 6000,
    private val readTimeoutMs: Int = 45000,
) {
    private val base = baseUrl.trimEnd('/')

    private fun open(method: String, path: String, read: Int = readTimeoutMs): HttpURLConnection {
        val c = java.net.URI(base + path).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = read
        c.setRequestProperty("Accept", "application/json")
        token()?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        return c
    }

    private fun readAll(c: HttpURLConnection): Pair<Int, String> {
        val status = c.responseCode
        val stream = if (status >= 400) c.errorStream else c.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return status to text
    }

    private fun parseObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text).asObject()
    } catch (e: Exception) {
        null
    }

    private fun call(method: String, path: String, body: JsonObject? = null): JsonObject {
        val c = try {
            open(method, path)
        } catch (e: Exception) {
            throw GemmaFailure.Unreachable(e.javaClass.simpleName)
        }
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val (status, text) = readAll(c)
            val json = parseObject(text)
            if (status == 401) throw GemmaFailure.AuthRequired()
            if (status >= 400) throw apiFailure(json, status)
            return json ?: throw GemmaFailure.BadResponse("not JSON (HTTP $status)")
        } catch (e: GemmaFailure) {
            throw e
        } catch (e: IOException) {
            throw GemmaFailure.Unreachable("${e.javaClass.simpleName}: ${e.message ?: ""}".take(200))
        } finally {
            c.disconnect()
        }
    }

    /** Is there a Gemma server at this address at all? Needs no access key. */
    fun health(): Int {
        val o = call("GET", "/api/v1/health")
        if (o.str("service") != "gemma") throw GemmaFailure.BadResponse("that address is not Gemma")
        return o.int("api_version") ?: 0
    }

    fun status(): ServerStatus {
        val o = call("GET", "/api/v1/status")
        val model = o.obj("model")
        val limits = o.obj("limits")
        return ServerStatus(
            apiVersion = o.int("api_version") ?: 0,
            modelName = model?.str("name") ?: "",
            modelReady = model?.bool("ready") ?: false,
            modelReason = model?.str("reason") ?: "",
            voiceReady = o.obj("voice")?.bool("ready") ?: false,
            primaryConversationId = o.long("primary_conversation_id") ?: -1,
            capabilities = o.obj("capabilities")?.mapValues { (_, v) -> v.toString().trim('"') } ?: emptyMap(),
            maxImagesPerTurn = limits?.int("max_images_per_turn") ?: 0,
            maxImageBytes = limits?.long("max_image_bytes") ?: 0,
        )
    }

    fun primaryConversation(): Conversation {
        val o = call("GET", "/api/v1/conversations/primary")
        return Conversation(o.long("id") ?: throw GemmaFailure.BadResponse("no conversation id"), o.str("title") ?: "", true)
    }

    fun messages(conversationId: Long, before: Long? = null, limit: Int = 50): HistoryPage {
        val query = "?limit=$limit" + (before?.let { "&before=$it" } ?: "")
        val o = call("GET", "/api/v1/conversations/$conversationId/messages$query")
        val list = (o["messages"] as? JsonArray)?.mapNotNull { it.asObject() }?.map { m ->
            Message(
                id = m.long("id") ?: 0,
                role = m.str("role") ?: "assistant",
                text = m.str("text") ?: "",
                researchLog = m.str("research_log"),
                attachments = (m["attachments"] as? JsonArray)?.mapNotNull { it.asObject() }
                    ?.map { Attachment(it.long("id") ?: 0, it.str("url") ?: "") } ?: emptyList(),
                createdAt = m.str("created_at") ?: "",
            )
        } ?: emptyList()
        return HistoryPage(conversationId, list, o.bool("has_more") ?: false, o.str("active_turn_id"))
    }

    fun turnInfo(turnId: String): TurnInfo {
        val o = call("GET", "/api/v1/turns/$turnId")
        val state = when (o.str("state")) {
            "active" -> TurnState.ACTIVE
            "done" -> TurnState.DONE
            "stopped" -> TurnState.STOPPED
            "failed" -> TurnState.FAILED
            else -> TurnState.UNKNOWN
        }
        return TurnInfo(turnId, state, o.str("reply") ?: "", o.str("research_log"))
    }

    /** Ask the server to stop the turn. This cancels the real generation, not just what the phone shows. */
    fun stopTurn(turnId: String): Boolean = call("POST", "/api/v1/turns/$turnId/stop").bool("was_running") ?: false

    fun newCall(turnId: String) = TurnCall(turnId)

    /**
     * Send one user turn and read the server's events as they arrive.
     * Returns the final event (Done or Failed). Throws:
     *   Unreachable   nothing was sent, or it is certain the server did not start the turn
     *   Interrupted   the connection dropped after the request went out; ask turnInfo() what happened
     */
    fun streamTurn(
        conversationId: Long,
        call: TurnCall,
        text: String,
        voice: Boolean = false,
        client: JsonObject? = null,
        onEvent: (TurnEvent) -> Unit,
    ): TurnEvent {
        val body = buildJsonObject {
            put("message", text)
            put("turn_id", call.turnId)
            put("voice", voice)
            put("source", "text")
            if (client != null) put("client", client)
        }
        val c = try {
            // The server sends a keep-alive line every ten seconds, so a silence much longer than that is a dead link.
            open("POST", "/api/v1/conversations/$conversationId/turns", read = 40000)
        } catch (e: Exception) {
            throw GemmaFailure.Unreachable(e.javaClass.simpleName)
        }
        call.connection = c
        var sent = false
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("Accept", "application/x-ndjson")
            try {
                c.connect()
            } catch (e: IOException) {
                throw GemmaFailure.Unreachable("${e.javaClass.simpleName}: ${e.message ?: ""}".take(200))
            }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            sent = true
            val status = c.responseCode
            if (status == 401) throw GemmaFailure.AuthRequired()
            if (status >= 400) {
                val textBody = c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                return TurnEvent.Failed(apiFailure(parseObject(textBody), status)).also(onEvent)
            }
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                var final: TurnEvent? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    val event = Events.parse(line, call.turnId) ?: continue
                    onEvent(event)
                    if (event is TurnEvent.Done || event is TurnEvent.Failed) final = event
                }
                return final ?: throw GemmaFailure.Interrupted(call.turnId, "stream ended without a result")
            }
        } catch (e: GemmaFailure) {
            throw e
        } catch (e: IOException) {
            val detail = "${e.javaClass.simpleName}: ${e.message ?: ""}".take(200)
            if (!sent) throw GemmaFailure.Unreachable(detail)
            throw GemmaFailure.Interrupted(call.turnId, detail)
        } finally {
            call.connection = null
            c.disconnect()
        }
    }
}
