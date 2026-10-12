package app.gemma.android

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.LruCache
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gemma.core.ChatController
import gemma.core.ChatMessage
import gemma.core.ChatState
import gemma.core.ConversationInfo
import gemma.core.FileHit
import gemma.core.FileSearch
import gemma.core.FileText
import gemma.core.GemmaApi
import gemma.core.GemmaFailure
import gemma.core.Link
import gemma.core.MemoryPage
import gemma.core.Note
import gemma.core.Reminder
import gemma.core.SearchHit
import gemma.core.ServerAddress
import gemma.core.TurnEvent
import gemma.core.TurnIds
import gemma.core.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** What the settings screen shows after "Test connection". */
data class ConnectionTest(val running: Boolean = false, val ok: Boolean = false, val message: String = "")

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

/** The voice side of the app, as the screens need to see it. */
data class VoiceUi(
    /** A hands-free conversation is running. */
    val live: Boolean = false,
    val phase: Phase = Phase.IDLE,
    /** What the phone has heard Glen say so far. */
    val heard: String = "",
    val muted: Boolean = false,
    /** The bubble whose play button is active. */
    val playingKey: String? = null,
    /** The microphone button in the message box is listening. */
    val dictating: Boolean = false,
    val problem: String? = null,
)

data class SidebarUi(
    val chats: List<ConversationInfo> = emptyList(), val loading: Boolean = false, val error: String? = null,
    val query: String = "", val hits: List<SearchHit>? = null,
)

data class MemoryUi(
    val category: String = "all", val query: String = "", val loading: Boolean = false,
    val page: MemoryPage? = null, val error: String? = null,
)

data class ListsUi(
    val notes: List<Note> = emptyList(), val reminders: List<Reminder> = emptyList(),
    val loading: Boolean = false, val error: String? = null,
)

data class FilesUi(
    val query: String = "", val loading: Boolean = false, val results: FileSearch? = null, val error: String? = null,
    val opening: Boolean = false, val open: FileText? = null,
)

data class TranscribeUi(
    val recording: Boolean = false, val working: Boolean = false, val text: String? = null, val error: String? = null,
)

/**
 * The Android side of Gemma: the phone's settings, its microphone, speaker,
 * camera and notifications. Everything that is Gemma herself (her words, her
 * memory, her voice, her tools) is asked of Vision; nothing of it lives here.
 */
class GemmaViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = Storage(app)
    private val main = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    @Volatile private var accessKey: String = storage.accessKey

    private val _diagnostics = MutableStateFlow<List<String>>(emptyList())
    /** Recent technical events for the developer view. Never contains the access key or message text. */
    val diagnostics: StateFlow<List<String>> = _diagnostics

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(line: String) = _diagnostics.update { (it + "${clock.format(Date())}  $line").takeLast(200) }

    private val controller = ChatController(viewModelScope, log = ::log)
    val chat: StateFlow<ChatState> = controller.state

    private val _prefs = MutableStateFlow(storage.prefs)
    val prefs: StateFlow<Prefs> = _prefs

    private val _draft = MutableStateFlow("")
    /** What Glen has typed and not yet sent. Kept if sending fails. */
    val draft: StateFlow<String> = _draft

    private val _pictures = MutableStateFlow<List<Picture>>(emptyList())
    /** Pictures waiting to go with the next message. */
    val pictures: StateFlow<List<Picture>> = _pictures

    private val _test = MutableStateFlow(ConnectionTest())
    val test: StateFlow<ConnectionTest> = _test

    private val _voice = MutableStateFlow(VoiceUi())
    val voice: StateFlow<VoiceUi> = _voice

    private val _sidebar = MutableStateFlow(SidebarUi())
    val sidebar: StateFlow<SidebarUi> = _sidebar

    private val _memory = MutableStateFlow(MemoryUi())
    val memory: StateFlow<MemoryUi> = _memory

    private val _lists = MutableStateFlow(ListsUi())
    val lists: StateFlow<ListsUi> = _lists

    private val _files = MutableStateFlow(FilesUi())
    val files: StateFlow<FilesUi> = _files

    private val _transcribe = MutableStateFlow(TranscribeUi())
    val transcribe: StateFlow<TranscribeUi> = _transcribe

    val serverAddress: String get() = storage.serverAddress
    val hasAccessKey: Boolean get() = accessKey.isNotBlank()
    val isConfigured: Boolean get() = storage.serverAddress.isNotBlank()
    var askedForNotifications: Boolean
        get() = storage.askedForNotifications
        set(value) { storage.askedForNotifications = value }

    private val whereabouts = Whereabouts(app)
    private val recorder = Recorder(app)

    private val speaker = Speaker(
        app,
        fetch = { path -> (controller.server ?: throw GemmaFailure.Unreachable("not set up")).fetchBytes(path) },
        log = ::log,
    )
    /** The id whose audio is sounding right now, or null. */
    val speaking: StateFlow<String?> = speaker.speaking

    private val listener = Listener(app, ::onPartial, ::onFinal, ::onListenProblem)

    /** Turns for which speech is still expected from Vision. */
    private val awaitingSpeech = ConcurrentHashMap.newKeySet<String>()
    private var playCall: gemma.core.TurnCall? = null
    private var bargeListening = false
    private var quietRounds = 0
    private var foreground = false
    private var noticeJob: Job? = null

    init {
        speaker.speed = _prefs.value.speed
        speaker.onFinished = { id, _ -> onMain { finishedSpeaking(id) } }
        controller.wantsVoice = {
            val p = _prefs.value
            chat.value.voiceReady && p.voiceOutput && (_voice.value.live || p.autoPlay)
        }
        controller.voiceStyle = { _prefs.value.style }
        controller.clientInfo = { whereabouts.client(storage.clientId, _prefs.value.shareLocation) }
        controller.speechListener = ::onSpeechEvent
        VoiceService.onEnd = { onMain { stopLive() } }
        controller.configure(storage.serverAddress.ifBlank { null }) { accessKey }
        if (isConfigured) controller.connect()
        if (_prefs.value.notifications && isConfigured) Notices.schedule(app)
    }

    override fun onCleared() {
        VoiceService.onEnd = null
        listener.cancel()
        speaker.stop()
        recorder.discard()
        VoiceService.stop(getApplication())
    }

    /** One sentence for the person; an old app.py on Vision gets its own explanation. */
    private fun explain(f: GemmaFailure): String =
        if (f is GemmaFailure.Api && f.httpStatus == 404 && f.code == "not_found") "Vision needs the newer app.py for this."
        else f.userMessage()

    /** Ask Vision for something off the main thread, and hand back the answer or one sentence about why not. */
    private fun <T> ask(onError: (String) -> Unit, work: (GemmaApi) -> T, done: (T) -> Unit) {
        val api = controller.server
        if (api == null) {
            onError("Set up Vision's address in Settings first.")
            return
        }
        viewModelScope.launch {
            var failure: GemmaFailure? = null
            val value = withContext(Dispatchers.IO) {
                try {
                    work(api)
                } catch (f: GemmaFailure) {
                    failure = f
                    null
                }
            }
            val f = failure
            if (f != null) {
                log("request failed: ${f.javaClass.simpleName}" + if (f is GemmaFailure.Api) " ${f.code} ${f.httpStatus}" else "")
                onError(explain(f))
            } else if (value != null) done(value)
        }
    }

    // ---- settings ---------------------------------------------------------------------------

    fun setPrefs(p: Prefs) {
        storage.prefs = p
        _prefs.value = p
        speaker.speed = p.speed
        if (!p.voiceOutput) stopSpeaking()
        if (p.notifications && isConfigured) Notices.schedule(getApplication()) else Notices.cancel(getApplication())
        if (p.shareLocation) whereabouts.refresh()
    }

    /** A key never contains spaces or line breaks; pasting often adds them. */
    private fun cleanKey(key: String): String = key.filterNot { it.isWhitespace() }

    /** Try an address and key without saving them. */
    fun testConnection(address: String, key: String) {
        val checked = ServerAddress.check(address)
        if (checked is ServerAddress.Check.Bad) {
            _test.value = ConnectionTest(message = checked.reason)
            return
        }
        val url = (checked as ServerAddress.Check.Ok).url
        val useKey = cleanKey(key).ifBlank { accessKey }
        _test.value = ConnectionTest(running = true, message = "Testing…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val api = GemmaApi(url, { useKey })
                    api.health()
                    val status = api.status()
                    val parts = mutableListOf("Connected to Gemma on Vision.")
                    parts += if (status.modelReady) "Model ready." else "Model unavailable: ${status.modelReason}."
                    parts += if (status.voiceReady) "Voice ready." else "Voice not running."
                    if (status.apiVersion < 2) parts += "Vision is running an older app.py: update it for voice, chats, memory and tools."
                    ConnectionTest(ok = true, message = parts.joinToString(" "))
                } catch (f: GemmaFailure) {
                    log("connection test failed: ${f.javaClass.simpleName}" + if (f is GemmaFailure.Unreachable) " (${f.detail})" else "")
                    ConnectionTest(message = f.userMessage())
                }
            }
            _test.value = result
        }
    }

    /** Save the settings and connect. Returns a sentence if the address is not usable. */
    fun saveSettings(address: String, key: String): String? {
        val checked = ServerAddress.check(address)
        if (checked is ServerAddress.Check.Bad) return checked.reason
        val url = (checked as ServerAddress.Check.Ok).url
        storage.serverAddress = url
        val cleaned = cleanKey(key)
        if (cleaned.isNotBlank()) {
            storage.accessKey = cleaned
            accessKey = cleaned
        }
        log("settings saved (${if (checked.cleartext) "http" else "https"})")
        controller.configure(url) { accessKey }
        controller.connect()
        if (_prefs.value.notifications) Notices.schedule(getApplication())
        return null
    }

    // ---- the conversation -------------------------------------------------------------------

    fun onDraftChange(text: String) {
        _draft.value = text
    }

    fun addToDraft(text: String) {
        val current = _draft.value
        _draft.value = if (current.isBlank()) text else current.trimEnd() + " " + text
    }

    fun send() {
        val text = _draft.value
        val sending = _pictures.value
        val accepted = controller.send(text, images = sending.map { it.dataUrl }) { returned ->
            // The server never received it: put the words back so nothing is lost.
            onMain {
                if (_draft.value.isBlank()) _draft.value = returned
                if (_pictures.value.isEmpty()) _pictures.value = sending
            }
        }
        if (accepted) {
            _draft.value = ""
            _pictures.value = emptyList()
            startedTurn()
        }
    }

    /** Send a ready-made sentence as Glen's message (the Yes / No buttons under a calendar proposal). */
    fun sendNow(text: String) {
        if (controller.send(text) { }) startedTurn()
    }

    private fun startedTurn() {
        if (_prefs.value.shareLocation) whereabouts.refresh()
        val turnId = chat.value.activeTurnId ?: return
        // A reply is coming: whatever she was saying before gives way to it.
        cutSpeech()
        if (controller.wantsVoice()) {
            awaitingSpeech.add(turnId)
            speaker.begin(turnId)
        }
    }

    fun stop() {
        controller.stop()
        cutSpeech()
        if (_voice.value.live) {
            _voice.update { it.copy(phase = Phase.IDLE) }
        }
    }

    fun loadOlder() = controller.loadOlder()
    fun dismissNotice() = controller.dismissNotice()

    fun onForeground() {
        foreground = true
        controller.onForeground()
        if (_prefs.value.shareLocation) whereabouts.refresh()
        if (noticeJob?.isActive != true) {
            noticeJob = viewModelScope.launch {
                while (true) {
                    checkNotices()
                    delay(45_000)
                }
            }
        }
    }

    fun onBackground() {
        foreground = false
        noticeJob?.cancel()
        if (_voice.value.dictating) {
            listener.cancel()
            _voice.update { it.copy(dictating = false) }
        }
    }

    /** While the app is open, Gemma's unprompted messages appear in the conversation rather than as notifications. */
    private suspend fun checkNotices() {
        val api = controller.server ?: return
        if (chat.value.link != Link.CONNECTED || !_prefs.value.notifications) return
        val fresh = withContext(Dispatchers.IO) {
            try {
                Notices.fetchNew(storage, api)
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (fresh.isEmpty()) return
        val here = chat.value.conversationId
        if (fresh.any { it.conversationId == here } && !chat.value.busy) controller.connect()
        fresh.filter { it.conversationId != here }.lastOrNull()?.let { controller.notice("Gemma: " + it.text.take(160)) }
    }

    // ---- pictures ---------------------------------------------------------------------------

    private val imageCache = LruCache<String, Bitmap>(24)

    /** A picture stored with a message on Vision. */
    suspend fun serverImage(path: String): Bitmap? {
        imageCache.get(path)?.let { return it }
        val api = controller.server ?: return null
        return withContext(Dispatchers.IO) {
            try {
                Pictures.decodeBytes(api.fetchBytes(path, 12_000_000))?.also { imageCache.put(path, it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun addPicture(uri: Uri) {
        val limit = 3
        if (_pictures.value.size >= limit) {
            controller.notice("Up to $limit pictures can go with one message.")
            return
        }
        viewModelScope.launch {
            val picture = withContext(Dispatchers.IO) { Pictures.prepare(getApplication(), uri, 6_000_000) }
            if (picture == null) controller.notice("That picture couldn't be read.")
            else _pictures.update { (it + picture).take(limit) }
        }
    }

    fun removePicture(index: Int) = _pictures.update { it.filterIndexed { i, _ -> i != index } }

    /** A private place for the camera to save one photo. */
    fun newPhotoUri(): Uri {
        val app = getApplication<Application>()
        val folder = File(app.cacheDir, "shots").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        return FileProvider.getUriForFile(app, "app.gemma.android.files", File(folder, "photo-${System.currentTimeMillis()}.jpg"))
    }

    /** Text, a link or a picture sent to Gemma from another app. */
    fun takeShared(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { addToDraft(it.take(8000)) }
        @Suppress("DEPRECATION")
        (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { addPicture(it) }
        return true
    }

    // ---- her voice --------------------------------------------------------------------------

    /** Called on a network thread for every speech-related event of this phone's own turns. */
    private fun onSpeechEvent(turnId: String, event: TurnEvent) {
        when (event) {
            is TurnEvent.Speech -> {
                speaker.offer(turnId, event.seq, event.url)
                onMain { if (_voice.value.phase == Phase.THINKING) nowSpeaking() }
            }
            TurnEvent.Reset -> speaker.withdraw(turnId)
            is TurnEvent.SpeechError -> {
                awaitingSpeech.remove(turnId)
                log("turn $turnId: voice unavailable")
            }
            is TurnEvent.SpeechDone -> {
                awaitingSpeech.remove(turnId)
                speaker.end(turnId)
            }
            is TurnEvent.Done -> if (event.result.stopped || !awaitingSpeech.contains(turnId)) {
                // No speech will follow this reply (stopped, or the voice is not running).
                awaitingSpeech.remove(turnId)
                speaker.end(turnId)
                onMain { if (!speaker.isActive) finishedSpeaking(turnId) }
            }
            is TurnEvent.Failed -> {
                awaitingSpeech.remove(turnId)
                speaker.stop()
                onMain {
                    if (_voice.value.live) _voice.update { it.copy(phase = Phase.IDLE, problem = event.failure.userMessage()) }
                }
            }
            else -> {}
        }
    }

    private fun nowSpeaking() {
        _voice.update { it.copy(phase = Phase.SPEAKING) }
        val v = _voice.value
        // With earphones in, the microphone cannot hear her, so Glen can simply talk over her.
        if (v.live && !v.muted && _prefs.value.interrupt && speaker.privateOutput() && listener.available()) {
            bargeListening = true
            speaker.keepThroughShortInterruptions = true
            listener.start()
        }
    }

    private fun finishedSpeaking(id: String) {
        if (playCall?.turnId == id) playCall = null
        val v = _voice.value
        _voice.update { it.copy(playingKey = null) }
        if (!v.live) {
            if (v.phase != Phase.IDLE) _voice.update { it.copy(phase = Phase.IDLE) }
            return
        }
        if (chat.value.busy) return                 // her next words are still on their way
        if (bargeListening && listener.isListening) {
            bargeListening = false
            _voice.update { it.copy(phase = Phase.LISTENING) }
        } else if (!v.muted) listen()
        else _voice.update { it.copy(phase = Phase.IDLE) }
    }

    /** Silence her here and tell Vision to stop making the rest. */
    private fun cutSpeech() {
        playCall?.cancelLocal()
        playCall = null
        val id = speaker.stop()
        if (id != null) controller.stopSpeech(id)
        awaitingSpeech.clear()
        _voice.update { it.copy(playingKey = null) }
    }

    fun stopSpeaking() {
        cutSpeech()
        val v = _voice.value
        if (v.live && !chat.value.busy) {
            if (!v.muted) listen() else _voice.update { it.copy(phase = Phase.IDLE) }
        } else if (!v.live && v.phase == Phase.SPEAKING) _voice.update { it.copy(phase = Phase.IDLE) }
    }

    /** The play button on one of Gemma's messages: Vision speaks that message; tapping again stops it. */
    fun playMessage(message: ChatMessage) {
        val messageId = message.id ?: return
        if (_voice.value.playingKey == message.key) {
            stopSpeaking()
            return
        }
        val api = controller.server ?: return
        cutSpeech()
        val id = "p" + TurnIds.new()
        val call = api.newCall(id)
        playCall = call
        speaker.begin(id)
        _voice.update { it.copy(playingKey = message.key, problem = null) }
        log("message $messageId: play requested")
        viewModelScope.launch(Dispatchers.IO) {
            var problem: String? = null
            try {
                api.streamSpeech(messageId, call, _prefs.value.style) { event ->
                    when (event) {
                        is TurnEvent.Speech -> speaker.offer(id, event.seq, event.url)
                        is TurnEvent.SpeechError -> problem = "Gemma's voice isn't running on Vision."
                        is TurnEvent.Failed -> problem = explain(event.failure)
                        else -> {}
                    }
                }
            } catch (f: GemmaFailure) {
                if (!call.closedLocally) problem = explain(f)
            }
            speaker.end(id)
            val said = problem
            onMain {
                if (said != null) controller.notice(said)
                if (!speaker.isActive && _voice.value.playingKey == message.key) _voice.update { it.copy(playingKey = null) }
            }
        }
    }

    // ---- listening --------------------------------------------------------------------------

    fun canListen(): Boolean = getApplication<Application>().allowed(Manifest.permission.RECORD_AUDIO)

    private fun listen() {
        if (!listener.available()) {
            _voice.update { it.copy(live = false, phase = Phase.IDLE, problem = "This phone has no speech recognition service installed.") }
            VoiceService.stop(getApplication())
            return
        }
        bargeListening = false
        speaker.keepThroughShortInterruptions = false
        _voice.update { it.copy(phase = Phase.LISTENING, heard = "", problem = null) }
        listener.start()
    }

    private fun onPartial(text: String) {
        val v = _voice.value
        if (v.dictating) return
        if (bargeListening && v.phase == Phase.SPEAKING) {
            if (text.trim().length < 4) return
            // Glen is talking over her: she stops, and the phone keeps listening to him.
            bargeListening = false
            speaker.keepThroughShortInterruptions = false
            if (chat.value.busy) controller.stop()
            cutSpeech()
            _voice.update { it.copy(phase = Phase.LISTENING, heard = text) }
            return
        }
        if (v.phase == Phase.LISTENING) _voice.update { it.copy(heard = text) }
    }

    private fun onFinal(text: String) {
        val v = _voice.value
        val said = text.trim()
        if (v.dictating) {
            _voice.update { it.copy(dictating = false) }
            if (said.isNotEmpty()) addToDraft(said)
            return
        }
        if (!v.live) return
        if (v.phase == Phase.SPEAKING) {
            // Listening for an interruption ended without one: keep an ear open while she is still talking.
            if (bargeListening && speaker.isActive && !v.muted) listener.start()
            return
        }
        if (said.isEmpty()) {
            heardNothing()
            return
        }
        quietRounds = 0
        _voice.update { it.copy(phase = Phase.THINKING, heard = said) }
        val accepted = controller.send(said, source = "voice") { }
        if (accepted) startedTurn()
        else _voice.update { it.copy(phase = Phase.IDLE, problem = "That couldn't be sent. Check the connection to Vision.") }
    }

    private fun heardNothing() {
        quietRounds++
        if (quietRounds >= 2) {
            // Nobody is talking: stop listening rather than run the microphone for nothing.
            _voice.update { it.copy(phase = Phase.IDLE, heard = "") }
        } else listen()
    }

    private fun onListenProblem(code: Int) {
        val v = _voice.value
        val silence = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (v.dictating) {
            _voice.update { it.copy(dictating = false) }
            if (!silence) controller.notice(listenProblem(code))
            return
        }
        if (!v.live) return
        if (v.phase == Phase.SPEAKING) {
            if (bargeListening && silence && speaker.isActive && !v.muted) listener.start() else bargeListening = false
            return
        }
        if (silence) heardNothing()
        else _voice.update { it.copy(phase = Phase.IDLE, problem = listenProblem(code)) }
    }

    private fun listenProblem(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The microphone isn't allowed for this app."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "The phone's speech recognition couldn't reach its service."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone is in use by something else."
        else -> "The phone's speech recognition stopped. Tap to try again."
    }

    /** The microphone in the message box: one spoken sentence, put into the box to check before sending. */
    fun dictate() {
        val v = _voice.value
        if (v.live) return
        if (v.dictating) {
            listener.finish()
            return
        }
        if (!listener.available()) {
            controller.notice("This phone has no speech recognition service installed.")
            return
        }
        _voice.update { it.copy(dictating = true) }
        listener.start()
    }

    /** Hands-free conversation: listen, send, hear her answer, listen again. */
    fun startLive() {
        if (_voice.value.live) return
        if (_voice.value.dictating) listener.cancel()
        quietRounds = 0
        _voice.update { VoiceUi(live = true, muted = false) }
        VoiceService.start(getApplication())
        if (chat.value.busy) _voice.update { it.copy(phase = Phase.THINKING) } else listen()
    }

    fun stopLive() {
        if (!_voice.value.live) return
        listener.cancel()
        bargeListening = false
        speaker.keepThroughShortInterruptions = false
        if (chat.value.busy) controller.stop()
        cutSpeech()
        _voice.value = VoiceUi()
        VoiceService.stop(getApplication())
    }

    /** Tap while she is speaking (or thinking): she stops and the phone listens. Tap while idle: listen again. */
    fun tapRing() {
        val v = _voice.value
        if (!v.live) return
        when (v.phase) {
            Phase.SPEAKING, Phase.THINKING -> {
                listener.cancel()
                if (chat.value.busy) controller.stop()
                cutSpeech()
                quietRounds = 0
                if (!v.muted) listen() else _voice.update { it.copy(phase = Phase.IDLE) }
            }
            Phase.IDLE -> if (!v.muted) {
                quietRounds = 0
                listen()
            }
            Phase.LISTENING -> listener.finish()
        }
    }

    fun toggleMute() {
        val v = _voice.value
        if (!v.live) return
        if (!v.muted) {
            listener.cancel()
            bargeListening = false
            _voice.update { it.copy(muted = true, phase = if (it.phase == Phase.LISTENING) Phase.IDLE else it.phase) }
        } else {
            _voice.update { it.copy(muted = false) }
            if (v.phase == Phase.IDLE && !chat.value.busy) {
                quietRounds = 0
                listen()
            }
        }
    }

    fun clearVoiceProblem() = _voice.update { it.copy(problem = null) }

    // ---- the list of chats ------------------------------------------------------------------

    fun refreshChats() {
        _sidebar.update { it.copy(loading = true, error = null) }
        ask({ e -> _sidebar.update { it.copy(loading = false, error = e) } }, { it.conversations() }) { list ->
            _sidebar.update { it.copy(loading = false, chats = list) }
        }
    }

    fun openChat(id: Long, title: String): Boolean {
        cutSpeech()
        val opened = controller.openConversation(id, title)
        if (!opened) controller.notice("Wait for Gemma to finish, or press Stop, before changing chats.")
        return opened
    }

    fun newChat(done: () -> Unit) {
        if (chat.value.busy) {
            controller.notice("Wait for Gemma to finish, or press Stop, before starting a new chat.")
            return
        }
        ask({ controller.notice(it) }, { it.newConversation() }) { c ->
            controller.openConversation(c.id, c.title)
            refreshChats()
            done()
        }
    }

    fun renameChat(id: Long, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        ask({ controller.notice(it) }, { it.renameConversation(id, clean) }) { refreshChats() }
    }

    fun deleteChat(id: Long) {
        ask({ controller.notice(it) }, { it.deleteConversation(id) }) {
            if (chat.value.conversationId == id) controller.openConversation(null)
            refreshChats()
        }
    }

    fun searchChats(query: String) {
        _sidebar.update { it.copy(query = query, hits = if (query.trim().length < 2) null else it.hits) }
        val q = query.trim()
        if (q.length < 2) return
        ask({ e -> _sidebar.update { it.copy(error = e) } }, { it.searchConversations(q) }) { hits ->
            if (_sidebar.value.query.trim() == q) _sidebar.update { it.copy(hits = hits, error = null) }
        }
    }

    // ---- what she remembers -----------------------------------------------------------------

    fun loadMemory(category: String = _memory.value.category, query: String = _memory.value.query) {
        _memory.update { it.copy(category = category, query = query, loading = true, error = null) }
        ask({ e -> _memory.update { it.copy(loading = false, error = e) } }, { it.memory(category, query.trim()) }) { page ->
            val now = _memory.value
            if (now.category == category && now.query == query) _memory.update { it.copy(loading = false, page = page) }
        }
    }

    fun forget(id: Long) {
        ask({ controller.notice(it) }, { it.deleteMemory(id) }) { loadMemory() }
    }

    // ---- tools ------------------------------------------------------------------------------

    fun loadLists() {
        _lists.update { it.copy(loading = true, error = null) }
        ask({ e -> _lists.update { it.copy(loading = false, error = e) } }, { it.notes() to it.reminders() }) { (notes, reminders) ->
            _lists.value = ListsUi(notes, reminders)
        }
    }

    fun searchFiles(query: String) {
        val q = query.trim()
        _files.update { it.copy(query = query) }
        if (q.length < 2) return
        _files.update { it.copy(loading = true, error = null) }
        ask({ e -> _files.update { it.copy(loading = false, error = e) } }, { it.searchFiles(q) }) { found ->
            _files.update { it.copy(loading = false, results = found) }
        }
    }

    fun openFile(hit: FileHit) {
        _files.update { it.copy(opening = true) }
        ask({ e -> _files.update { it.copy(opening = false, error = e) } }, { it.fileText(hit.path) }) { text ->
            _files.update { it.copy(opening = false, open = text) }
        }
    }

    fun closeFile() = _files.update { it.copy(open = null) }

    fun startRecording() {
        if (_voice.value.live) {
            _transcribe.update { it.copy(error = "End the voice conversation first.") }
            return
        }
        if (recorder.start()) _transcribe.value = TranscribeUi(recording = true)
        else _transcribe.value = TranscribeUi(error = "The microphone couldn't be started.")
    }

    fun finishRecording() {
        val audio = recorder.finish()
        if (audio == null || audio.isEmpty()) {
            _transcribe.value = TranscribeUi(error = "Nothing was recorded.")
            return
        }
        sendForTranscription(audio, "audio/mp4")
    }

    fun transcribeFile(uri: Uri) {
        val app = getApplication<Application>()
        _transcribe.value = TranscribeUi(working = true)
        viewModelScope.launch {
            val audio = withContext(Dispatchers.IO) {
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            if (out.size() > 60_000_000) return@use null
                        }
                        out.toByteArray()
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (audio == null) _transcribe.value = TranscribeUi(error = "That file couldn't be read, or is larger than 60 MB.")
            else sendForTranscription(audio, app.contentResolver.getType(uri) ?: "application/octet-stream")
        }
    }

    private fun sendForTranscription(audio: ByteArray, mime: String) {
        _transcribe.value = TranscribeUi(working = true)
        ask({ e -> _transcribe.value = TranscribeUi(error = e) }, { it.transcribe(audio, mime) }) { result ->
            _transcribe.value = TranscribeUi(text = result.text.ifBlank { "(No speech was found in that recording.)" })
        }
    }

    fun clearTranscript() {
        recorder.discard()
        _transcribe.value = TranscribeUi()
    }
}
