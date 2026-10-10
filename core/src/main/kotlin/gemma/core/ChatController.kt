package gemma.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives one conversation with Gemma's server and publishes what the screen
 * should show. It holds no Android types, so the same class runs in a unit
 * test, on the phone, and later behind a voice or car interface.
 *
 * Rules it keeps:
 *  - the server's history is the history; the phone never keeps its own
 *  - a message is never sent twice automatically; when it is unclear whether
 *    the server received a turn, the server is asked by turn id
 *  - Stop asks the server to cancel the real turn
 */
class ChatController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
    private val retryDelayMs: Long = 2000,
    private val maxLostTurnChecks: Int = 150,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    @Volatile private var api: GemmaApi? = null
    @Volatile private var call: TurnCall? = null

    private fun dispatch(action: ChatAction) = _state.update { ChatReducer.reduce(it, action) }

    /** Point the controller at a server. A null address means "not set up yet". */
    fun configure(address: String?, token: () -> String?) {
        api = address?.takeIf { it.isNotBlank() }?.let { GemmaApi(it, token) }
        if (api == null) dispatch(ChatAction.LinkChanged(Link.NOT_CONFIGURED))
    }

    /** Check the server, find the conversation, load its recent history. Safe to call as often as needed. */
    fun connect(): Job = scope.launch(io) { connectNow() }

    /** Call when the app returns to the foreground or the network changes. */
    fun onForeground() {
        if (!state.value.busy) connect()
    }

    fun dismissNotice() = dispatch(ChatAction.NoticeDismissed)

    private fun onFailure(f: GemmaFailure, what: String) {
        log("$what failed: ${f.javaClass.simpleName}" + if (f is GemmaFailure.Api) " ${f.code} ${f.httpStatus}" else "")
        when {
            f is GemmaFailure.Unreachable || f is GemmaFailure.Interrupted -> dispatch(ChatAction.LinkChanged(Link.UNREACHABLE))
            f is GemmaFailure.AuthRequired || (f is GemmaFailure.Api && f.code == "auth_required") ->
                dispatch(ChatAction.LinkChanged(Link.AUTH_REQUIRED))
            else -> dispatch(ChatAction.LinkChanged(state.value.link, notice = f.userMessage()))
        }
    }

    private fun connectNow(): Boolean {
        val api = api ?: run {
            dispatch(ChatAction.LinkChanged(Link.NOT_CONFIGURED))
            return false
        }
        if (state.value.link != Link.CONNECTED) dispatch(ChatAction.LinkChanged(Link.CONNECTING))
        return try {
            val status = api.status()
            dispatch(ChatAction.StatusKnown(status))
            if (status.apiVersion > SUPPORTED_API_VERSION) {
                dispatch(ChatAction.LinkChanged(Link.CONNECTED, notice = "Vision is running a newer version than this app expects."))
            }
            val conversation = api.primaryConversation()
            val page = api.messages(conversation.id, limit = 60)
            dispatch(ChatAction.HistoryLoaded(conversation, page))
            log("connected: api v${status.apiVersion}, model ${if (status.modelReady) "ready" else "unavailable"}, " +
                "voice ${if (status.voiceReady) "ready" else "unavailable"}, ${page.messages.size} messages")
            true
        } catch (f: GemmaFailure) {
            onFailure(f, "connect")
            false
        }
    }

    private fun reloadHistory() {
        val api = api ?: return
        val id = state.value.conversationId ?: return
        try {
            val page = api.messages(id, limit = 60)
            dispatch(ChatAction.HistoryLoaded(Conversation(id, state.value.conversationTitle, true), page))
        } catch (f: GemmaFailure) {
            onFailure(f, "reload")
        }
    }

    fun loadOlder(): Job = scope.launch(io) {
        val api = api ?: return@launch
        val s = state.value
        val id = s.conversationId ?: return@launch
        val oldest = s.messages.mapNotNull { it.key.removePrefix("m").toLongOrNull() }.minOrNull() ?: return@launch
        try {
            dispatch(ChatAction.OlderLoaded(api.messages(id, before = oldest, limit = 60)))
        } catch (f: GemmaFailure) {
            onFailure(f, "older")
        }
    }

    /**
     * Send what Glen typed. Returns false (and changes nothing) if a turn is
     * already running or there is nothing to send to.
     * `onReturned` is called with the text if the server certainly never received it,
     * so the app can put it back in the input box.
     */
    fun send(text: String, onReturned: (String) -> Unit = {}): Boolean {
        val api = api ?: return false
        val s = state.value
        val conversationId = s.conversationId ?: return false
        val message = text.trim()
        if (s.busy || message.isEmpty()) return false

        val turnId = TurnIds.new()
        val thisCall = api.newCall(turnId)
        call = thisCall
        dispatch(ChatAction.SendStarted(turnId, message))
        log("turn $turnId: sending (${message.length} chars)")

        scope.launch(io) {
            try {
                val last = api.streamTurn(conversationId, thisCall, message) { event ->
                    if (event !is TurnEvent.Ping && event !is TurnEvent.Delta) log("turn $turnId: ${event.javaClass.simpleName}")
                    dispatch(ChatAction.Event(turnId, event))
                }
                if (last is TurnEvent.Failed && last.failure.code == "model_unavailable") {
                    dispatch(ChatAction.LinkChanged(Link.CONNECTED))
                    _state.update { it.copy(modelReady = false) }
                }
                reloadHistory()      // swap the in-progress bubbles for the stored messages
            } catch (f: GemmaFailure.Interrupted) {
                log("turn $turnId: connection dropped mid-turn (${f.detail})")
                resolveLostTurn(api, turnId)
            } catch (f: GemmaFailure) {
                // Nothing reached the server: give the text back rather than leave a message that was never sent.
                onFailure(f, "turn $turnId")
                dispatch(ChatAction.TurnLost(turnId, userProblem = null, notice = f.userMessage() + " Your message wasn't sent.", sent = false))
                onReturned(message)
            } finally {
                if (call === thisCall) call = null
            }
        }
        return true
    }

    /**
     * The connection dropped after the message went out. Ask the server, by turn
     * id, what became of it. The message is never sent again from here.
     */
    private suspend fun resolveLostTurn(api: GemmaApi, turnId: String) {
        dispatch(ChatAction.Event(turnId, TurnEvent.Status("reconnect", "Reconnecting…")))
        var checks = 0
        while (checks < maxLostTurnChecks) {
            checks++
            try {
                val info = api.turnInfo(turnId)
                if (state.value.link != Link.CONNECTED) dispatch(ChatAction.LinkChanged(Link.CONNECTED))
                when (info.state) {
                    TurnState.ACTIVE -> dispatch(ChatAction.Event(turnId, TurnEvent.Status("reconnect", "Gemma is still answering…")))
                    TurnState.DONE, TurnState.STOPPED -> {
                        dispatch(ChatAction.Event(turnId, TurnEvent.Done(
                            TurnResult(turnId, info.reply, info.researchLog, info.state == TurnState.STOPPED, duplicate = false))))
                        reloadHistory()
                        log("turn $turnId: recovered after reconnect (${info.state})")
                        return
                    }
                    TurnState.FAILED -> {
                        dispatch(ChatAction.TurnLost(turnId, "Not answered", "Gemma couldn't answer that. You can send it again."))
                        return
                    }
                    TurnState.UNKNOWN -> {
                        dispatch(ChatAction.TurnLost(turnId, "Not received by Vision", "Vision didn't receive that message. You can send it again."))
                        return
                    }
                }
            } catch (f: GemmaFailure) {
                onFailure(f, "turn $turnId check")
                if (f is GemmaFailure.AuthRequired) break
            }
            delay(retryDelayMs)
        }
        dispatch(ChatAction.TurnLost(turnId, "Sent, but the reply didn't arrive",
            "The connection dropped before Gemma's reply arrived. It will appear here once Vision is reachable, if she answered."))
    }

    /** Test hook: behave as if the network dropped in the middle of the current turn. */
    fun simulateConnectionLoss() {
        call?.cancelLocal()
    }

    /** Stop the turn on the server. The screen shows "Stopping…" until the server confirms. */
    fun stop() {
        val api = api ?: return
        val turnId = state.value.activeTurnId ?: return
        dispatch(ChatAction.StopRequested(turnId))
        log("turn $turnId: stop requested")
        scope.launch(io) {
            try {
                api.stopTurn(turnId)
            } catch (f: GemmaFailure) {
                // Could not tell the server. Drop the stream so the turn is resolved by id instead of hanging.
                log("turn $turnId: stop could not be delivered (${f.javaClass.simpleName})")
                call?.cancelLocal()
            }
        }
    }
}
