package app.gemma.android

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gemma.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class ConnectionTest(val running: Boolean = false, val ok: Boolean = false, val message: String = "")
data class SpeechItem(val turnId: String, val seq: Int, val url: String)
private data class PreparedSpeech(val item: SpeechItem, val player: MediaPlayer, var prepared: Boolean = false)

class GemmaViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = Storage(app)
    @Volatile private var accessKey: String = storage.accessKey
    private val _diagnostics = MutableStateFlow<List<String>>(emptyList()); val diagnostics: StateFlow<List<String>> = _diagnostics
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(line: String) = _diagnostics.update { (it + "${clock.format(Date())}  $line").takeLast(200) }

    private val _voiceEnabled = MutableStateFlow(storage.voiceEnabled); val voiceEnabled: StateFlow<Boolean> = _voiceEnabled
    private val _liveMode = MutableStateFlow(false); val liveMode: StateFlow<Boolean> = _liveMode
    private val _listenNonce = MutableStateFlow(0L); val listenNonce: StateFlow<Long> = _listenNonce
    private val _speaking = MutableStateFlow(false); val speaking: StateFlow<Boolean> = _speaking
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList()); val conversations: StateFlow<List<Conversation>> = _conversations
    private val speechQueue = ArrayDeque<PreparedSpeech>()
    private var player: MediaPlayer? = null
    private var serverSpeechFinished = false

    // Automatic speech belongs to Live Mode only. Normal chat stays silent until Glen taps 🔊.
    private val controller = ChatController(viewModelScope, log = ::log,
        voiceEnabled = { _voiceEnabled.value && _liveMode.value },
        turnEvent = { turn, event -> onTurnEvent(turn, event) })
    val chat: StateFlow<ChatState> = controller.state
    private val _draft = MutableStateFlow(""); val draft: StateFlow<String> = _draft
    private val _test = MutableStateFlow(ConnectionTest()); val test: StateFlow<ConnectionTest> = _test
    private val _developerMode = MutableStateFlow(storage.developerMode); val developerMode: StateFlow<Boolean> = _developerMode

    val serverAddress: String get() = storage.serverAddress
    val hasAccessKey: Boolean get() = accessKey.isNotBlank()
    val isConfigured: Boolean get() = storage.serverAddress.isNotBlank()

    init {
        controller.configure(storage.serverAddress.ifBlank { null }) { accessKey }
        if (isConfigured) { controller.connect(); refreshConversations() }
    }

    private fun api(): GemmaApi? = storage.serverAddress.takeIf { it.isNotBlank() }?.let {
        GemmaApi(baseUrl = it, token = { accessKey })
    }

    fun onDraftChange(text: String) { _draft.value = text }
    fun send() = sendText(_draft.value)
    fun sendText(raw: String) { val text=raw.trim(); if(text.isBlank()) return; if(controller.send(text){ if(_draft.value.isBlank()) _draft.value=it }) _draft.value="" }
    fun onSpeechRecognized(text:String){ if(text.isNotBlank()) sendText(text) else if(_liveMode.value) requestListening() }
    fun requestListening(){ if(!chat.value.busy && !_speaking.value) _listenNonce.value++ }
    fun setLiveMode(on:Boolean){ _liveMode.value=on; if(on){setVoiceEnabled(true);requestListening()} else stopAudio() }
    fun stop(){ _liveMode.value=false;stopAudio();controller.stop() }
    fun loadOlder()=controller.loadOlder(); fun onForeground()=controller.onForeground(); fun dismissNotice()=controller.dismissNotice()
    fun setDeveloperMode(on:Boolean){storage.developerMode=on;_developerMode.value=on}
    fun setVoiceEnabled(on:Boolean){storage.voiceEnabled=on;_voiceEnabled.value=on;if(!on)stopAudio()}

    fun refreshConversations() = viewModelScope.launch(Dispatchers.IO) {
        try { _conversations.value = api()?.conversations().orEmpty() }
        catch (f: GemmaFailure) { log("conversation list: ${f.javaClass.simpleName}") }
    }

    fun selectConversation(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        if (chat.value.busy) return@launch
        try {
            api()?.setPrimaryConversation(id)
            controller.connect().join()
            _conversations.value = api()?.conversations().orEmpty()
        } catch (f: GemmaFailure) { log("conversation switch: ${f.javaClass.simpleName}") }
    }

    /** Normal chat speaker button: render and play exactly this message on demand. */
    fun speakMessage(text: String) = viewModelScope.launch(Dispatchers.IO) {
        if (text.isBlank() || !chat.value.voiceReady) return@launch
        try {
            val path = api()?.requestSpeech(text) ?: return@launch
            val item = SpeechItem("manual", 0, absoluteSpeechUrl(path))
            withContext(Dispatchers.Main) {
                stopAudioNow()
                serverSpeechFinished = true
                prefetchSpeech(item)
            }
        } catch (f: GemmaFailure) { log("manual speech: ${f.userMessage()}") }
    }

    private fun onTurnEvent(turn:String,event:TurnEvent){ when(event){
        is TurnEvent.Started -> viewModelScope.launch(Dispatchers.Main) { clearPreparedQueue(); serverSpeechFinished=false }
        is TurnEvent.Speech -> if(_liveMode.value && _voiceEnabled.value){ log("voice ${event.seq} received: ${event.url.take(80)}"); prefetchSpeech(SpeechItem(turn,event.seq,absoluteSpeechUrl(event.url))) }
        is TurnEvent.SpeechError -> log("Vision voice error: ${event.message}")
        TurnEvent.Reset -> viewModelScope.launch(Dispatchers.Main) { clearPreparedQueue(); stopPlayerOnly() }
        is TurnEvent.SpeechDone -> {serverSpeechFinished=true;if(event.cancelled)stopAudio() else viewModelScope.launch(Dispatchers.Main){ maybeFinishSpeaking() }}
        is TurnEvent.Failed -> {serverSpeechFinished=true;stopAudio()}
        else -> Unit
    }}
    private fun absoluteSpeechUrl(url:String)=if(url.startsWith("http://")||url.startsWith("https://"))url else storage.serverAddress.trimEnd('/')+"/"+url.trimStart('/')

    private fun prefetchSpeech(item: SpeechItem) = viewModelScope.launch(Dispatchers.Main) {
        try {
            val headers = if(accessKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $accessKey")
            val mp = MediaPlayer(); val entry = PreparedSpeech(item, mp); speechQueue.addLast(entry); _speaking.value = true
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(getApplication(), android.net.Uri.parse(item.url), headers)
            mp.setOnPreparedListener { entry.prepared = true; startPreparedHeadIfIdle() }
            mp.setOnErrorListener { p, what, extra ->
                val wasCurrent = player === p; if (wasCurrent) player = null; speechQueue.remove(entry); p.release()
                log("voice ${item.seq}: MediaPlayer error $what/$extra"); startPreparedHeadIfIdle(); maybeFinishSpeaking(); true
            }
            mp.prepareAsync(); startPreparedHeadIfIdle()
        } catch(e:Exception) { log("voice ${item.seq}: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"); maybeFinishSpeaking() }
    }

    private fun startPreparedHeadIfIdle() {
        if (player != null) return
        val head = speechQueue.firstOrNull() ?: run { maybeFinishSpeaking(); return }
        if (!head.prepared) return
        speechQueue.removeFirst(); player = head.player
        head.player.setOnCompletionListener { p -> p.release(); if (player === p) player = null; startPreparedHeadIfIdle() }
        try { head.player.start() } catch(e:Exception) { head.player.release(); if(player === head.player) player=null; startPreparedHeadIfIdle() }
    }

    private fun clearPreparedQueue() { while(speechQueue.isNotEmpty()) speechQueue.removeFirst().player.release() }
    private fun maybeFinishSpeaking(){if(serverSpeechFinished&&speechQueue.isEmpty()&&player==null){_speaking.value=false;if(_liveMode.value)requestListening()}}
    private fun stopPlayerOnly(){player?.runCatching{stop()};player?.release();player=null;_speaking.value=false}
    private fun stopAudioNow(){clearPreparedQueue();stopPlayerOnly();serverSpeechFinished=true}
    private fun stopAudio(){viewModelScope.launch(Dispatchers.Main){stopAudioNow()}}

    private fun cleanKey(key:String)=key.filterNot{it.isWhitespace()}

    /** A successful connection is a successful setup: remember it immediately. */
    fun testConnection(address:String,key:String){
        val checked=ServerAddress.check(address); if(checked is ServerAddress.Check.Bad){_test.value=ConnectionTest(message=checked.reason);return}
        val url=(checked as ServerAddress.Check.Ok).url; val useKey=cleanKey(key).ifBlank{accessKey}; _test.value=ConnectionTest(running=true,message="Connecting…")
        viewModelScope.launch {
            val result=withContext(Dispatchers.IO){
                try {
                    val testApi=GemmaApi(baseUrl = url, token = { useKey }); testApi.health(); val status=testApi.status()
                    storage.serverAddress=url
                    if(useKey.isNotBlank()){ storage.accessKey=useKey; accessKey=useKey }
                    controller.configure(url){accessKey}; controller.connect().join(); _conversations.value=testApi.conversations()
                    ConnectionTest(ok=true,message="Connected and saved. "+(if(status.modelReady)"Model ready. "else"Model unavailable. ")+(if(status.voiceReady)"Voice ready."else"Voice not running."))
                }catch(f:GemmaFailure){log("connection test failed: ${f.javaClass.simpleName}");ConnectionTest(message=f.userMessage())}
            }
            _test.value=result
        }
    }

    fun saveSettings(address:String,key:String):String?{
        val checked=ServerAddress.check(address);if(checked is ServerAddress.Check.Bad)return checked.reason
        val url=(checked as ServerAddress.Check.Ok).url;storage.serverAddress=url;val cleaned=cleanKey(key)
        if(cleaned.isNotBlank()){storage.accessKey=cleaned;accessKey=cleaned};controller.configure(url){accessKey};controller.connect();refreshConversations();return null
    }
    override fun onCleared(){stopAudio();super.onCleared()}
}