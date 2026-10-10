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

class ChatController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
    private val retryDelayMs: Long = 2000,
    private val maxLostTurnChecks: Int = 150,
    private val voiceEnabled: () -> Boolean = { false },
    private val turnEvent: (String, TurnEvent) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state
    @Volatile private var api: GemmaApi? = null
    @Volatile private var call: TurnCall? = null
    private fun dispatch(action: ChatAction) = _state.update { ChatReducer.reduce(it, action) }

    fun configure(address: String?, token: () -> String?) {
        api = address?.takeIf { it.isNotBlank() }?.let { GemmaApi(it, token) }
        if (api == null) dispatch(ChatAction.LinkChanged(Link.NOT_CONFIGURED))
    }
    fun connect(): Job = scope.launch(io) { connectNow() }
    fun onForeground() { if (!state.value.busy) connect() }
    fun dismissNotice() = dispatch(ChatAction.NoticeDismissed)

    private fun onFailure(f: GemmaFailure, what: String) {
        log("$what failed: ${f.javaClass.simpleName}" + if (f is GemmaFailure.Api) " ${f.code} ${f.httpStatus}" else "")
        when {
            f is GemmaFailure.Unreachable || f is GemmaFailure.Interrupted -> dispatch(ChatAction.LinkChanged(Link.UNREACHABLE))
            f is GemmaFailure.AuthRequired || (f is GemmaFailure.Api && f.code == "auth_required") -> dispatch(ChatAction.LinkChanged(Link.AUTH_REQUIRED))
            else -> dispatch(ChatAction.LinkChanged(state.value.link, notice = f.userMessage()))
        }
    }

    private fun connectNow(): Boolean {
        val api = api ?: run { dispatch(ChatAction.LinkChanged(Link.NOT_CONFIGURED)); return false }
        if (state.value.link != Link.CONNECTED) dispatch(ChatAction.LinkChanged(Link.CONNECTING))
        return try {
            val status = api.status(); dispatch(ChatAction.StatusKnown(status))
            if (status.apiVersion > SUPPORTED_API_VERSION) dispatch(ChatAction.LinkChanged(Link.CONNECTED, notice = "Vision is running a newer version than this app expects."))
            val conversation = api.primaryConversation(); val page = api.messages(conversation.id, limit = 60)
            dispatch(ChatAction.HistoryLoaded(conversation, page))
            log("connected: api v${status.apiVersion}, model ${if (status.modelReady) "ready" else "unavailable"}, voice ${if (status.voiceReady) "ready" else "unavailable"}, ${page.messages.size} messages")
            true
        } catch (f: GemmaFailure) { onFailure(f, "connect"); false }
    }

    private fun reloadHistory() {
        val api = api ?: return; val id = state.value.conversationId ?: return
        try { dispatch(ChatAction.HistoryLoaded(Conversation(id, state.value.conversationTitle, true), api.messages(id, limit = 60))) }
        catch (f: GemmaFailure) { onFailure(f, "reload") }
    }

    fun loadOlder(): Job = scope.launch(io) {
        val api = api ?: return@launch; val s = state.value; val id = s.conversationId ?: return@launch
        val oldest = s.messages.mapNotNull { it.key.removePrefix("m").toLongOrNull() }.minOrNull() ?: return@launch
        try { dispatch(ChatAction.OlderLoaded(api.messages(id, before = oldest, limit = 60))) } catch (f: GemmaFailure) { onFailure(f, "older") }
    }

    fun send(text: String, onReturned: (String) -> Unit = {}): Boolean {
        val api = api ?: return false; val s = state.value; val conversationId = s.conversationId ?: return false
        val message = text.trim(); if (s.busy || message.isEmpty()) return false
        val turnId = TurnIds.new(); val thisCall = api.newCall(turnId); call = thisCall
        dispatch(ChatAction.SendStarted(turnId, message)); log("turn $turnId: sending (${message.length} chars)")
        scope.launch(io) {
            try {
                val last = api.streamTurn(conversationId, thisCall, message, voice = voiceEnabled() && s.voiceReady) { event ->
                    if (event !is TurnEvent.Ping && event !is TurnEvent.Delta) log("turn $turnId: ${event.javaClass.simpleName}")
                    turnEvent(turnId, event); dispatch(ChatAction.Event(turnId, event))
                }
                if (last is TurnEvent.Failed && last.failure.code == "model_unavailable") { dispatch(ChatAction.LinkChanged(Link.CONNECTED)); _state.update { it.copy(modelReady = false) } }
                reloadHistory()
            } catch (f: GemmaFailure.Interrupted) { log("turn $turnId: connection dropped mid-turn (${f.detail})"); resolveLostTurn(api, turnId) }
            catch (f: GemmaFailure) {
                onFailure(f, "turn $turnId"); dispatch(ChatAction.TurnLost(turnId, null, f.userMessage() + " Your message wasn't sent.", sent = false)); onReturned(message)
            } finally { if (call === thisCall) call = null }
        }
        return true
    }

    private suspend fun resolveLostTurn(api: GemmaApi, turnId: String) {
        dispatch(ChatAction.Event(turnId, TurnEvent.Status("reconnect", "Reconnecting…"))); var checks = 0
        while (checks < maxLostTurnChecks) {
            checks++
            try {
                val info = api.turnInfo(turnId); if (state.value.link != Link.CONNECTED) dispatch(ChatAction.LinkChanged(Link.CONNECTED))
                when (info.state) {
                    TurnState.ACTIVE -> dispatch(ChatAction.Event(turnId, TurnEvent.Status("reconnect", "Gemma is still answering…")))
                    TurnState.DONE, TurnState.STOPPED -> { dispatch(ChatAction.Event(turnId, TurnEvent.Done(TurnResult(turnId, info.reply, info.researchLog, info.state == TurnState.STOPPED, false)))); reloadHistory(); return }
                    TurnState.FAILED -> { dispatch(ChatAction.TurnLost(turnId, "Not answered", "Gemma couldn't answer that. You can send it again.")); return }
                    TurnState.UNKNOWN -> { dispatch(ChatAction.TurnLost(turnId, "Not received by Vision", "Vision didn't receive that message. You can send it again.")); return }
                }
            } catch (f: GemmaFailure) { onFailure(f, "turn $turnId check"); if (f is GemmaFailure.AuthRequired) break }
            delay(retryDelayMs)
        }
        dispatch(ChatAction.TurnLost(turnId, "Sent, but the reply didn't arrive", "The connection dropped before Gemma's reply arrived. It will appear here once Vision is reachable, if she answered."))
    }

    fun simulateConnectionLoss() { call?.cancelLocal() }
    fun stop() {
        val api = api ?: return; val turnId = state.value.activeTurnId ?: return
        turnEvent(turnId, TurnEvent.SpeechDone(true)); dispatch(ChatAction.StopRequested(turnId)); log("turn $turnId: stop requested")
        scope.launch(io) { try { api.stopTurn(turnId) } catch (f: GemmaFailure) { log("turn $turnId: stop could not be delivered (${f.javaClass.simpleName})"); call?.cancelLocal() } }
    }
}
