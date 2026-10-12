package gemma.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * The wire protocol of Gemma's server (API v1), as plain data.
 * Nothing here decides anything about Gemma: the server is the one Gemma, and
 * this is only how a client reads what it says.
 */

const val SUPPORTED_API_VERSION = 2

data class Conversation(val id: Long, val title: String, val primary: Boolean)

data class Attachment(val id: Long, val url: String)

/** One stored message. `text` is Gemma's canonical reply; `researchLog` is the app-written log, kept apart. */
data class Message(
    val id: Long,
    val role: String,
    val text: String,
    val researchLog: String?,
    val attachments: List<Attachment>,
    val createdAt: String,
    val artifacts: List<Artifact> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val approvals: List<Approval> = emptyList(),
)

data class HistoryPage(
    val conversationId: Long,
    val messages: List<Message>,
    val hasMore: Boolean,
    val activeTurnId: String?,
    val pendingCalendar: PendingCalendar? = null,
)

data class ServerStatus(
    val apiVersion: Int,
    val modelName: String,
    val modelReady: Boolean,
    val modelReason: String,
    val voiceReady: Boolean,
    val primaryConversationId: Long,
    val capabilities: Map<String, String>,
    val maxImagesPerTurn: Int,
    val maxImageBytes: Long,
    val maxDocumentBytes: Long = 0,
    val maxUploadBytes: Long = 0,
    val build: String = "",
)

data class TurnResult(
    val turnId: String,
    val reply: String,
    val researchLog: String?,
    val stopped: Boolean,
    val duplicate: Boolean,
    val pendingCalendar: PendingCalendar? = null,
    val messageId: Long? = null,
    val userMessageId: Long? = null,
    val artifacts: List<Artifact> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val approvals: List<Approval> = emptyList(),
)

enum class TurnState { ACTIVE, DONE, STOPPED, FAILED, UNKNOWN }

data class TurnInfo(val turnId: String, val state: TurnState, val reply: String, val researchLog: String?)

/** Everything the server can say while a turn is in progress. */
sealed class TurnEvent {
    data class Started(val turnId: String, val conversationId: Long, val apiVersion: Int) : TurnEvent()
    /** What Gemma is doing. Document work also carries the server's own counters (never a client-side guess). */
    data class Status(
        val stage: String, val label: String, val percent: Int? = null, val done: Int? = null, val total: Int? = null,
        val docStage: String? = null, val docName: String? = null,
    ) : TurnEvent()
    data class Delta(val text: String) : TurnEvent()
    /** Text shown so far was withdrawn (Gemma reached for a tool instead): discard it. */
    object Reset : TurnEvent()
    data class Speech(val seq: Int, val url: String, val seconds: Double) : TurnEvent()
    data class SpeechError(val message: String) : TurnEvent()
    data class SpeechDone(val cancelled: Boolean) : TurnEvent()
    data class Done(val result: TurnResult) : TurnEvent()
    data class Failed(val failure: GemmaFailure.Api) : TurnEvent()
    object Ping : TurnEvent()
    data class Unknown(val type: String) : TurnEvent()
}

/** Why something did not work, in terms a person can act on. Never carries the access key. */
sealed class GemmaFailure(message: String) : Exception(message) {
    /** The phone could not reach the server at all. */
    class Unreachable(val detail: String) : GemmaFailure("Can't reach Vision.")
    /** The server is there but did not accept the access key. */
    class AuthRequired : GemmaFailure("Vision needs a valid access key.")
    /** The server answered with a structured error. */
    class Api(val code: String, val text: String, val httpStatus: Int, val activeTurnId: String? = null) :
        GemmaFailure(text)
    /** The server answered with something this app does not understand. */
    class BadResponse(val detail: String) : GemmaFailure("Vision sent a reply this app did not understand.")
    /** The connection dropped part-way through a turn; what happened to the turn is not yet known. */
    class Interrupted(val turnId: String, val detail: String) : GemmaFailure("The connection to Vision dropped.")
}

/** One short sentence for the person using the app. Technical detail goes to diagnostics instead. */
fun GemmaFailure.userMessage(): String = when (this) {
    is GemmaFailure.Unreachable -> "Can't reach Vision."
    is GemmaFailure.AuthRequired -> "Vision needs a valid access key. Check it in Settings."
    is GemmaFailure.BadResponse -> "Vision sent a reply this app did not understand."
    is GemmaFailure.Interrupted -> "The connection to Vision dropped."
    is GemmaFailure.Api -> when (code) {
        "model_unavailable" -> "Gemma's model isn't running on Vision."
        "turn_active" -> "Gemma is already answering in this conversation."
        "conversation_not_found" -> "That conversation no longer exists on Vision."
        "validation_error" -> "Vision didn't accept that message."
        "attachment_rejected" -> "Vision didn't accept that attachment."
        "auth_required" -> "Vision needs a valid access key. Check it in Settings."
        "voice_unavailable" -> "Gemma's voice isn't running on Vision."
        "stt_unavailable" -> "Transcription isn't installed on Vision yet."
        "message_not_found" -> "That message is no longer on Vision."
        else -> "Something went wrong on Vision."
    }
}

// ---- reading JSON without assuming any field is present -------------------------------

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun apiFailure(body: JsonObject?, httpStatus: Int): GemmaFailure.Api = GemmaFailure.Api(
    code = body?.str("code") ?: if (httpStatus == 401) "auth_required" else "internal_error",
    text = body?.str("error") ?: "HTTP $httpStatus",
    httpStatus = body?.int("http_status") ?: httpStatus,
    activeTurnId = body?.str("active_turn_id"),
)

internal fun turnResult(o: JsonObject, fallbackTurnId: String): TurnResult = TurnResult(
    turnId = o.str("turn_id") ?: fallbackTurnId,
    reply = o.str("reply") ?: "",
    researchLog = o.str("research_log"),
    stopped = o.bool("stopped") ?: false,
    duplicate = o.bool("duplicate") ?: false,
    pendingCalendar = pendingCalendar(o),
    messageId = o.long("message_id"),
    userMessageId = o.long("user_message_id"),
    artifacts = o.artifacts(),
    jobs = o.jobs(),
    approvals = o.approvals(),
)

object Events {
    /** One line of the turn stream. Returns null for a line that is not an event at all. */
    fun parse(line: String, turnId: String = ""): TurnEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val o = try {
            kotlinx.serialization.json.Json.parseToJsonElement(trimmed).asObject()
        } catch (e: Exception) {
            null
        } ?: return null
        return when (val type = o.str("type") ?: return null) {
            "start" -> TurnEvent.Started(o.str("turn_id") ?: o.str("play_id") ?: turnId, o.long("conversation_id") ?: -1, o.int("api_version") ?: 0)
            "status" -> TurnEvent.Status(o.str("stage") ?: "", o.str("label") ?: "", o.int("percent"), o.int("done"), o.int("total"),
                o.str("doc_stage"), o.str("doc_name"))
            "delta" -> TurnEvent.Delta(o.str("text") ?: "")
            "reset" -> TurnEvent.Reset
            "speech" -> TurnEvent.Speech(o.int("seq") ?: 0, o.str("url") ?: "", o.double("seconds") ?: 0.0)
            "speech_error" -> TurnEvent.SpeechError(o.str("error") ?: "")
            "speech_done" -> TurnEvent.SpeechDone(o.bool("cancelled") ?: false)
            "done" -> TurnEvent.Done(turnResult(o, turnId))
            "error" -> TurnEvent.Failed(apiFailure(o, o.int("http_status") ?: 500))
            "ping" -> TurnEvent.Ping
            else -> TurnEvent.Unknown(type)
        }
    }
}
