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
import java.util.Date
import java.util.Locale

/** What the settings screen shows after Test connection. */
data class ConnectionTest(val running: Boolean = false, val ok: Boolean = false, val message: String = "")

class GemmaViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = Storage(app)
    @Volatile private var accessKey: String = storage.accessKey
    private val _diagnostics = MutableStateFlow<List<String>>(emptyList())
    val diagnostics: StateFlow<List<String>> = _diagnostics
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(line: String) = _diagnostics.update { (it + "${clock.format(Date())}  $line").takeLast(200) }

    private val _voiceEnabled = MutableStateFlow(storage.voiceEnabled)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled
    private var player: MediaPlayer? = null
    private val speech = SpeechQueue { turn, seq, url -> playSpeech(turn, seq, url) }

    private val controller = ChatController(
        viewModelScope,
        log = ::log,
        voiceEnabled = { _voiceEnabled.value },
        turnEvent = { turn, event -> onTurnEvent(turn, event) },
    )
    val chat: StateFlow<ChatState> = controller.state
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft
    private val _test = MutableStateFlow(ConnectionTest())
    val test: StateFlow<ConnectionTest> = _test
    private val _developerMode = MutableStateFlow(storage.developerMode)
    val developerMode: StateFlow<Boolean> = _developerMode

    val serverAddress: String get() = storage.serverAddress
    val hasAccessKey: Boolean get() = accessKey.isNotBlank()
    val isConfigured: Boolean get() = storage.serverAddress.isNotBlank()

    init { controller.configure(storage.serverAddress.ifBlank { null }) { accessKey }; if (isConfigured) controller.connect() }
    fun onDraftChange(text: String) { _draft.value = text }
    fun send() { val text = _draft.value; val accepted = controller.send(text) { if (_draft.value.isBlank()) _draft.value = it }; if (accepted) _draft.value = "" }
    fun stop() { stopAudio(); controller.stop() }
    fun loadOlder() = controller.loadOlder()
    fun onForeground() = controller.onForeground()
    fun dismissNotice() = controller.dismissNotice()
    fun setDeveloperMode(on: Boolean) { storage.developerMode = on; _developerMode.value = on }
    fun setVoiceEnabled(on: Boolean) { storage.voiceEnabled = on; _voiceEnabled.value = on; if (!on) stopAudio() }

    private fun onTurnEvent(turn: String, event: TurnEvent) {
        when (event) {
            is TurnEvent.Started -> if (_voiceEnabled.value) speech.begin(turn)
            is TurnEvent.Speech -> if (_voiceEnabled.value) speech.offer(turn, event.seq, absoluteSpeechUrl(event.url))
            TurnEvent.Reset -> speech.reset(turn)
            is TurnEvent.SpeechDone -> if (event.cancelled) { speech.stop(turn); stopAudio() }
            is TurnEvent.Failed -> { speech.stop(turn); stopAudio() }
            else -> Unit
        }
    }

    private fun absoluteSpeechUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return storage.serverAddress.trimEnd('/') + "/" + url.trimStart('/')
    }

    private fun playSpeech(turn: String, seq: Int, url: String) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                player?.release()
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(url)
                    setOnPreparedListener { it.start(); log("voice $turn/$seq playing") }
                    setOnCompletionListener { p -> p.release(); if (player === p) player = null }
                    setOnErrorListener { p, _, _ -> p.release(); if (player === p) player = null; log("voice $turn/$seq failed"); true }
                    prepareAsync()
                }
            } catch (e: Exception) { log("voice $turn/$seq failed: ${e.javaClass.simpleName}") }
        }
    }

    private fun stopAudio() { player?.runCatching { stop() }; player?.release(); player = null }
    private fun cleanKey(key: String): String = key.filterNot { it.isWhitespace() }

    fun testConnection(address: String, key: String) {
        val checked = ServerAddress.check(address); if (checked is ServerAddress.Check.Bad) { _test.value = ConnectionTest(message = checked.reason); return }
        val url = (checked as ServerAddress.Check.Ok).url; val useKey = cleanKey(key).ifBlank { accessKey }; _test.value = ConnectionTest(running = true, message = "Testing…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { val api = GemmaApi(url, { useKey }); api.health(); val status = api.status(); ConnectionTest(ok = true, message = "Connected to Gemma on Vision. " + (if (status.modelReady) "Model ready. " else "Model unavailable. ") + if (status.voiceReady) "Voice ready." else "Voice not running.") }
                catch (f: GemmaFailure) { log("connection test failed: ${f.javaClass.simpleName}"); ConnectionTest(message = f.userMessage()) }
            }; _test.value = result
        }
    }

    fun saveSettings(address: String, key: String): String? {
        val checked = ServerAddress.check(address); if (checked is ServerAddress.Check.Bad) return checked.reason
        val url = (checked as ServerAddress.Check.Ok).url; storage.serverAddress = url; val cleaned = cleanKey(key)
        if (cleaned.isNotBlank()) { storage.accessKey = cleaned; accessKey = cleaned }
        log("settings saved (${if (checked.cleartext) "http" else "https"})"); controller.configure(url) { accessKey }; controller.connect(); return null
    }

    override fun onCleared() { stopAudio(); super.onCleared() }
}
