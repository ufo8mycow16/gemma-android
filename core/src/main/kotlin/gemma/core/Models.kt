package gemma.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/*
 * Plain data for everything beyond the conversation itself: the list of chats,
 * what Gemma remembers, her notes and reminders, Glen's files on Vision.
 * All of it is the server's; the phone only shows it.
 */

data class ConversationInfo(
    val id: Long, val title: String, val primary: Boolean,
    val updatedAt: String, val messageCount: Int, val preview: String,
)

data class SearchHit(
    val conversationId: Long, val title: String, val messageId: Long,
    val role: String, val snippet: String, val createdAt: String,
)

data class MemoryItem(val id: Long, val category: String, val kind: String, val subject: String, val fact: String, val updatedAt: String)

data class MemoryPage(val counts: Map<String, Int>, val summary: String?, val memories: List<MemoryItem>)

data class Reminder(val id: Long, val text: String, val dueAt: String)

data class Note(val id: Long, val listName: String, val text: String)

/** Something Gemma has to say unprompted (a due reminder, a finished follow-up). */
data class Notice(val id: Long, val conversationId: Long?, val kind: String, val text: String, val createdAt: String)

data class NoticePage(val latest: Long, val items: List<Notice>)

data class FileHit(
    val path: String, val name: String, val folder: String, val size: Long,
    val modified: String, val match: String, val snippet: String,
)

data class FileSearch(val results: List<FileHit>, val truncated: Boolean)

data class FileText(val path: String, val name: String, val text: String, val truncated: Boolean)

data class Transcript(val text: String, val language: String, val audioSeconds: Double)

/** A calendar change Gemma has proposed and Glen has not yet answered. */
data class PendingCalendar(val id: Long, val summary: String)

internal fun JsonObject.list(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it.asObject() } ?: emptyList()

internal fun pendingCalendar(o: JsonObject): PendingCalendar? =
    o.obj("pending_calendar")?.let { p -> p.long("id")?.let { PendingCalendar(it, p.str("summary") ?: "") } }

internal fun conversationInfo(o: JsonObject) = ConversationInfo(
    id = o.long("id") ?: -1, title = o.str("title") ?: "", primary = o.bool("primary") ?: false,
    updatedAt = o.str("updated_at") ?: "", messageCount = o.int("message_count") ?: 0, preview = o.str("preview") ?: "",
)

internal fun memoryPage(o: JsonObject) = MemoryPage(
    counts = o.obj("counts")?.mapNotNull { (k, _) -> o.obj("counts")?.int(k)?.let { k to it } }?.toMap() ?: emptyMap(),
    summary = o.str("summary")?.takeIf { it.isNotBlank() },
    memories = o.list("memories").map {
        MemoryItem(it.long("id") ?: -1, it.str("category") ?: "other", it.str("kind") ?: "", it.str("subject") ?: "",
            it.str("fact") ?: "", it.str("updated_at") ?: "")
    },
)

internal fun noticePage(o: JsonObject) = NoticePage(
    latest = o.long("latest") ?: 0,
    items = o.list("items").map {
        Notice(it.long("id") ?: 0, it.long("conversation_id"), it.str("kind") ?: "", it.str("text") ?: "", it.str("created_at") ?: "")
    },
)

internal fun fileSearch(o: JsonObject) = FileSearch(
    results = o.list("results").map {
        FileHit(it.str("path") ?: "", it.str("name") ?: "", it.str("folder") ?: "", it.long("size") ?: 0,
            it.str("modified") ?: "", it.str("match") ?: "name", it.str("snippet") ?: "")
    }.filter { it.path.isNotEmpty() },
    truncated = o.bool("truncated") ?: false,
)

// ---- files, background tasks and approvals (server API v2) ----------------------------

/** A file on Vision: one Glen attached, or one Gemma (or Claude Code, or a build) made. */
data class Artifact(
    val id: String, val kind: String, val filename: String, val mime: String, val size: Long,
    val url: String, val createdBy: String, val description: String, val createdAt: String,
    val language: String? = null, val text: Boolean = false, val prompt: String? = null,
) {
    val isImage: Boolean get() = kind == "image" || mime.startsWith("image/")
}

/** A background task (a picture, Claude Code, a specialist). `progress` is the server's own fraction, or null when unknown. */
data class Job(
    val id: String, val kind: String, val title: String, val state: String, val progress: Double?,
    val progressText: String, val error: String?, val resultArtifactId: String?, val summary: String?,
    val workspace: String?, val filesChanged: List<String> = emptyList(), val cancelRequested: Boolean = false,
) {
    val running: Boolean get() = state == "queued" || state == "running"
    val finished: Boolean get() = !running
    /** A whole-number percentage only when the server reports real progress for a running task. */
    val percent: Int? get() = progress?.takeIf { state == "running" && !progressText.startsWith("Loading") && !progressText.startsWith("Queued") }
        ?.let { (it * 100).toInt().coerceIn(0, 100) }
}

/** Something Gemma wants to do outside herself, waiting for Glen's OK. */
data class Approval(
    val id: String, val title: String, val plugin: String, val tool: String, val state: String,
    val risk: String, val fields: List<Pair<String, String>>, val body: String, val resultError: String?,
) {
    val pending: Boolean get() = state == "pending"
}

/** Real progress of a document being read, from the server's own counters. */
data class DocProgress(val name: String, val stage: String, val label: String, val percent: Int?, val done: Int?, val total: Int?)

internal fun artifact(o: JsonObject): Artifact? {
    val id = o.str("id") ?: return null
    return Artifact(
        id = id, kind = o.str("kind") ?: "file", filename = o.str("filename") ?: "file", mime = o.str("mime") ?: "",
        size = o.long("size") ?: 0, url = o.str("url") ?: "/api/v1/artifacts/$id/content", createdBy = o.str("created_by") ?: "",
        description = o.str("description") ?: "", createdAt = o.str("created_at") ?: "", language = o.str("language"),
        text = o.bool("text") ?: false, prompt = o.obj("provenance")?.str("prompt"),
    )
}

internal fun job(o: JsonObject): Job? {
    val id = o.str("id") ?: return null
    val r = o.obj("result")
    return Job(
        id = id, kind = o.str("kind") ?: "", title = o.str("title") ?: "Task", state = o.str("state") ?: "queued",
        progress = o.double("progress"), progressText = o.str("progress_text") ?: "", error = o.str("error"),
        resultArtifactId = r?.str("artifact_id"), summary = r?.str("summary")?.takeIf { it.isNotBlank() }, workspace = r?.str("workspace"),
        filesChanged = (r?.get("files_changed") as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList(),
        cancelRequested = o.bool("cancel_requested") ?: false,
    )
}

internal fun approval(o: JsonObject): Approval? {
    val id = o.str("id") ?: return null
    val p = o.obj("preview")
    val fields = (p?.get("fields") as? JsonArray)?.mapNotNull { f ->
        val pair = f as? JsonArray ?: return@mapNotNull null
        if (pair.size < 2) null else pair[0].toString().trim('"') to (pair[1] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    } ?: emptyList()
    val res = o.obj("result")
    val err = res?.get("error")?.let { e -> (e as? JsonObject)?.str("message") ?: (e as? kotlinx.serialization.json.JsonPrimitive)?.content }
    return Approval(id, p?.str("title") ?: o.str("title") ?: "Action", o.str("plugin") ?: "", o.str("tool") ?: "",
        o.str("state") ?: "pending", p?.str("risk") ?: "medium", fields, p?.str("body") ?: "", err)
}

internal fun JsonObject.artifacts(key: String = "artifacts") = list(key).mapNotNull(::artifact)
internal fun JsonObject.jobs(key: String = "jobs") = list(key).mapNotNull(::job)
internal fun JsonObject.approvals(key: String = "approvals") = list(key).mapNotNull(::approval)
