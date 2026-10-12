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
