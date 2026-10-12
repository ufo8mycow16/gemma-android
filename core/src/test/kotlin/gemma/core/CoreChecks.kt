package gemma.core

/**
 * Checks of the client's logic that need no server and no Android.
 * Framework-free so they run anywhere: CoreChecks.runAll() throws on the first failure.
 */
object CoreChecks {
    private var count = 0
    private fun check(condition: Boolean, what: () -> String) {
        count++
        if (!condition) throw AssertionError("FAILED: " + what())
    }
    private fun <T> eq(actual: T, expected: T, what: String) = check(actual == expected) { "$what\n  expected: $expected\n  actual:   $actual" }

    fun runAll(): Int {
        count = 0
        events(); addresses(); reducerHappyPath(); reducerStopAndFailure(); reducerHistory(); speechQueue(); turnIds(); messages(); codeBlocks(); highlighting(); cards(); docProgress(); relativeTimes()
        return count
    }

    private fun events() {
        eq(Events.parse("""{"type":"start","turn_id":"abc12345","conversation_id":3,"api_version":1}"""), TurnEvent.Started("abc12345", 3, 1), "start event")
        eq(Events.parse("""{"type":"delta","text":"Hel"}"""), TurnEvent.Delta("Hel"), "delta event")
        eq(Events.parse("""{"type":"status","stage":"web","label":"Searching the web: phasers"}"""), TurnEvent.Status("web", "Searching the web: phasers"), "status event")
        check(Events.parse("""{"type":"reset"}""") === TurnEvent.Reset) { "reset event" }
        check(Events.parse("""{"type":"ping"}""") === TurnEvent.Ping) { "ping event" }
        eq(Events.parse("""{"type":"speech","seq":2,"seconds":3.5,"url":"/api/speech/t/2","spoken":"Oh! Hi. [chuckle]","performed":true}"""),
            TurnEvent.Speech(2, "/api/speech/t/2", 3.5), "speech event carries no performance text")
        val done = Events.parse("""{"type":"done","reply":"Hello Glen.","research_log":null,"stopped":false,"turn_id":"abc12345","environment":{"x":1},"http_status":200}""", "zzz") as TurnEvent.Done
        eq(done.result, TurnResult("abc12345", "Hello Glen.", null, stopped = false, duplicate = false), "done event")
        val stopped = Events.parse("""{"type":"done","reply":"One two","research_log":null,"stopped":true}""", "tid00001") as TurnEvent.Done
        check(stopped.result.stopped && stopped.result.turnId == "tid00001") { "stopped turn keeps the id it was sent with" }
        val failed = Events.parse("""{"type":"error","error":"Gemma is already answering in this conversation.","code":"turn_active","active_turn_id":"other001","http_status":409}""") as TurnEvent.Failed
        eq(failed.failure.code to failed.failure.httpStatus, "turn_active" to 409, "error event code")
        eq(failed.failure.activeTurnId, "other001", "error event active turn")
        eq(failed.failure.userMessage(), "Gemma is already answering in this conversation.", "turn_active wording")
        eq(Events.parse("""{"type":"something_new","x":1}"""), TurnEvent.Unknown("something_new"), "unknown event types are tolerated")
        for (junk in listOf("", "   ", "not json", "[1,2]", "{\"no_type\":1}", "{\"type\":5}", "{")) eq(Events.parse(junk), null, "junk line '$junk'")
        eq((Events.parse("""{"type":"error","error":"boom"}""") as TurnEvent.Failed).failure.code, "internal_error", "error without a code")
        eq(GemmaFailure.Unreachable("java.net.ConnectException: refused").userMessage(), "Can't reach Vision.", "unreachable wording has no Java in it")
        eq(GemmaFailure.Api("model_unavailable", "Ollama error: ...", 503).userMessage(), "Gemma's model isn't running on Vision.", "model wording")
        check(!GemmaFailure.AuthRequired().userMessage().contains("401")) { "auth wording" }
    }

    private fun addresses() {
        eq(ServerAddress.check(" 192.168.1.20:7860 "), ServerAddress.Check.Ok("http://192.168.1.20:7860", true), "bare ip and port")
        eq(ServerAddress.check("http://vision.local:7860/"), ServerAddress.Check.Ok("http://vision.local:7860", true), "trailing slash")
        eq(ServerAddress.check("https://vision.tail1234.ts.net"), ServerAddress.Check.Ok("https://vision.tail1234.ts.net", false), "https")
        eq(ServerAddress.check("HTTPS://Vision.example/gemma/"), ServerAddress.Check.Ok("https://Vision.example/gemma", false), "path prefix kept")
        for (bad in listOf("", "   ", "ftp://vision", "http://", "http://glen:secret@vision:7860", "http://vision:7860/?key=abc", "http://vision:7860/#x", "http://exa mple.com", "javascript:alert(1)"))
            check(ServerAddress.check(bad) is ServerAddress.Check.Bad) { "address '$bad' should be rejected" }
    }

    private fun connected(): ChatState {
        val status = ServerStatus(1, "gemma4:12b", true, "", false, 7, emptyMap(), 3, 8_000_000)
        var s = ChatReducer.reduce(ChatState(), ChatAction.LinkChanged(Link.CONNECTING))
        eq(s.statusLine, "Connecting…", "connecting line")
        s = ChatReducer.reduce(s, ChatAction.StatusKnown(status))
        val page = HistoryPage(7, listOf(Message(1, "user", "Hi", null, emptyList(), ""), Message(2, "assistant", "Hello Glen.", null, emptyList(), "")), false, null)
        return ChatReducer.reduce(s, ChatAction.HistoryLoaded(Conversation(7, "Chat with Glen", true), page))
    }

    private fun reducerHappyPath() {
        var s = connected()
        eq(s.statusLine, "Connected", "connected line")
        eq(s.messages.map { it.key }, listOf("m1", "m2"), "history keys")
        s = ChatReducer.reduce(s, ChatAction.SendStarted("turn0001", "Hello Gem."))
        check(s.busy && s.messages.size == 4 && s.messages[3].streaming && s.messages[2].text == "Hello Gem.") { "send adds the message and an empty reply" }
        check(ChatReducer.reduce(s, ChatAction.SendStarted("turn0002", "Again")) === s) { "a second send while busy changes nothing" }
        fun ev(e: TurnEvent) { s = ChatReducer.reduce(s, ChatAction.Event("turn0001", e)) }
        ev(TurnEvent.Started("turn0001", 7, 1)); eq(s.activity, "Thinking…", "activity after start")
        ev(TurnEvent.Status("web", "Searching the web: tricorder")); eq(s.activity, "Searching the web: tricorder", "activity is the server's own label")
        ev(TurnEvent.Delta("Let me ")); ev(TurnEvent.Delta("check."))
        eq(s.messages.last().text, "Let me check.", "deltas accumulate"); eq(s.activity, "", "activity clears once text flows")
        ev(TurnEvent.Reset); eq(s.messages.last().text, "", "reset withdraws shown text")
        ev(TurnEvent.Delta("Wah Chang ")); ev(TurnEvent.Ping); ev(TurnEvent.Unknown("future")); ev(TurnEvent.Speech(0, "/x", 1.0))
        eq(s.messages.last().text, "Wah Chang ", "pings, unknown events and speech never touch the text")
        val stale = ChatReducer.reduce(s, ChatAction.Event("old-turn", TurnEvent.Delta("LATE")))
        check(stale === s) { "events from another turn are ignored" }
        ev(TurnEvent.Done(TurnResult("turn0001", "Wah Chang built it.", "────\nResearch log", stopped = false, duplicate = false)))
        check(!s.busy && s.activity == "") { "done ends the turn" }
        eq(s.messages.last().text, "Wah Chang built it.", "the server's final text wins over the pieces")
        eq(s.messages.last().researchLog, "────\nResearch log", "research log is kept apart from the text")
        check(!s.messages.last().streaming) { "reply no longer streaming" }
    }

    private fun reducerStopAndFailure() {
        var s = ChatReducer.reduce(connected(), ChatAction.SendStarted("turn0003", "Count."))
        s = ChatReducer.reduce(s, ChatAction.Event("turn0003", TurnEvent.Delta("One two")))
        s = ChatReducer.reduce(s, ChatAction.StopRequested("turn0003"))
        check(s.stopping && s.busy && s.activity == "Stopping…") { "stop is shown as stopping until the server confirms" }
        s = ChatReducer.reduce(s, ChatAction.Event("turn0003", TurnEvent.Status("writing", "Writing")))
        eq(s.activity, "Stopping…", "status labels do not override stopping")
        s = ChatReducer.reduce(s, ChatAction.Event("turn0003", TurnEvent.Done(TurnResult("turn0003", "One two", null, stopped = true, duplicate = false))))
        check(!s.busy && !s.stopping && s.messages.last().stopped && s.messages.last().text == "One two") { "stopped reply keeps what was generated" }

        s = ChatReducer.reduce(connected(), ChatAction.SendStarted("turn0004", "Hi"))
        s = ChatReducer.reduce(s, ChatAction.Event("turn0004", TurnEvent.Failed(GemmaFailure.Api("model_unavailable", "x", 503))))
        check(!s.busy && s.notice == "Gemma's model isn't running on Vision.") { "failure ends the turn with a plain notice" }
        eq(s.messages.map { it.key }, listOf("m1", "m2", "tturn0004:u"), "the empty reply bubble is removed on failure")
        eq(s.messages.last().problem, "Not answered", "the message is marked")

        s = ChatReducer.reduce(connected(), ChatAction.SendStarted("turn0005", "Hi"))
        s = ChatReducer.reduce(s, ChatAction.TurnLost("turn0005", null, "Can't reach Vision. Your message wasn't sent.", sent = false))
        eq(s.messages.map { it.key }, listOf("m1", "m2"), "a message that never left the phone leaves no bubble")
        check(!s.busy && s.notice!!.startsWith("Can't reach Vision.")) { "and says why" }

        s = ChatReducer.reduce(connected(), ChatAction.SendStarted("turn0006", "Hi"))
        s = ChatReducer.reduce(s, ChatAction.Event("turn0006", TurnEvent.Delta("Partial rep")))
        s = ChatReducer.reduce(s, ChatAction.TurnLost("turn0006", "Sent, but the reply didn't arrive", "dropped"))
        eq(s.messages.takeLast(2).map { it.text to it.problem }, listOf("Hi" to "Sent, but the reply didn't arrive", "Partial rep" to null), "a dropped turn keeps what arrived")
        eq(ChatReducer.reduce(s, ChatAction.LinkChanged(Link.UNREACHABLE)).statusLine, "Vision unavailable", "unreachable line")
        eq(ChatReducer.reduce(s, ChatAction.LinkChanged(Link.AUTH_REQUIRED)).statusLine, "Authentication required", "auth line")
        eq(ChatState().statusLine, "Not set up", "initial line")
        eq(ChatReducer.reduce(s, ChatAction.NoticeDismissed).notice, null, "notice dismissed")
    }

    private fun reducerHistory() {
        var s = ChatReducer.reduce(connected(), ChatAction.SendStarted("turn0007", "Mid-turn"))
        s = ChatReducer.reduce(s, ChatAction.Event("turn0007", TurnEvent.Delta("So far")))
        // The app comes back to the foreground and reloads history while its own turn is still streaming.
        val page = HistoryPage(7, listOf(Message(1, "user", "Hi", null, emptyList(), ""), Message(2, "assistant", "Hello Glen.", null, emptyList(), ""),
            Message(3, "assistant", "⏰ Reminder: stretch", null, emptyList(), "")), true, "turn0007")
        s = ChatReducer.reduce(s, ChatAction.HistoryLoaded(Conversation(7, "Chat with Glen", true), page))
        eq(s.messages.map { it.key }, listOf("m1", "m2", "m3", "tturn0007:u", "tturn0007:a"), "server history replaces the phone's, in-progress turn kept")
        check(s.hasOlder && s.messages.last().text == "So far") { "older flag and streaming text survive" }
        s = ChatReducer.reduce(s, ChatAction.OlderLoaded(HistoryPage(7, listOf(Message(0, "user", "Earlier", null, emptyList(), ""), Message(1, "user", "Hi", null, emptyList(), "")), false, null)))
        eq(s.messages.map { it.key }, listOf("m0", "m1", "m2", "m3", "tturn0007:u", "tturn0007:a"), "older page is prepended without duplicates")
        var idle = connected()
        idle = ChatReducer.reduce(idle, ChatAction.HistoryLoaded(Conversation(7, "x", true), HistoryPage(7, emptyList(), false, "someone-else")))
        eq(idle.activity, "Gemma is answering on another device", "a turn running from another device is shown, not hidden")
        eq(ChatReducer.reduce(idle, ChatAction.RemoteTurn(null)).activity, "", "and cleared when it ends")
        val model = ChatReducer.reduce(connected(), ChatAction.StatusKnown(ServerStatus(1, "gemma4:12b", false, "Ollama is not reachable", false, 7, emptyMap(), 3, 1)))
        eq(model.statusLine, "Connected · Model unavailable", "server up but model down is its own state")
    }

    private fun speechQueue() {
        val played = ArrayList<String>()
        val q = SpeechQueue { turn, seq, _ -> played.add("$turn#$seq") }
        q.begin("t1")
        q.offer("t1", 1, "u1"); eq(played, arrayListOf<String>(), "a later piece waits for the earlier one")
        q.offer("t1", 0, "u0"); eq(played, arrayListOf("t1#0", "t1#1"), "pieces play in order")
        q.offer("t1", 1, "u1"); q.offer("t1", 0, "u0"); eq(played.size, 2, "duplicates are ignored")
        q.offer("t0", 2, "old"); eq(played.size, 2, "pieces from another turn are ignored")
        q.offer("t1", 3, "u3"); q.stop("t1"); q.offer("t1", 2, "u2"); q.offer("t1", 4, "u4")
        eq(played.size, 2, "nothing plays after stop, including pieces that arrive late")
        q.begin("t2"); q.offer("t1", 2, "late"); q.offer("t2", 0, "v0")
        eq(played.last(), "t2#0", "a new turn starts clean and old pieces stay dropped")
        q.offer("t2", 2, "v2"); q.reset("t2"); q.offer("t2", 1, "v1"); eq(played.last(), "t2#1", "after a reset, queued pieces are dropped and order continues")
        eq(played, arrayListOf("t1#0", "t1#1", "t2#0", "t2#1"), "no overlap, no repeats")
    }

    private fun turnIds() {
        val ids = (1..500).map { TurnIds.new() }
        check(ids.toSet().size == 500 && ids.all { TurnIds.isValid(it) }) { "turn ids are unique and acceptable to the server" }
        check(!TurnIds.isValid("short") && !TurnIds.isValid("has space in it") && !TurnIds.isValid("x".repeat(65))) { "bad ids rejected" }
    }

    private fun messages() {
        val all = listOf(GemmaFailure.Unreachable("d"), GemmaFailure.AuthRequired(), GemmaFailure.BadResponse("d"), GemmaFailure.Interrupted("t", "d"),
            GemmaFailure.Api("turn_active", "x", 409), GemmaFailure.Api("validation_error", "x", 400), GemmaFailure.Api("conversation_not_found", "x", 404),
            GemmaFailure.Api("attachment_rejected", "x", 413), GemmaFailure.Api("voice_unavailable", "x", 503), GemmaFailure.Api("something_new", "x", 500))
        for (f in all) check(f.userMessage().isNotBlank() && !f.userMessage().contains("Exception") && !f.userMessage().contains("java.")) { "plain wording for ${f.javaClass.simpleName}" }
    }

    private fun codeBlocks() {
        val md = Markdown.parse("Here:\n\n```gdscript player.gd\nextends Node\n\tvar x = 1\n```\nDone.")
        val code = md.filterIsInstance<MdBlock.Code>().single()
        eq(code.lang, "gdscript", "fence language"); eq(code.filename, "player.gd", "fence filename")
        eq(code.text, "extends Node\n\tvar x = 1", "code text exact, tabs kept"); check(!code.open) { "closed block" }
        eq(Markdown.fenceInfo("python title=\"app.py\""), "python" to "app.py", "title= filename")
        eq(Markdown.fenceInfo("main.py"), "py" to "main.py", "a bare filename gives its language")
        val live = Markdown.parse("Writing:\n```python\ndef a():\n    pass").filterIsInstance<MdBlock.Code>().single()
        check(live.open && live.text == "def a():\n    pass") { "an unclosed fence is shown as code while it streams" }
        val named = Markdown.parse("```python\n# utils/helpers.py\nx = 1\n```").filterIsInstance<MdBlock.Code>().single()
        eq(named.filename, "utils/helpers.py", "filename from a first-line comment")
    }

    private fun highlighting() {
        val src = "@export var speed: float = 3.5 # fast\nfunc _ready():\n\tprint(\"hi\")\n"
        val p = Highlight.pieces(src, "gd")
        eq(p.joinToString("") { it.text }, src, "highlighting never changes the code")
        check(p.any { it.tok == Tok.KEYWORD && it.text == "func" } && p.any { it.tok == Tok.COMMENT && it.text == "# fast" } &&
            p.any { it.tok == Tok.STRING && it.text == "\"hi\"" } && p.any { it.tok == Tok.DECORATOR } && p.any { it.tok == Tok.NUMBER && it.text == "3.5" }) { "GDScript tokens: $p" }
        val py = "def f(a):\n    \"\"\"Doc.\"\"\"\n    return a  # x\n"
        val q = Highlight.pieces(py, "python")
        eq(q.joinToString("") { it.text }, py, "python round trip")
        check(q.any { it.tok == Tok.FUNCTION && it.text == "f" } && q.any { it.tok == Tok.STRING && it.text.startsWith("\"\"\"") }) { "python tokens" }
        eq(Highlight.pieces("plain words", "unknownlang").single().tok, Tok.PLAIN, "unknown languages are left plain")
        eq(Highlight.defaultFilename("gdscript"), "snippet.gd", "save name for GDScript")
    }

    private fun cards() {
        val line = """{"type":"done","turn_id":"turn0009","reply":"Made it.","message_id":42,"user_message_id":41,
            "artifacts":[{"id":"a0123456789abcde","kind":"code","filename":"utility.py","mime":"text/x-python","size":30,"url":"/api/v1/artifacts/a0123456789abcde/content","created_by":"gemma","provenance":{}}],
            "jobs":[{"id":"j1","kind":"image","title":"Generating an image","state":"running","progress":0.42,"progress_text":"Generating… 42%","result":null}],
            "approvals":[{"id":"p1","plugin":"local_git","tool":"git_commit","title":"Commit","state":"pending","preview":{"title":"Commit to proj","risk":"medium","fields":[["Branch","main"]],"body":"a.txt | 2"}}]}""".replace("\n", "")
        val done = Events.parse(line) as TurnEvent.Done
        eq(done.result.messageId, 42L, "reply id"); eq(done.result.artifacts.single().filename, "utility.py", "file card")
        eq(done.result.jobs.single().percent, 42, "the job percentage is the server's own")
        val ap = done.result.approvals.single(); check(ap.pending && ap.fields == listOf("Branch" to "main") && ap.risk == "medium") { "approval preview" }
        var s = ChatReducer.reduce(connected(), ChatAction.SendStarted("turn0009", "make it", files = listOf("notes.zip")))
        check(s.messages.last { it.role == "user" }.createdAt.isNotEmpty()) { "my message has a time at once" }
        eq(s.messages.last { it.role == "user" }.pendingFiles, listOf("notes.zip"), "attached file names show on my bubble")
        s = ChatReducer.reduce(s, ChatAction.Event("turn0009", done))
        val reply = s.messages.last()
        check(reply.id == 42L && reply.artifacts.size == 1 && reply.jobs.size == 1 && reply.approvals.size == 1) { "cards land on her reply" }
        eq(s.runningJobs.map { it.id }, listOf("j1"), "running tasks are known")
        s = ChatReducer.reduce(s, ChatAction.JobUpdated(reply.jobs[0].copy(state = "done", progress = 1.0, resultArtifactId = "a1")))
        check(s.runningJobs.isEmpty() && s.messages.last().jobs[0].state == "done") { "task update applies" }
        s = ChatReducer.reduce(s, ChatAction.ApprovalUpdated(ap.copy(state = "executed")))
        eq(s.messages.last().approvals[0].state, "executed", "approval outcome applies")
        val m = Message(5, "assistant", "x", null, emptyList(), "", artifacts = listOf(done.result.artifacts[0].copy(id = "a1", kind = "image")),
            jobs = listOf(reply.jobs[0].copy(state = "done", resultArtifactId = "a1")))
        s = ChatReducer.reduce(connected(), ChatAction.HistoryLoaded(Conversation(7, "", true), HistoryPage(7, listOf(m), false, null)))
        check(s.messages.single().jobs.isEmpty() && s.messages.single().artifacts.size == 1) { "a finished picture shows once, not as card and picture" }
    }

    private fun docProgress() {
        var s = ChatReducer.reduce(connected(), ChatAction.SendStarted("turn0010", "read this"))
        val ev = Events.parse("""{"type":"status","stage":"analysing","label":"Reading Ep4.docx — 3/10 · 28%","done":2,"total":10,"percent":28,"doc_stage":"analysing","doc_name":"Ep4.docx"}""")!!
        s = ChatReducer.reduce(s, ChatAction.Event("turn0010", ev))
        val d = s.docProgress!!
        check(d.percent == 28 && d.done == 2 && d.total == 10 && d.name == "Ep4.docx" && d.stage == "analysing") { "document progress from the server: $d" }
        s = ChatReducer.reduce(s, ChatAction.Event("turn0010", TurnEvent.Done(TurnResult("turn0010", "Read it.", null, false, false))))
        eq(s.docOutcome, "complete", "the document card ends as COMPLETE")
        s = ChatReducer.reduce(s, ChatAction.SendStarted("turn0011", "again"))
        check(s.docProgress == null && s.docOutcome == null) { "a new turn starts without an old document card" }
    }

    private fun relativeTimes() {
        val now = java.time.OffsetDateTime.parse("2026-10-12T19:00:00+11:00").toInstant().toEpochMilli()
        val z = java.time.ZoneId.of("Australia/Sydney")
        eq(RelativeTime.of("2026-10-12T18:59:30+11:00", now, z), "Just now", "under 45 s")
        eq(RelativeTime.of("2026-10-12T18:59:00+11:00", now, z), "About 1 min ago", "a minute")
        eq(RelativeTime.of("2026-10-12T18:40:00+11:00", now, z), "About 20 mins ago", "minutes")
        eq(RelativeTime.of("2026-10-12T15:00:00+11:00", now, z), "About 4 hours ago", "hours")
        eq(RelativeTime.of("2026-10-11T12:00:00+11:00", now, z), "Yesterday", "yesterday")
        eq(RelativeTime.of("2026-10-12T07:00:00", now, z), "About 1 hour ago", "a stamp without offset is UTC (07:00Z = 18:00 Sydney)")
        eq(RelativeTime.of("", now, z), "", "no stamp, no text")
        check(RelativeTime.of("2026-10-01T12:00:00+11:00", now, z).isNotBlank()) { "older messages show a date" }
    }

    @JvmStatic fun main(args: Array<String>) {
        println("CORE OK: ${runAll()} checks passed")
    }
}
