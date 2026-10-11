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
import kotlinx.serialization.json.JsonObject

class ChatController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
    private val retryDelayMs: Long = 2000,
    private val maxLostTurnChecks: Int = 150,
    private val voiceEnabled: () -> Boolean = { false },
    private val clientContext: () -> JsonObject? = { null },
    private val turnEvent: (String, TurnEvent) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow(ChatState()); val state: StateFlow<ChatState> = _state
    @Volatile private var api: GemmaApi? = null
    @Volatile private var call: TurnCall? = null
    private fun dispatch(action: ChatAction) = _state.update { ChatReducer.reduce(it, action) }
    fun configure(address:String?, token:()->String?){ api=address?.takeIf{it.isNotBlank()}?.let{GemmaApi(it,token)}; if(api==null)dispatch(ChatAction.LinkChanged(Link.NOT_CONFIGURED)) }
    fun connect():Job=scope.launch(io){connectNow()}; fun onForeground(){if(!state.value.busy)connect()}; fun dismissNotice()=dispatch(ChatAction.NoticeDismissed)
    private fun onFailure(f:GemmaFailure,what:String){log("$what failed: ${f.javaClass.simpleName}");when{f is GemmaFailure.Unreachable||f is GemmaFailure.Interrupted->dispatch(ChatAction.LinkChanged(Link.UNREACHABLE));f is GemmaFailure.AuthRequired||(f is GemmaFailure.Api&&f.code=="auth_required")->dispatch(ChatAction.LinkChanged(Link.AUTH_REQUIRED));else->dispatch(ChatAction.LinkChanged(state.value.link,notice=f.userMessage()))}}
    private fun connectNow():Boolean{val a=api?:run{dispatch(ChatAction.LinkChanged(Link.NOT_CONFIGURED));return false};if(state.value.link!=Link.CONNECTED)dispatch(ChatAction.LinkChanged(Link.CONNECTING));return try{val status=a.status();dispatch(ChatAction.StatusKnown(status));if(status.apiVersion>SUPPORTED_API_VERSION)dispatch(ChatAction.LinkChanged(Link.CONNECTED,notice="Vision is running a newer version than this app expects."));val c=a.primaryConversation();val p=a.messages(c.id,limit=60);dispatch(ChatAction.HistoryLoaded(c,p));log("connected: api v${status.apiVersion}, model ${if(status.modelReady)"ready" else "unavailable"}");true}catch(f:GemmaFailure){onFailure(f,"connect");false}}
    private fun reloadHistory(){val a=api?:return;val id=state.value.conversationId?:return;try{dispatch(ChatAction.HistoryLoaded(Conversation(id,state.value.conversationTitle,true),a.messages(id,limit=60)))}catch(f:GemmaFailure){onFailure(f,"reload")}}
    fun loadOlder():Job=scope.launch(io){val a=api?:return@launch;val s=state.value;val id=s.conversationId?:return@launch;val oldest=s.messages.mapNotNull{it.key.removePrefix("m").toLongOrNull()}.minOrNull()?:return@launch;try{dispatch(ChatAction.OlderLoaded(a.messages(id,before=oldest,limit=60)))}catch(f:GemmaFailure){onFailure(f,"older")}}
    fun send(text:String,onReturned:(String)->Unit={}):Boolean{val a=api?:return false;val s=state.value;val id=s.conversationId?:return false;val msg=text.trim();if(s.busy||msg.isEmpty())return false;val tid=TurnIds.new();val tc=a.newCall(tid);call=tc;dispatch(ChatAction.SendStarted(tid,msg));log("turn $tid: sending (${msg.length} chars)");scope.launch(io){try{val last=a.streamTurn(id,tc,msg,voice=voiceEnabled()&&s.voiceReady,client=clientContext()){e->if(e !is TurnEvent.Ping&&e !is TurnEvent.Delta)log("turn $tid: ${e.javaClass.simpleName}");turnEvent(tid,e);dispatch(ChatAction.Event(tid,e))};if(last is TurnEvent.Failed&&last.failure.code=="model_unavailable"){dispatch(ChatAction.LinkChanged(Link.CONNECTED));_state.update{it.copy(modelReady=false)}};reloadHistory()}catch(f:GemmaFailure.Interrupted){log("turn $tid: connection dropped mid-turn");resolveLostTurn(a,tid)}catch(f:GemmaFailure){onFailure(f,"turn $tid");dispatch(ChatAction.TurnLost(tid,null,f.userMessage()+" Your message wasn't sent.",sent=false));onReturned(msg)}finally{if(call===tc)call=null}};return true}
    private suspend fun resolveLostTurn(a:GemmaApi,tid:String){dispatch(ChatAction.Event(tid,TurnEvent.Status("reconnect","Reconnecting…")));var checks=0;while(checks++<maxLostTurnChecks){try{val info=a.turnInfo(tid);if(state.value.link!=Link.CONNECTED)dispatch(ChatAction.LinkChanged(Link.CONNECTED));when(info.state){TurnState.ACTIVE->dispatch(ChatAction.Event(tid,TurnEvent.Status("reconnect","Gemma is still answering…")));TurnState.DONE,TurnState.STOPPED->{dispatch(ChatAction.Event(tid,TurnEvent.Done(TurnResult(tid,info.reply,info.researchLog,info.state==TurnState.STOPPED,false))));reloadHistory();return};TurnState.FAILED->{dispatch(ChatAction.TurnLost(tid,"Not answered","Gemma couldn't answer that."));return};TurnState.UNKNOWN->{dispatch(ChatAction.TurnLost(tid,"Not received by Vision","Vision didn't receive that message."));return}}}catch(f:GemmaFailure){onFailure(f,"turn $tid check");if(f is GemmaFailure.AuthRequired)break};delay(retryDelayMs)};dispatch(ChatAction.TurnLost(tid,"Sent, but the reply didn't arrive","The connection dropped before Gemma's reply arrived."))}
    fun simulateConnectionLoss(){call?.cancelLocal()}
    fun stop(){val a=api?:return;val tid=state.value.activeTurnId?:return;turnEvent(tid,TurnEvent.SpeechDone(true));dispatch(ChatAction.StopRequested(tid));scope.launch(io){try{a.stopTurn(tid)}catch(_:GemmaFailure){call?.cancelLocal()}}}
}
