package gemma.core

/*
 * What the conversation screen shows, and the one function that changes it.
 * Pure data and a pure function: no network, no Android. The server stays the
 * authority; this is only the phone's picture of it.
 */

enum class Link { NOT_CONFIGURED, CONNECTING, CONNECTED, UNREACHABLE, AUTH_REQUIRED }

data class ChatMessage(
    /** Stable key for lists: "m<server id>" for stored messages, "t<turn id>:u" / ":a" for the turn in progress. */
    val key: String,
    val role: String,
    val text: String,
    val researchLog: String? = null,
    val streaming: Boolean = false,
    val stopped: Boolean = false,
    /** Set when this message did not go through, with a sentence saying so. */
    val problem: String? = null,
    /** The server's id, once the message is stored there. */
    val id: Long? = null,
    val createdAt: String = "",
    /** Server paths of pictures stored with this message. */
    val attachments: List<String> = emptyList(),
    /** Pictures sent with a turn still in progress. */
    val pendingImages: Int = 0,
    /** Files Gemma made or Glen attached, background tasks this message started, and actions waiting for Glen's OK. */
    val artifacts: List<Artifact> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    /** Names of files sent with a turn still in progress. */
    val pendingFiles: List<String> = emptyList(),
)

data class ChatState(
    val link: Link = Link.NOT_CONFIGURED,
    val modelReady: Boolean = true,
    val voiceReady: Boolean = false,
    val conversationId: Long? = null,
    val conversationTitle: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val hasOlder: Boolean = false,
    val activeTurnId: String? = null,
    /** What Gemma is doing, in the server's own words ("Searching the web: ..."). Empty when idle. */
    val activity: String = "",
    val stopping: Boolean = false,
    /** A short notice for the person, or null. */
    val notice: String? = null,
    val pendingCalendar: PendingCalendar? = null,
    /** A document being read for the turn in progress, with the server's real counters. */
    val docProgress: DocProgress? = null,
    /** How the last document finished: "complete" or "failed"; shown until the next turn. */
    val docOutcome: String? = null,
) {
    /** Background tasks still running anywhere in the visible conversation. */
    val runningJobs: List<Job> get() = messages.flatMap { it.jobs }.filter { it.running }

    val busy: Boolean get() = activeTurnId != null

    /** The small status line under the title. Never more than a few words. */
    val statusLine: String
        get() = when (link) {
            Link.NOT_CONFIGURED -> "Not set up"
            Link.CONNECTING -> "Connecting…"
            Link.UNREACHABLE -> "Vision unavailable"
            Link.AUTH_REQUIRED -> "Authentication required"
            Link.CONNECTED -> if (modelReady) "Connected" else "Connected · Model unavailable"
        }
}

sealed class ChatAction {
    data class LinkChanged(val link: Link, val notice: String? = null) : ChatAction()
    data class StatusKnown(val status: ServerStatus) : ChatAction()
    data class HistoryLoaded(val conversation: Conversation, val page: HistoryPage) : ChatAction()
    data class OlderLoaded(val page: HistoryPage) : ChatAction()
    data class SendStarted(val turnId: String, val text: String, val images: Int = 0, val files: List<String> = emptyList()) : ChatAction()
    /** The latest state of a background task, from the server. */
    data class JobUpdated(val job: Job) : ChatAction()
    /** An approval was decided (here or on another device). */
    data class ApprovalUpdated(val approval: Approval) : ChatAction()
    /** Another chat is about to be shown: clear this one off the screen. */
    object ConversationCleared : ChatAction()
    data class Event(val turnId: String, val event: TurnEvent) : ChatAction()
    data class StopRequested(val turnId: String) : ChatAction()
    /** The turn ended on this phone without a result from the server. */
    data class TurnLost(val turnId: String, val userProblem: String?, val notice: String?, val sent: Boolean = true) : ChatAction()
    /** Another client's turn is running in this conversation, or stopped running. */
    data class RemoteTurn(val turnId: String?) : ChatAction()
    object NoticeDismissed : ChatAction()
}

object ChatReducer {
    private fun Message.toChat() = ChatMessage(
        key = "m$id", role = role, text = text, researchLog = researchLog,
        id = id, createdAt = createdAt, attachments = attachments.map { it.url },
        artifacts = artifacts,
        // A finished picture is already on the message as a file; its task card would only repeat it.
        jobs = jobs.filterNot { j -> j.state == "done" && j.resultArtifactId != null && artifacts.any { it.id == j.resultArtifactId } },
        approvals = approvals,
    )

    fun reduce(s: ChatState, a: ChatAction): ChatState = when (a) {
        is ChatAction.LinkChanged -> s.copy(link = a.link, notice = a.notice ?: s.notice)

        is ChatAction.StatusKnown -> s.copy(
            link = Link.CONNECTED, modelReady = a.status.modelReady, voiceReady = a.status.voiceReady,
        )

        is ChatAction.HistoryLoaded -> {
            // The server's history replaces the phone's picture, except for a turn this phone is in the middle of.
            val mine = s.activeTurnId?.let { id -> s.messages.filter { it.key.startsWith("t$id:") } } ?: emptyList()
            val remote = a.page.activeTurnId?.takeIf { it != s.activeTurnId }
            s.copy(
                conversationId = a.conversation.id,
                conversationTitle = a.conversation.title,
                messages = a.page.messages.map { it.toChat() } + mine,
                hasOlder = a.page.hasMore,
                pendingCalendar = if (s.activeTurnId == null) a.page.pendingCalendar else s.pendingCalendar,
                activity = if (s.activeTurnId == null && remote != null) "Gemma is answering on another device" else s.activity,
            )
        }

        is ChatAction.OlderLoaded -> {
            val known = s.messages.map { it.key }.toSet()
            s.copy(messages = a.page.messages.map { it.toChat() }.filter { it.key !in known } + s.messages, hasOlder = a.page.hasMore)
        }

        is ChatAction.SendStarted -> if (s.busy) s else s.copy(
            activeTurnId = a.turnId,
            stopping = false,
            activity = "Sending…",
            notice = null,
            pendingCalendar = null,
            docProgress = null,
            docOutcome = null,
            messages = s.messages +
                ChatMessage("t${a.turnId}:u", "user", a.text, pendingImages = a.images, pendingFiles = a.files, createdAt = nowStamp()) +
                ChatMessage("t${a.turnId}:a", "assistant", "", streaming = true),
        )

        is ChatAction.Event -> if (a.turnId != s.activeTurnId) s else onEvent(s, a.turnId, a.event)

        is ChatAction.StopRequested -> if (a.turnId == s.activeTurnId) s.copy(stopping = true, activity = "Stopping…") else s

        is ChatAction.TurnLost -> if (a.turnId != s.activeTurnId) s else s.copy(
            activeTurnId = null, stopping = false, activity = "", notice = a.notice,
            messages = s.messages.mapNotNull { m ->
                when (m.key) {
                    // Never reached the server: no bubble at all (the text goes back to the input box).
                    "t${a.turnId}:u" -> if (a.sent) m.copy(problem = a.userProblem) else null
                    // Keep whatever of her reply had already arrived; drop an empty placeholder.
                    "t${a.turnId}:a" -> if (m.text.isBlank()) null else m.copy(streaming = false)
                    else -> m
                }
            },
        )

        is ChatAction.RemoteTurn -> if (s.busy) s else s.copy(
            activity = if (a.turnId != null) "Gemma is answering on another device" else "",
        )

        ChatAction.NoticeDismissed -> s.copy(notice = null)

        is ChatAction.JobUpdated -> s.copy(messages = s.messages.map { m ->
            if (m.jobs.any { it.id == a.job.id }) m.copy(jobs = m.jobs.map { if (it.id == a.job.id) a.job else it }) else m
        })

        is ChatAction.ApprovalUpdated -> s.copy(messages = s.messages.map { m ->
            if (m.approvals.any { it.id == a.approval.id }) m.copy(approvals = m.approvals.map { if (it.id == a.approval.id) a.approval else it }) else m
        })

        ChatAction.ConversationCleared -> if (s.busy) s else s.copy(
            conversationId = null, conversationTitle = "", messages = emptyList(), hasOlder = false,
            activity = "", pendingCalendar = null,
        )
    }

    private fun onEvent(s: ChatState, turnId: String, e: TurnEvent): ChatState {
        val key = "t$turnId:a"
        fun reply(change: (ChatMessage) -> ChatMessage) = s.messages.map { if (it.key == key) change(it) else it }
        return when (e) {
            is TurnEvent.Started -> s.copy(activity = "Thinking…")
            is TurnEvent.Status -> {
                val doc = if (e.docStage != null || e.stage in DOC_STAGES) DocProgress(
                    name = e.docName ?: s.docProgress?.name ?: "", stage = e.docStage ?: e.stage, label = e.label,
                    percent = e.percent ?: s.docProgress?.percent, done = e.done ?: s.docProgress?.done, total = e.total ?: s.docProgress?.total,
                ) else s.docProgress
                if (s.stopping) s.copy(docProgress = doc) else s.copy(activity = e.label.ifBlank { s.activity }, docProgress = doc)
            }
            is TurnEvent.Delta -> s.copy(messages = reply { it.copy(text = it.text + e.text) }, activity = if (s.stopping) s.activity else "")
            TurnEvent.Reset -> s.copy(messages = reply { it.copy(text = "") })
            is TurnEvent.Done -> {
                val r = e.result
                // The server's final text is authoritative, whatever arrived piece by piece.
                val hasCards = r.artifacts.isNotEmpty() || r.jobs.isNotEmpty() || r.approvals.isNotEmpty()
                val finished = reply {
                    it.copy(text = r.reply, researchLog = r.researchLog, streaming = false, stopped = r.stopped, id = r.messageId ?: it.id,
                        createdAt = it.createdAt.ifEmpty { nowStamp() }, artifacts = r.artifacts, jobs = r.jobs, approvals = r.approvals)
                }.filterNot { it.key == key && it.text.isBlank() && !r.stopped && !hasCards }
                s.copy(messages = finished, activeTurnId = null, stopping = false, activity = "", pendingCalendar = r.pendingCalendar,
                    docOutcome = if (s.docProgress != null) "complete" else null)
            }
            is TurnEvent.Failed -> s.copy(
                activeTurnId = null, stopping = false, activity = "", notice = e.failure.userMessage(),
                docOutcome = if (s.docProgress != null) "failed" else null,
                messages = s.messages.mapNotNull { m ->
                    when (m.key) {
                        "t$turnId:u" -> m.copy(problem = "Not answered")
                        key -> if (m.text.isBlank()) null else m.copy(streaming = false)
                        else -> m
                    }
                },
            )
            // Speech belongs to the audio player; it never changes the text.
            is TurnEvent.Speech, is TurnEvent.SpeechError, is TurnEvent.SpeechDone -> s
            TurnEvent.Ping, is TurnEvent.Unknown -> s
        }
    }
}

private val DOC_STAGES = setOf("reading", "chunking", "indexing", "analysing", "analysing_retry")

/** Now, in the same ISO form the server uses, for a message shown before the server has stored it. */
fun nowStamp(): String = java.time.OffsetDateTime.now().withNano(0).toString()

/** Turn ids are chosen by the client so a message can never be answered twice by accident. */
object TurnIds {
    private val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val random = java.security.SecureRandom()

    fun new(): String {
        val sb = StringBuilder("a")
        sb.append(java.lang.Long.toString(System.currentTimeMillis(), 36))
        repeat(12) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    fun isValid(id: String): Boolean = Regex("^[A-Za-z0-9_-]{8,64}$").matches(id)
}

/**
 * Order and deduplication for speech pieces.
 * Pieces can arrive late, twice, or after Stop; only the right ones, in order, reach the player.
 */
class SpeechQueue(private val play: (turnId: String, seq: Int, url: String) -> Unit) {
    private var turnId: String? = null
    private var next = 0
    private val waiting = HashMap<Int, String>()
    private val stopped = HashSet<String>()

    /** A new turn's speech begins: anything left from an earlier turn is dropped. */
    @Synchronized fun begin(turn: String) {
        turnId = turn
        next = 0
        waiting.clear()
    }

    @Synchronized fun offer(turn: String, seq: Int, url: String) {
        if (turn != turnId || turn in stopped || seq < next || waiting.containsKey(seq)) return
        waiting[seq] = url
        while (true) {
            val ready = waiting.remove(next) ?: break
            play(turn, next, ready)
            next++
        }
    }

    /** Stop, or voice switched off: nothing further from this turn is ever played. */
    @Synchronized fun stop(turn: String?) {
        if (turn != null) stopped.add(turn)
        if (turn == turnId) waiting.clear()
    }

    /** Text was withdrawn mid-turn: pieces already queued are dropped, numbering carries on from the server. */
    @Synchronized fun reset(turn: String) {
        if (turn == turnId) waiting.clear()
    }
}

/**
 * "Just now", "About 5 mins ago", "Yesterday"…: the same wording and thresholds as the web page's
 * relativeTime(), so a message reads the same on both. Empty when the stamp cannot be read.
 */
object RelativeTime {
    fun of(stamp: String, nowMillis: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        if (stamp.isBlank()) return ""
        val at = parse(stamp) ?: return ""
        val sec = maxOf(0L, (nowMillis - at.toEpochMilli()) / 1000)
        return when {
            sec < 45 -> "Just now"
            sec < 90 -> "About 1 min ago"
            sec < 3600 -> "About ${Math.round(sec / 60.0)} mins ago"
            sec < 5400 -> "About 1 hour ago"
            sec < 86400 -> "About ${Math.round(sec / 3600.0)} hours ago"
            sec < 172800 -> "Yesterday"
            else -> at.atZone(zone).toLocalDate().format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT))
        }
    }

    /** The server writes ISO times with an offset; older rows may lack one (taken as UTC). */
    fun parse(stamp: String): java.time.Instant? = try {
        java.time.OffsetDateTime.parse(stamp).toInstant()
    } catch (e: Exception) {
        try {
            java.time.LocalDateTime.parse(stamp.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC)
        } catch (e2: Exception) {
            null
        }
    }
}
