package gemma.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections

/**
 * The client talking to a real Gemma server.
 *   GEMMA_TEST_URL   for example http://127.0.0.1:7860
 *   GEMMA_TEST_KEY   an access key for that server
 * With GEMMA_TEST_LONG set to a prompt that produces a long reply, the stop and
 * reconnect checks run too. Every turn it sends is a real turn in the server's
 * primary conversation, so point it at a test server or expect a few test messages.
 */
object IntegrationChecks {
    private fun check(condition: Boolean, what: () -> String) {
        if (!condition) throw AssertionError("FAILED: " + what())
    }

    private suspend fun until(seconds: Int, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("TIMED OUT after ${seconds}s waiting for: $what")
            delay(40)
        }
    }

    fun run(url: String, key: String, longPrompt: String?): List<String> = runBlocking {
        val done = ArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val log = Collections.synchronizedList(ArrayList<String>())
        try {
            // 1. reachability and identity, without a key
            check(GemmaApi(url, { null }).health() == SUPPORTED_API_VERSION) { "health reports API v$SUPPORTED_API_VERSION" }
            val dead = try { GemmaApi("http://127.0.0.1:9", { key }, connectTimeoutMs = 1500).health(); null } catch (f: GemmaFailure) { f }
            check(dead is GemmaFailure.Unreachable && dead.userMessage() == "Can't reach Vision.") { "a dead address is 'Can't reach Vision.', got $dead" }
            done += "health check works without a key; an unreachable address is reported plainly"

            // 2. a wrong key is its own state
            val locked = ChatController(scope, log = { log.add(it) })
            locked.configure(url, { "wrong-key-wrong-key-wrong" })
            locked.connect().join()
            check(locked.state.value.link == Link.AUTH_REQUIRED && locked.state.value.statusLine == "Authentication required") { "wrong key -> ${locked.state.value.link}" }
            check(!locked.send("hello")) { "nothing can be sent without a conversation" }
            done += "a wrong access key shows 'Authentication required', not 'disconnected'"

            // 3. connect and load the existing conversation
            val chat = ChatController(scope, log = { log.add(it) }, retryDelayMs = 400)
            chat.configure(url, { key })
            chat.connect().join()
            var s = chat.state.value
            check(s.link == Link.CONNECTED && s.conversationId != null) { "connected: ${s.link} ${s.notice}" }
            check(s.modelReady && s.statusLine == "Connected") { "status line '${s.statusLine}'" }
            val historyBefore = s.messages.size
            check(historyBefore > 0 && s.messages.all { it.key.startsWith("m") }) { "existing history is loaded from the server ($historyBefore messages)" }
            done += "connected, found the primary conversation \"${s.conversationTitle}\" with $historyBefore earlier messages"

            // 4. send a message and watch the reply stream in
            val seen = Collections.synchronizedSet(LinkedHashSet<String>())
            val watcher = scope.launch { chat.state.collect { st -> st.messages.lastOrNull()?.takeIf { it.streaming && it.text.isNotEmpty() }?.let { seen.add(it.text) } } }
            check(chat.send("Hello Gem.")) { "send accepted" }
            check(!chat.send("Second message while busy")) { "a second send while busy is refused" }
            until(60, "the reply to finish") { !chat.state.value.busy }
            delay(300)
            s = chat.state.value
            val reply = s.messages.last()
            check(reply.role == "assistant" && reply.text.isNotBlank() && !reply.streaming) { "a reply arrived: $reply" }
            check(seen.size >= 1) { "the reply was visible while still being written (saw ${seen.size} partial states)" }
            check(s.messages.size == historyBefore + 2 && s.messages.all { it.key.startsWith("m") }) { "after the turn the screen shows the server's stored messages (${s.messages.size} vs ${historyBefore + 2})" }
            check(s.messages[s.messages.size - 2].text == "Hello Gem.") { "the sent message is stored on the server" }
            watcher.cancel()
            done += "sent \"Hello Gem.\" and received the reply: \"${reply.text.take(60)}\""

            if (longPrompt != null) {
                // 5. Stop cancels the real turn on the server
                check(chat.send(longPrompt)) { "long send accepted" }
                until(40, "some of the long reply") { chat.state.value.messages.last().text.split(" ").size > 5 }
                val partial = chat.state.value
                check(partial.busy && partial.messages.last().streaming) { "text is on screen while Gemma is still writing" }
                val turnId = partial.activeTurnId!!
                chat.stop()
                until(30, "the turn to stop") { !chat.state.value.busy }
                val info = GemmaApi(url, { key }).turnInfo(turnId)
                check(info.state == TurnState.STOPPED) { "the server reports the turn as stopped: ${info.state}" }
                delay(300)
                val words = chat.state.value.messages.last().text.split(" ").filter { it.isNotBlank() }.size
                check(words in 5..60) { "the stopped reply holds only what was generated ($words words)" }
                done += "the long reply streamed onto the screen while being written; Stop cancelled the turn on the server after $words words"

                // 6. a second client in the same conversation is refused while Gemma is answering
                val other = ChatController(scope)
                other.configure(url, { key })
                other.connect().join()
                check(chat.send(longPrompt)) { "third send accepted" }
                until(40, "the turn to start") { chat.state.value.messages.last().text.isNotEmpty() }
                var returned: String? = null
                check(other.send("Me too")) { "second client send attempted" }
                until(20, "the refusal") { !other.state.value.busy }
                check(other.state.value.notice == "Gemma is already answering in this conversation.") { "second client told why: ${other.state.value.notice}" }
                other.connect().join()
                check(other.state.value.activity == "Gemma is answering on another device") { "second client can see a turn is running: '${other.state.value.activity}'" }

                // 7. the connection drops mid-turn: the turn is recovered by id and never sent twice
                chat.simulateConnectionLoss()
                until(10, "the drop to be noticed") { chat.state.value.activity.contains("Reconnecting") || chat.state.value.activity.contains("still answering") }
                until(90, "the turn to be recovered") { !chat.state.value.busy }
                delay(300)
                s = chat.state.value
                val recovered = s.messages.last().text.split(" ").filter { it.isNotBlank() }.size
                check(recovered >= 60) { "the full reply was recovered after the drop ($recovered words)" }
                val copies = GemmaApi(url, { key }).messages(s.conversationId!!, limit = 200).messages.count { it.role == "user" && it.text == longPrompt }
                check(copies == 2) { "the long prompt was sent twice and is stored twice: the reconnect did not send it again ($copies)" }
                check(GemmaApi(url, { key }).messages(s.conversationId!!, limit = 200).messages.none { it.text == "Me too" }) { "the refused message was not stored" }
                done += "a dropped connection mid-turn was recovered by turn id ($recovered words), with no duplicate message"

                // 8. the server becomes unreachable: the message comes back instead of vanishing
                chat.configure("http://127.0.0.1:9", { key })
                check(chat.send("This cannot be delivered") { returned = it }) { "send attempted" }
                until(20, "the failure") { !chat.state.value.busy }
                s = chat.state.value
                check(returned == "This cannot be delivered" && s.link == Link.UNREACHABLE && s.statusLine == "Vision unavailable") { "unreachable: returned=$returned link=${s.link}" }
                check(s.messages.none { it.text == "This cannot be delivered" } && s.notice!!.contains("wasn't sent")) { "no phantom bubble; notice: ${s.notice}" }
                chat.configure(url, { key }); chat.onForeground()
                until(20, "reconnection") { chat.state.value.link == Link.CONNECTED }
                done += "with Vision unreachable the unsent text was handed back; it reconnected when Vision returned"
            }
            check(log.none { it.contains(key) }) { "the access key never appears in diagnostics" }
            check(log.none { it.contains("Hello Gem") }) { "message text never appears in diagnostics" }
            done += "diagnostics contain no access key and no message text (${log.size} lines)"
            done
        } finally {
            scope.cancel()
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val url = System.getenv("GEMMA_TEST_URL") ?: error("set GEMMA_TEST_URL")
        val key = System.getenv("GEMMA_TEST_KEY") ?: error("set GEMMA_TEST_KEY")
        run(url, key, System.getenv("GEMMA_TEST_LONG")).forEach { println("OK  $it") }
        println("INTEGRATION OK")
    }
}
