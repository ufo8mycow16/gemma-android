package app.gemma.android

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Shortcuts to things Gemma can do. Most of them simply start a message to
 * her; file search, transcription and her lists have screens of their own.
 */
@Composable
fun ToolsScreen(viewModel: GemmaViewModel, onGoToChat: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("home") }
    BackHandler(enabled = page != "home") { page = "home" }

    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.addPicture(uri)
            onGoToChat()
        }
    }
    fun startMessage(opening: String) {
        viewModel.onDraftChange(opening)
        onGoToChat()
    }

    when (page) {
        "files" -> FileSearchPage(viewModel, onBack = { page = "home" }, onAsk = { startMessage(it) })
        "transcribe" -> TranscribePage(viewModel, onBack = { page = "home" }, onSend = { startMessage(it) })
        "lists" -> ListsPage(viewModel, onBack = { page = "home" })
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tools", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(Glyph.Globe, "Web Search", Modifier.weight(1f)) { startMessage("Search the web for ") }
                Tile(Glyph.Document, "Summarise", Modifier.weight(1f)) { startMessage("Summarise this:\n") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(Glyph.Picture, "Image Analysis", Modifier.weight(1f)) {
                    pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                Tile(Glyph.Wave, "Transcribe", Modifier.weight(1f)) { page = "transcribe" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(Glyph.Code, "Code Help", Modifier.weight(1f)) { startMessage("Help me with this code:\n") }
                Tile(Glyph.Folder, "File Search", Modifier.weight(1f)) { page = "files" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(Glyph.Bell, "Notes & Reminders", Modifier.weight(1f)) { page = "lists" }
            }
        }
    }
}

@Composable
private fun Tile(symbol: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp), color = Space.Surface,
        modifier = modifier.height(104.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).testTag("tool-$label"),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(symbol, contentDescription = null, tint = Space.Blue, modifier = Modifier.size(30.dp))
            Text(label, modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PageTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to tools") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun FileSearchPage(viewModel: GemmaViewModel, onBack: () -> Unit, onAsk: (String) -> Unit) {
    val ui by viewModel.files.collectAsStateWithLifecycle()
    val open = ui.open

    if (open != null) {
        BackHandler { viewModel.closeFile() }
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            PageTitle(open.name.ifBlank { "File" }, onBack = { viewModel.closeFile() })
            Text(open.path, style = MaterialTheme.typography.labelSmall, color = Space.Muted)
            OutlinedButton(onClick = { onAsk("About the file ${open.path}: ") }, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("Ask Gemma about this file")
            }
            if (open.truncated) Text("Only the beginning of this file is shown.", style = MaterialTheme.typography.labelSmall, color = Space.Muted)
            SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(open.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 14.dp)) {
        PageTitle("File Search", onBack)
        Text("Searches your files on Vision by name and by what's inside them. Nothing is ever changed.",
            style = MaterialTheme.typography.bodySmall, color = Space.Muted, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = ui.query, onValueChange = { viewModel.searchFiles(it) }, singleLine = true,
            placeholder = { Text("File name or words inside it") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.searchFiles(ui.query) }),
            modifier = Modifier.fillMaxWidth().testTag("file-search"),
        )
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        if (ui.loading) Text("Searching…", color = Space.Muted, modifier = Modifier.padding(8.dp))
        if (ui.opening) Text("Opening…", color = Space.Muted, modifier = Modifier.padding(8.dp))
        val results = ui.results
        if (results != null && results.results.isEmpty() && !ui.loading) Text("Nothing found.", color = Space.Muted, modifier = Modifier.padding(8.dp))
        if (results != null && results.truncated) {
            Text("The search ran out of time; there may be more.", style = MaterialTheme.typography.labelSmall, color = Space.Muted, modifier = Modifier.padding(top = 6.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results?.results ?: emptyList(), key = { it.path }) { hit ->
                Surface(
                    shape = RoundedCornerShape(12.dp), color = Space.Surface,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { viewModel.openFile(hit) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(hit.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(hit.folder, style = MaterialTheme.typography.labelSmall, color = Space.Blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (hit.snippet.isNotBlank()) {
                            Text(hit.snippet, style = MaterialTheme.typography.bodySmall, color = Space.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscribePage(viewModel: GemmaViewModel, onBack: () -> Unit, onSend: (String) -> Unit) {
    val ui by viewModel.transcribe.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording()
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.transcribeFile(uri)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTitle("Transcribe", onBack)
        Text("Turns a recording into text. The audio is sent to Vision, written out there, and not kept.",
            style = MaterialTheme.typography.bodySmall, color = Space.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.recording) {
                Button(onClick = { viewModel.finishRecording() }, colors = ButtonDefaults.buttonColors(containerColor = Space.Red)) {
                    Text("Stop and transcribe")
                }
            } else {
                Button(enabled = !ui.working, onClick = {
                    if (context.allowed(Manifest.permission.RECORD_AUDIO)) viewModel.startRecording()
                    else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Record") }
                OutlinedButton(enabled = !ui.working, onClick = { pickAudio.launch("audio/*") }) { Text("Choose a file") }
            }
        }
        if (ui.recording) Text("Recording…", color = Space.Blue)
        if (ui.working) Text("Vision is writing it out. Long recordings take a while.", color = Space.Muted)
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val text = ui.text
        if (text != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
                OutlinedButton(onClick = { onSend(text) }) { Text("Put in a message") }
                OutlinedButton(onClick = { viewModel.clearTranscript() }) { Text("Clear") }
            }
            SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ListsPage(viewModel: GemmaViewModel, onBack: () -> Unit) {
    val ui by viewModel.lists.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadLists() }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        PageTitle("Notes & Reminders", onBack)
        Text("Ask Gemma to add, change or remove these.", style = MaterialTheme.typography.bodySmall, color = Space.Muted)
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        if (ui.loading) Text("Loading…", color = Space.Muted, modifier = Modifier.padding(8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "r-head") { Text("Reminders", style = MaterialTheme.typography.titleMedium, color = Space.Blue) }
            if (ui.reminders.isEmpty() && !ui.loading) item(key = "r-none") { Text("None waiting.", color = Space.Muted) }
            items(ui.reminders, key = { "r" + it.id }) { r ->
                Surface(shape = RoundedCornerShape(12.dp), color = Space.Surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(r.text)
                        Text(friendlyWhen(r.dueAt), style = MaterialTheme.typography.labelSmall, color = Space.Muted)
                    }
                }
            }
            item(key = "n-head") { Text("Notes", style = MaterialTheme.typography.titleMedium, color = Space.Blue, modifier = Modifier.padding(top = 12.dp)) }
            if (ui.notes.isEmpty() && !ui.loading) item(key = "n-none") { Text("No notes.", color = Space.Muted) }
            items(ui.notes, key = { "n" + it.id }) { n ->
                Surface(shape = RoundedCornerShape(12.dp), color = Space.Surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        if (n.listName.isNotBlank()) Text(n.listName, style = MaterialTheme.typography.labelSmall, color = Space.Muted)
                        Text(n.text)
                    }
                }
            }
        }
    }
}

/** "Sat 10 Oct, 18:30" in the phone's time zone; the raw value if it cannot be read. */
private fun friendlyWhen(stamp: String): String = try {
    java.time.OffsetDateTime.parse(stamp).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
} catch (e: Exception) {
    stamp
}
