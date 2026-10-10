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

class GemmaViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = Storage(app)
    @Volatile private var accessKey: String = storage.accessKey
    private val _diagnostics = MutableStateFlow<List<String>>(emptyList())
    val diagnostics: StateFlow<List<String>> = _diagnostics
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(line: String) = _diagnostics.update { (it + "${clock.format(Date())}  $line").takeLast(200) }

    private val _voiceEnabled = MutableStateFlow(storage.voiceEnabled); val voiceEnabled: StateFlow<Boolean> = _voiceEnabled
    private val _liveMode = MutableStateFlow(false); val liveMode: StateFlow<Boolean> = _liveMode
    private val _listenNonce = MutableStateFlow(0L); val listenNonce: StateFlow<Long> = _listenNonce
    private val _speaking = MutableStateFlow(false); val speaking: StateFlow<Boolean> = _speaking
    private val speechQueue = ArrayDeque<SpeechItem>()
    private var player: MediaPlayer? = null
    private var serverSpeechFinished = false

    private val controller = ChatController(viewModelScope, log = ::log, voiceEnabled = { _voiceEnabled.value }, turnEvent = { turn, event -> onTurnEvent(turn, event) })
    val chat: StateFlow<ChatState> = controller.state
    private val _draft = MutableStateFlow(""); val draft: StateFlow<String> = _draft
    private val _test = MutableStateFlow(ConnectionTest()); val test: StateFlow<ConnectionTest> = _test
    private val _developerMode = MutableStateFlow(storage.developerMode); val developerMode: StateFlow<Boolean> = _developerMode

    val serverAddress: String get() = storage.serverAddress
    val hasAccessKey: Boolean get() = accessKey.isNotBlank()
    val isConfigured: Boolean get() = storage.serverAddress.isNotBlank()

    init { controller.configure(storage.serverAddress.ifBlank { null }) { accessKey }; if (isConfigured) controller.connect() }
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

    private fun onTurnEvent(turn:String,event:TurnEvent){ when(event){
        is TurnEvent.Started -> synchronized(speechQueue){speechQueue.clear();serverSpeechFinished=false}
        is TurnEvent.Speech -> if(_voiceEnabled.value){ log("voice ${event.seq} received: ${event.url.take(80)}"); enqueueSpeech(SpeechItem(turn,event.seq,absoluteSpeechUrl(event.url))) }
        is TurnEvent.SpeechError -> log("Vision voice error: ${event.message}")
        TurnEvent.Reset -> { synchronized(speechQueue){speechQueue.clear()}; stopPlayerOnly() }
        is TurnEvent.SpeechDone -> {serverSpeechFinished=true;if(event.cancelled)stopAudio() else maybeFinishSpeaking()}
        is TurnEvent.Failed -> {serverSpeechFinished=true;stopAudio()}
        else -> Unit
    }}
    private fun absoluteSpeechUrl(url:String)=if(url.startsWith("http://")||url.startsWith("https://"))url else storage.serverAddress.trimEnd('/')+"/"+url.trimStart('/')
    private fun enqueueSpeech(item:SpeechItem){synchronized(speechQueue){speechQueue.addLast(item)};viewModelScope.launch(Dispatchers.Main){playNextIfIdle()}}

    private fun playNextIfIdle(){
        if(player!=null)return
        val next=synchronized(speechQueue){if(speechQueue.isEmpty())null else speechQueue.removeFirst()}
        if(next==null){maybeFinishSpeaking();return}
        try{
            val headers=if(accessKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $accessKey")
            _speaking.value=true
            log("voice ${next.seq}: fetching authenticated audio")
            player=MediaPlayer().apply{
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(getApplication(), android.net.Uri.parse(next.url), headers)
                setOnPreparedListener{it.start();log("voice ${next.seq}: playing")}
                setOnCompletionListener{p->p.release();if(player===p)player=null;playNextIfIdle()}
                setOnErrorListener{p,what,extra->p.release();if(player===p)player=null;log("voice ${next.seq}: MediaPlayer error $what/$extra at ${next.url.take(80)}");playNextIfIdle();true}
                prepareAsync()
            }
        }catch(e:Exception){player=null;_speaking.value=false;log("voice ${next.seq}: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}");playNextIfIdle()}
    }
    private fun maybeFinishSpeaking(){val empty=synchronized(speechQueue){speechQueue.isEmpty()};if(serverSpeechFinished&&empty&&player==null){_speaking.value=false;if(_liveMode.value)requestListening()}}
    private fun stopPlayerOnly(){player?.runCatching{stop()};player?.release();player=null;_speaking.value=false}
    private fun stopAudio(){synchronized(speechQueue){speechQueue.clear()};stopPlayerOnly();serverSpeechFinished=true}

    private fun cleanKey(key:String)=key.filterNot{it.isWhitespace()}
    fun testConnection(address:String,key:String){val checked=ServerAddress.check(address);if(checked is ServerAddress.Check.Bad){_test.value=ConnectionTest(message=checked.reason);return};val url=(checked as ServerAddress.Check.Ok).url;val useKey=cleanKey(key).ifBlank{accessKey};_test.value=ConnectionTest(running=true,message="Testing…");viewModelScope.launch{val result=withContext(Dispatchers.IO){try{val api=GemmaApi(url,{useKey});api.health();val status=api.status();ConnectionTest(ok=true,message="Connected to Gemma on Vision. "+(if(status.modelReady)"Model ready. "else"Model unavailable. ")+(if(status.voiceReady)"Voice ready."else"Voice not running."))}catch(f:GemmaFailure){log("connection test failed: ${f.javaClass.simpleName}");ConnectionTest(message=f.userMessage())}};_test.value=result}}
    fun saveSettings(address:String,key:String):String?{val checked=ServerAddress.check(address);if(checked is ServerAddress.Check.Bad)return checked.reason;val url=(checked as ServerAddress.Check.Ok).url;storage.serverAddress=url;val cleaned=cleanKey(key);if(cleaned.isNotBlank()){storage.accessKey=cleaned;accessKey=cleaned};log("settings saved");controller.configure(url){accessKey};controller.connect();return null}
    override fun onCleared(){stopAudio();super.onCleared()}
}
