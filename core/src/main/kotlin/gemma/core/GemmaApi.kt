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

    private fun enc(text: String): String = java.net.URLEncoder.encode(text, "UTF-8")

    private fun call(
        method: String, path: String, body: JsonObject? = null,
        raw: ByteArray? = null, contentType: String? = null, read: Int = readTimeoutMs,
    ): JsonObject {
        val c = try {
            open(method, path, read)
        } catch (e: Exception) {
            throw GemmaFailure.Unreachable(e.javaClass.simpleName)
        }
        try {
            val bytes = raw ?: body?.toString()?.toByteArray(Charsets.UTF_8)
            if (bytes != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType ?: "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
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

    /**
     * Raw bytes from Gemma's server: a piece of her voice, or a stored picture.
     * Only ever a path on this server, so the access key cannot be sent anywhere else.
     */
    fun fetchBytes(path: String, maxBytes: Int = 40_000_000): ByteArray {
        if (!path.startsWith("/") || path.startsWith("//")) throw GemmaFailure.BadResponse("not a path on Vision")
        val c = try {
            open("GET", path)
        } catch (e: Exception) {
            throw GemmaFailure.Unreachable(e.javaClass.simpleName)
        }
        try {
            c.setRequestProperty("Accept", "*/*")
            val status = c.responseCode
            if (status == 401) throw GemmaFailure.AuthRequired()
            if (status >= 400) {
                val text = c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                throw apiFailure(parseObject(text), status)
            }
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(16384)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) throw GemmaFailure.BadResponse("too large")
                }
            }
            return out.toByteArray()
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
        return HistoryPage(conversationId, list, o.bool("has_more") ?: false, o.str("active_turn_id"), pendingCalendar(o))
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

    // ---- chats ---------------------------------------------------------------------------

    fun conversations(): List<ConversationInfo> =
        call("GET", "/api/v1/conversations").list("conversations").map(::conversationInfo).filter { it.id > 0 }

    fun newConversation(title: String? = null): Conversation {
        val o = call("POST", "/api/v1/conversations", buildJsonObject { if (title != null) put("title", title) })
        return Conversation(o.long("id") ?: throw GemmaFailure.BadResponse("no conversation id"), o.str("title") ?: "", o.bool("primary") ?: false)
    }

    fun renameConversation(id: Long, title: String) {
        call("PUT", "/api/v1/conversations/$id", buildJsonObject { put("title", title) })
    }

    /** Removes the chat from the list (Vision keeps the record). Returns the conversation that is now the main one. */
    fun deleteConversation(id: Long): Long = call("DELETE", "/api/v1/conversations/$id").long("primary_conversation_id") ?: -1

    fun searchConversations(query: String): List<SearchHit> =
        call("GET", "/api/v1/conversations/search?q=${enc(query)}&limit=40").list("results").map {
            SearchHit(it.long("conversation_id") ?: -1, it.str("title") ?: "", it.long("message_id") ?: -1,
                it.str("role") ?: "", it.str("snippet") ?: "", it.str("created_at") ?: "")
        }

    // ---- what Gemma keeps ----------------------------------------------------------------

    fun memory(category: String = "all", query: String = ""): MemoryPage =
        memoryPage(call("GET", "/api/v1/memory?category=${enc(category)}&q=${enc(query)}&limit=200"))

    fun deleteMemory(id: Long) {
        call("DELETE", "/api/v1/memories/$id")
    }

    fun reminders(): List<Reminder> = call("GET", "/api/v1/reminders").list("reminders").map {
        Reminder(it.long("id") ?: -1, it.str("text") ?: "", it.str("due_at") ?: "")
    }

    fun notes(): List<Note> = call("GET", "/api/v1/notes").list("notes").map {
        Note(it.long("id") ?: -1, it.str("list_name") ?: "", it.str("text") ?: "")
    }

    /** With no `after`, only the newest id is reported, so a new phone does not replay old notices. */
    fun notifications(after: Long? = null): NoticePage =
        noticePage(call("GET", "/api/v1/notifications" + (after?.let { "?after=$it" } ?: "")))

    // ---- Glen's files on Vision, read-only ------------------------------------------------

    fun searchFiles(query: String, mode: String = "both"): FileSearch =
        fileSearch(call("GET", "/api/v1/files/search?q=${enc(query)}&mode=${enc(mode)}&limit=40", read = 30000))

    fun fileText(path: String): FileText {
        val o = call("GET", "/api/v1/files/text?path=${enc(path)}", read = 30000)
        return FileText(o.str("path") ?: path, o.str("name") ?: "", o.str("text") ?: "", o.bool("truncated") ?: false)
    }

    /** Speech to text on Vision. Long recordings take a while, so this waits up to ten minutes. */
    fun transcribe(audio: ByteArray, mime: String, language: String? = null): Transcript {
        val o = call("POST", "/api/v1/transcribe" + (language?.let { "?language=${enc(it)}" } ?: ""),
            raw = audio, contentType = mime, read = 600000)
        return Transcript(o.str("text") ?: "", o.str("language") ?: "", o.double("audio_seconds") ?: 0.0)
    }

    fun newCall(turnId: String) = TurnCall(turnId)

    /**
     * Send one user turn and read the server's events as they arrive.
     * Returns the final event (Done or Failed). Throws:
     *   Unreachable   nothing was sent, or it is certain the server did not start the turn
     *   Interrupted   the connection dropped after the request went out; ask turnInfo() what happened
     * With voice on, the stream stays open after Done while the rest of her speech arrives.
     */
    fun streamTurn(
        conversationId: Long,
        call: TurnCall,
        text: String,
        voice: Boolean = false,
        client: JsonObject? = null,
        images: List<String> = emptyList(),
        voiceStyle: String? = null,
        source: String = "text",
        onEvent: (TurnEvent) -> Unit,
    ): TurnEvent {
        val body = buildJsonObject {
            put("message", text)
            put("turn_id", call.turnId)
            put("voice", voice)
            put("source", source)
            if (voice && voiceStyle != null) put("voice_style", voiceStyle)
            if (client != null) put("client", client)
            if (images.isNotEmpty()) put("images", JsonArray(images.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        return ndjson("/api/v1/conversations/$conversationId/turns", body, call, onEvent) { it is TurnEvent.Done || it is TurnEvent.Failed }
    }

    /**
     * Have Gemma say a stored message aloud. Events are Speech / SpeechError / SpeechDone.
     * `call.turnId` is the play id; stopTurn(playId) stops it.
     */
    fun streamSpeech(messageId: Long, call: TurnCall, voiceStyle: String? = null, onEvent: (TurnEvent) -> Unit): TurnEvent {
        val body = buildJsonObject {
            put("play_id", call.turnId)
            if (voiceStyle != null) put("voice_style", voiceStyle)
        }
        return ndjson("/api/v1/messages/$messageId/speech", body, call, onEvent) { it is TurnEvent.SpeechDone || it is TurnEvent.Failed }
    }

    private fun ndjson(
        path: String, body: JsonObject, call: TurnCall,
        onEvent: (TurnEvent) -> Unit, isFinal: (TurnEvent) -> Boolean,
    ): TurnEvent {
        val c = try {
            // The server sends a keep-alive line every ten seconds, so a silence much longer than that is a dead link.
            open("POST", path, read = 40000)
        } catch (e: Exception) {
            throw GemmaFailure.Unreachable(e.javaClass.simpleName)
        }
        call.connection = c
        var sent = false
        var final: TurnEvent? = null
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
                while (true) {
                    val line = reader.readLine() ?: break
                    val event = Events.parse(line, call.turnId) ?: continue
                    if (isFinal(event) && final == null) final = event
                    onEvent(event)
                }
            }
            return final ?: throw GemmaFailure.Interrupted(call.turnId, "stream ended without a result")
        } catch (e: GemmaFailure) {
            throw e
        } catch (e: IOException) {
            // Once the result has arrived, a drop only costs the tail of the stream (remaining speech).
            final?.let { return it }
            val detail = "${e.javaClass.simpleName}: ${e.message ?: ""}".take(200)
            if (!sent) throw GemmaFailure.Unreachable(detail)
            throw GemmaFailure.Interrupted(call.turnId, detail)
        } finally {
            call.connection = null
            c.disconnect()
        }
    }
}
