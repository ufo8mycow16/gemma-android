package app.gemma.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gemma.core.ChatController
import gemma.core.ChatState
import gemma.core.GemmaApi
import gemma.core.GemmaFailure
import gemma.core.ServerAddress
import gemma.core.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the settings screen shows after "Test connection". */
data class ConnectionTest(val running: Boolean = false, val ok: Boolean = false, val message: String = "")

/**
 * The Android side of the conversation: it owns the phone's settings and hands
 * everything else to ChatController. No Gemma logic lives here.
 */
class GemmaViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = Storage(app)

    @Volatile private var accessKey: String = storage.accessKey

    private val _diagnostics = MutableStateFlow<List<String>>(emptyList())
    /** Recent technical events for the developer view. Never contains the access key or message text. */
    val diagnostics: StateFlow<List<String>> = _diagnostics

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun log(line: String) = _diagnostics.update { (it + "${clock.format(Date())}  $line").takeLast(200) }

    private val controller = ChatController(viewModelScope, log = ::log)
    val chat: StateFlow<ChatState> = controller.state

    private val _draft = MutableStateFlow("")
    /** What Glen has typed and not yet sent. Kept if sending fails. */
    val draft: StateFlow<String> = _draft

    private val _test = MutableStateFlow(ConnectionTest())
    val test: StateFlow<ConnectionTest> = _test

    private val _developerMode = MutableStateFlow(storage.developerMode)
    val developerMode: StateFlow<Boolean> = _developerMode

    val serverAddress: String get() = storage.serverAddress
    val hasAccessKey: Boolean get() = accessKey.isNotBlank()
    val isConfigured: Boolean get() = storage.serverAddress.isNotBlank()

    init {
        controller.configure(storage.serverAddress.ifBlank { null }) { accessKey }
        if (isConfigured) controller.connect()
    }

    fun onDraftChange(text: String) {
        _draft.value = text
    }

    fun send() {
        val text = _draft.value
        val accepted = controller.send(text) { returned ->
            // The server never received it: put the words back so nothing is lost.
            if (_draft.value.isBlank()) _draft.value = returned
        }
        if (accepted) _draft.value = ""
    }

    fun stop() = controller.stop()
    fun loadOlder() = controller.loadOlder()
    fun onForeground() = controller.onForeground()
    fun dismissNotice() = controller.dismissNotice()

    fun setDeveloperMode(on: Boolean) {
        storage.developerMode = on
        _developerMode.value = on
    }

    /** Try an address and key without saving them. */
    fun testConnection(address: String, key: String) {
        val checked = ServerAddress.check(address)
        if (checked is ServerAddress.Check.Bad) {
            _test.value = ConnectionTest(message = checked.reason)
            return
        }
        val url = (checked as ServerAddress.Check.Ok).url
        val useKey = key.ifBlank { accessKey }
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
        if (key.isNotBlank()) {
            storage.accessKey = key.trim()
            accessKey = key.trim()
        }
        log("settings saved (${if (checked.cleartext) "http" else "https"})")
        controller.configure(url) { accessKey }
        controller.connect()
        return null
    }
}
