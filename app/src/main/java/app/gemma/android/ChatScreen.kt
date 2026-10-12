package app.gemma.android

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.style.TextOverflow
import gemma.core.ChatMessage
import gemma.core.ChatState
import gemma.core.DocProgress
import gemma.core.RelativeTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

/** "16:22" from the server's timestamp, in the phone's own time zone. Empty if it cannot be read. */
internal fun shortTime(stamp: String): String = try {
    if (stamp.isBlank()) "" else OffsetDateTime.parse(stamp).atZoneSameInstant(ZoneId.systemDefault()).format(clockFormat)
} catch (e: Exception) {
    ""
}

@Composable
fun ChatScreen(viewModel: GemmaViewModel) {
    val state by viewModel.chat.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val speaking by viewModel.speaking.collectAsStateWithLifecycle()
    val pictures by viewModel.pictures.collectAsStateWithLifecycle()
    val files by viewModel.attachments.collectAsStateWithLifecycle()
    val viewer by viewModel.viewer.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Relative times ("About 5 mins ago") are refreshed every 30 seconds, as on the web page.
    val tick by produceState(0L) { while (true) { delay(30_000); value++ } }

    // Follow new text only while Glen is at the bottom. If he has scrolled up to read, nothing moves;
    // the arrow button appears instead (with a dot when something new has arrived below).
    var follow by remember { mutableStateOf(true) }
    var newBelow by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (dragging, more) ->
            if (dragging) follow = !more
            if (!more) { follow = true; newBelow = false }
        }
    }
    val last = state.messages.lastOrNull()
    val itemCount = state.messages.size + (if (state.hasOlder) 1 else 0)
    val contentKey = listOf(state.messages.size, last?.text?.length, last?.artifacts?.size, last?.jobs?.size, last?.approvals?.size, state.docProgress?.percent)
    LaunchedEffect(contentKey) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (follow) listState.scrollToItem(itemCount - 1, Int.MAX_VALUE / 2) else newBelow = true
    }
    LaunchedEffect(state.conversationId) { follow = true; newBelow = false }

    Box(Modifier.fillMaxSize()) {
        // Her picture sits behind the conversation: clear while the chat is empty, faint once it has messages.
        Portrait(Modifier.fillMaxSize(), strength = if (state.messages.isEmpty()) 0.95f else 0.16f)

        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.messages.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomStart) {
                        Surface(shape = RoundedCornerShape(18.dp), color = Color(0xCC0C1322)) {
                            Text(
                                if (state.conversationId == null) state.statusLine else "No messages yet.",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                color = Space.Text,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("messages"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(if (prefs.compact) 6.dp else 12.dp),
                    ) {
                        if (state.hasOlder) {
                            item(key = "older") {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    TextButton(onClick = { viewModel.loadOlder() }) { Text("Show earlier messages") }
                                }
                            }
                        }
                        items(state.messages, key = { it.key }) { message ->
                            val isLastReply = message === last && message.role != "user"
                            MessageBubble(
                                message, viewModel, compact = prefs.compact,
                                canPlay = prefs.voiceOutput && state.voiceReady,
                                playing = voice.playingKey == message.key,
                                tick = tick,
                                doc = if (isLastReply) state.docProgress else null,
                                docOutcome = if (isLastReply) state.docOutcome else null,
                            )
                        }
                    }
                }
                if (!follow && state.messages.isNotEmpty()) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(44.dp).clip(RoundedCornerShape(22.dp))
                            .background(Space.Raised).border(1.dp, Space.Line, RoundedCornerShape(22.dp))
                            .clickable {
                                scope.launch { listState.animateScrollToItem(itemCount - 1, Int.MAX_VALUE / 2) }
                                follow = true; newBelow = false
                            }
                            .testTag("jump"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Glyph.ArrowDown, contentDescription = "Jump to the latest message", tint = Space.Text, modifier = Modifier.size(22.dp))
                        if (newBelow) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(9.dp).clip(RoundedCornerShape(5.dp)).background(Space.Blue))
                    }
                }
            }
            Composer(state, draft, viewModel, voice, pictures, files, speakingNow = speaking != null && !voice.live, onSent = { follow = true })
        }
    }
    viewer?.let { ArtifactViewer(it, viewModel) }
}

@Composable
private fun MessageBubble(
    message: ChatMessage, viewModel: GemmaViewModel, compact: Boolean, canPlay: Boolean, playing: Boolean,
    tick: Long, doc: DocProgress?, docOutcome: String?,
) {
    val mine = message.role == "user"
    val speaker = if (mine) "Glen" else "Gemma"
    var showLog by rememberSaveable(message.key) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val pad = if (compact) 9.dp else 12.dp

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        if (!mine) {
            Avatar(if (compact) 28.dp else 34.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val showBubble = message.text.isNotEmpty() || message.streaming || message.attachments.isNotEmpty() ||
                message.pendingImages > 0 || message.pendingFiles.isNotEmpty()
            if (showBubble) Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (mine) Color(0xE61F3F86) else Color(0xE6131C31),
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .semantics { contentDescription = "$speaker said: ${message.text}" },
            ) {
                Column(Modifier.padding(horizontal = pad + 2.dp, vertical = pad - 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    message.attachments.forEach { path -> ServerPicture(viewModel, path) }
                    if (message.pendingFiles.isNotEmpty()) {
                        Text("📎 " + message.pendingFiles.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = Space.Muted)
                    }
                    if (message.pendingImages > 0) {
                        Text(
                            if (message.pendingImages == 1) "1 picture attached" else "${message.pendingImages} pictures attached",
                            style = MaterialTheme.typography.labelSmall, color = Space.Muted,
                        )
                    }
                    SelectionContainer {
                        if (message.streaming && message.text.isEmpty()) {
                            // Nothing has arrived yet: show that a reply is on its way.
                            Text("…", color = Space.Text, style = MaterialTheme.typography.bodyLarge)
                        } else if (message.text.isNotEmpty()) {
                            if (mine) Text(message.text, color = Space.Text, style = MaterialTheme.typography.bodyLarge)
                            else MarkdownText(message.text, Space.Text, onSaveCode = { name, code -> viewModel.saveCode(name, code) })
                        }
                    }
                }
            }
            if (doc != null) DocumentCard(doc, docOutcome)
            message.artifacts.forEach { ArtifactCard(it, viewModel) }
            message.jobs.forEach { JobCard(it, viewModel) }
            message.approvals.forEach { ApprovalCard(it, viewModel) }
            if (message.stopped) Text("Stopped", style = MaterialTheme.typography.labelSmall, color = Space.Muted)
            message.problem?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }

            if (!message.streaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The same relative time on Glen's messages and on Gemma's ("Just now", "About 5 mins ago").
                    val time = remember(message.createdAt, tick) { RelativeTime.of(message.createdAt) }
                    if (time.isNotEmpty()) {
                        Text(time, style = MaterialTheme.typography.labelSmall, color = Space.Muted,
                            modifier = Modifier.padding(horizontal = 6.dp).testTag(if (mine) "time-mine" else "time-hers"))
                    }
                    if (message.text.isNotEmpty()) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }, modifier = Modifier.size(34.dp)) {
                            Icon(Glyph.Copy, contentDescription = "Copy message", tint = Space.Muted, modifier = Modifier.size(17.dp))
                        }
                    }
                    // Only Gemma's own messages can be played, and only once Vision has stored them.
                    if (!mine && canPlay && message.id != null && message.text.isNotEmpty()) {
                        IconButton(onClick = { viewModel.playMessage(message) }, modifier = Modifier.size(34.dp)) {
                            if (playing) Icon(Glyph.Stop, contentDescription = "Stop speaking", tint = Space.Blue, modifier = Modifier.size(18.dp))
                            else Icon(Icons.Filled.PlayArrow, contentDescription = "Play message", tint = Space.Muted, modifier = Modifier.size(20.dp))
                        }
                    }
                    // The research log is the server's own record. It is shown only when asked for, never mixed into her words.
                    if (message.researchLog != null) {
                        TextButton(onClick = { showLog = !showLog }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(if (showLog) "Hide research" else "Research", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (showLog) {
                message.researchLog?.let { log ->
                    Surface(shape = RoundedCornerShape(8.dp), color = Space.Surface) {
                        SelectionContainer {
                            Text(log, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Space.Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerPicture(viewModel: GemmaViewModel, path: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, path) { value = viewModel.serverImage(path) }
    val shown = bitmap
    if (shown != null) {
        Image(
            bitmap = shown.asImageBitmap(), contentDescription = "Picture sent with this message",
            modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text("Picture", style = MaterialTheme.typography.labelSmall, color = Space.Muted)
    }
}

@Composable
private fun Composer(
    state: ChatState, draft: String, viewModel: GemmaViewModel, voice: VoiceUi,
    pictures: List<Picture>, files: List<PendingFile>, speakingNow: Boolean, onSent: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.addPicture(uri)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = photoUri
        if (saved && uri != null) viewModel.addPicture(uri)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { viewModel.addFile(it) }
    }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.dictate()
    }

    Surface(color = Color(0xF20C1322)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            state.notice?.let { notice ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { viewModel.dismissNotice() }) { Text("OK") }
                }
            }
            // A calendar change Gemma has proposed: the buttons send Glen's own answer as an ordinary message.
            state.pendingCalendar?.let { proposal ->
                if (!state.busy) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        if (proposal.summary.isNotBlank()) {
                            Text(proposal.summary, style = MaterialTheme.typography.labelMedium, color = Space.Muted, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.sendNow("Yes, go ahead.") }) { Text("Yes, go ahead") }
                            OutlinedButton(onClick = { viewModel.sendNow("No, cancel that.") }) { Text("No, cancel that") }
                        }
                    }
                }
            }
            // What Gemma is doing, in the server's own words. Nothing is invented here.
            if (state.activity.isNotBlank()) {
                Text(state.activity, modifier = Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = Space.Muted)
            } else if (speakingNow) {
                Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Glyph.Volume, contentDescription = null, tint = Space.Blue, modifier = Modifier.size(16.dp))
                    Text("  Speaking", style = MaterialTheme.typography.labelMedium, color = Space.Muted)
                    TextButton(onClick = { viewModel.stopSpeaking() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Stop voice") }
                }
            } else if (voice.dictating) {
                Text("Listening… tap the microphone when you've finished", modifier = Modifier.padding(bottom = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = Space.Blue)
            }
            if (pictures.isNotEmpty()) {
                Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pictures.forEachIndexed { index, picture ->
                        Box {
                            Image(
                                bitmap = picture.preview.asImageBitmap(), contentDescription = "Picture to send",
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Space.Line, RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Box(
                                Modifier.align(Alignment.TopEnd).size(22.dp).clip(RoundedCornerShape(11.dp))
                                    .background(Color(0xCC000000)).clickable { viewModel.removePicture(index) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Close, contentDescription = "Remove picture", tint = Color.White, modifier = Modifier.size(14.dp)) }
                        }
                    }
                }
            }
            if (files.isNotEmpty()) {
                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    files.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Space.Raised).padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(f.name, color = Space.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                val line = when {
                                    f.error != null -> f.error
                                    !f.ready && f.document -> "Reading…"
                                    !f.ready -> "Uploading ${(f.progress * 100).toInt()}%"
                                    f.document -> "${sizeText(f.size)} · Gemma reads it when you send"
                                    else -> "${sizeText(f.size)} · uploaded"
                                }
                                Text(line, style = MaterialTheme.typography.labelSmall, color = if (f.error != null) MaterialTheme.colorScheme.error else Space.Muted)
                                if (!f.ready && f.error == null && !f.document) {
                                    LinearProgressIndicator(progress = { f.progress.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                }
                            }
                            IconButton(onClick = { viewModel.removeFile(f.id) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${f.name}", tint = Space.Muted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.height(56.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = "Add a picture or file", tint = Space.Muted)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Choose a picture") },
                            leadingIcon = { Icon(Glyph.Picture, contentDescription = null) },
                            onClick = {
                                menu = false
                                pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Attach a file") },
                            leadingIcon = { Icon(Glyph.Attach, contentDescription = null) },
                            onClick = {
                                menu = false
                                pickFiles.launch(arrayOf("*/*"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Take a photo") },
                            leadingIcon = { Icon(Glyph.Camera, contentDescription = null) },
                            onClick = {
                                menu = false
                                try {
                                    val uri = viewModel.newPhotoUri()
                                    photoUri = uri
                                    takePhoto.launch(uri)
                                } catch (e: Exception) {
                                    photoUri = null
                                }
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = viewModel::onDraftChange,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("draft"),
                    placeholder = { Text("Message Gemma…", color = Space.Muted) },
                    maxLines = 6,
                    shape = RoundedCornerShape(26.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Space.Blue, unfocusedBorderColor = Space.Line,
                        focusedContainerColor = Space.Surface, unfocusedContainerColor = Space.Surface,
                        focusedTextColor = Space.Text, unfocusedTextColor = Space.Text, cursorColor = Space.Blue,
                    ),
                    trailingIcon = {
                        if (!voice.live) {
                            IconButton(onClick = {
                                if (context.allowed(Manifest.permission.RECORD_AUDIO)) viewModel.dictate()
                                else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                            }) {
                                Icon(Glyph.Mic, contentDescription = if (voice.dictating) "Finish speaking" else "Speak a message",
                                    tint = if (voice.dictating) Space.Blue else Space.Muted)
                            }
                        }
                    },
                )
                if (state.busy) {
                    Button(
                        onClick = { viewModel.stop() },
                        enabled = !state.stopping,
                        modifier = Modifier.padding(start = 8.dp).heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Space.Red),
                    ) { Text(if (state.stopping) "Stopping" else "Stop") }
                } else {
                    val uploading = files.any { !it.ready && it.error == null }
                    Button(
                        onClick = { viewModel.send(); onSent() },
                        enabled = !uploading && (draft.isNotBlank() || pictures.isNotEmpty() || files.any { it.ready }) && state.conversationId != null,
                        modifier = Modifier.padding(start = 8.dp).heightIn(min = 56.dp),
                    ) { Text("Send") }
                }
            }
        }
    }
}
