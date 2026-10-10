package app.gemma.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gemma.core.ChatMessage
import gemma.core.ChatState
import kotlinx.coroutines.launch

private val Night = Color(0xFF01050D)
private val Panel = Color(0xD9091729)
private val Cyan = Color(0xFF62E7FF)
private val Violet = Color(0xFF8A79FF)
private val Muted = Color(0xFFA7B7CB)
private enum class Tab { CHAT, VOICE, MEMORY, TOOLS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalScreen(vm: GemmaViewModel, onOpenSettings: () -> Unit, onRequestSpeech: () -> Unit) {
    val state by vm.chat.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val voice by vm.voiceEnabled.collectAsStateWithLifecycle()
    val live by vm.liveMode.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF06101E)) {
                Spacer(Modifier.height(44.dp))
                Text("GEMMA", Modifier.padding(horizontal = 20.dp), color = Cyan, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("ON VISION", Modifier.padding(horizontal = 20.dp, vertical = 3.dp), color = Muted, style = MaterialTheme.typography.labelSmall)
                HorizontalDivider(Modifier.padding(top = 18.dp), color = Color(0xFF1C344D))
                Text("CONVERSATIONS", Modifier.padding(20.dp), color = Muted, style = MaterialTheme.typography.labelMedium)
                Surface(
                    onClick = { scope.launch { drawer.close() } },
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF10243B),
                    border = BorderStroke(1.dp, Color(0x554FDFFF)),
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(state.conversationTitle.ifBlank { "Primary conversation" }, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Current conversation on Vision", color = Cyan, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Vision currently exposes this primary conversation to the phone.", Modifier.padding(horizontal = 20.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Night, Color(0xFF06152A), Night)))) {
            Column(Modifier.fillMaxSize()) {
                Top(state, { scope.launch { drawer.open() } }, onOpenSettings)
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.CHAT -> Chat(state, vm)
                        Tab.VOICE -> Voice(state, voice, live, speaking, vm, onRequestSpeech)
                        Tab.MEMORY -> Info("Memory", "Gemma's shared memory stays on Vision. This page will read the real memory API when Vision exposes it.")
                        Tab.TOOLS -> Tools(vm)
                    }
                }
                if (tab == Tab.CHAT) Composer(state, draft, vm, onRequestSpeech)
                Bottom(tab) { tab = it }
            }
        }
    }
}

@Composable
private fun Top(state: ChatState, menu: () -> Unit, settings: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(menu) { Text("☰", color = Cyan, style = MaterialTheme.typography.titleLarge) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GEMMA", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(state.statusLine.uppercase(), color = if (state.conversationId != null) Color(0xFF5DE7A3) else Muted, style = MaterialTheme.typography.labelSmall)
        }
        IconButton(settings) { Icon(Icons.Default.Settings, "Settings", tint = Cyan) }
    }
}

@Composable
private fun Portrait(state: ChatState, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(30.dp)).background(Color(0xFF10263A))) {
        Image(painterResource(R.drawable.gemma_portrait), "Gemma", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x10000000), Color.Transparent, Color(0xDD010610)))))
        Surface(
            Modifier.align(Alignment.BottomCenter).padding(14.dp),
            shape = RoundedCornerShape(22.dp), color = Color(0xB906101C),
            border = BorderStroke(1.dp, if (state.busy) Violet else Cyan)
        ) {
            Text(if (state.busy) state.activity.ifBlank { "JUST THINKING…" } else "ONLINE", Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Chat(state: ChatState, vm: GemmaViewModel) {
    if (state.messages.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Portrait(state, Modifier.fillMaxWidth(.64f).aspectRatio(.72f))
            Spacer(Modifier.height(16.dp)); Text("Gemma", color = Color.White, style = MaterialTheme.typography.headlineMedium); Text("Your private AI on Vision", color = Muted)
        }
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.hasOlder) item { TextButton(onClick = vm::loadOlder) { Text("Earlier messages") } }
        items(state.messages, key = { it.key }) { Bubble(it) }
    }
}

@Composable
private fun Bubble(message: ChatMessage) {
    val mine = message.role == "user"; val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text(if (mine) "YOU" else "GEMMA", color = if (mine) Muted else Cyan, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(20.dp), color = if (mine) Color(0xDD20375E) else Panel, border = BorderStroke(1.dp, if (mine) Color(0x554C7BD9) else Color(0x555EE5FF)), modifier = Modifier.widthIn(max = 560.dp)) {
            Text(if (message.streaming && message.text.isBlank()) "…" else message.text, Modifier.padding(15.dp), color = Color.White)
        }
        TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Gemma", message.text)) }) { Text("COPY", color = Muted, style = MaterialTheme.typography.labelSmall) }
        message.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun Composer(state: ChatState, draft: String, vm: GemmaViewModel, mic: () -> Unit) {
    Row(Modifier.fillMaxWidth().imePadding().background(Color(0xF0050A12)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = mic, enabled = !state.busy) { Text("🎙", style = MaterialTheme.typography.titleLarge) }
        OutlinedTextField(draft, vm::onDraftChange, Modifier.weight(1f), placeholder = { Text("Message Gemma…") }, shape = RoundedCornerShape(26.dp), maxLines = 5, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF27425C)))
        Spacer(Modifier.width(8.dp))
        if (state.busy) Button(onClick = vm::stop, shape = RoundedCornerShape(24.dp)) { Text("■") }
        else Button(onClick = vm::send, enabled = draft.isNotBlank() && state.conversationId != null, shape = RoundedCornerShape(24.dp)) { Text("➤") }
    }
}

@Composable
private fun Voice(state: ChatState, voice: Boolean, live: Boolean, speaking: Boolean, vm: GemmaViewModel, mic: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Portrait(state, Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp))
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text(when { speaking -> "GEMMA IS SPEAKING"; state.busy -> "GEMMA IS THINKING"; live -> "LISTENING"; else -> "VOICE MODE" }, color = Cyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(when { speaking -> "Tiffany voice • Vision"; live -> "Live conversation active"; state.voiceReady -> "Tiffany voice ready"; else -> "Voice unavailable on Vision" }, color = Color.White)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlowButton(if (voice) "🔊" else "🔇", if (voice) "SPEAKER" else "MUTED") { vm.setVoiceEnabled(!voice) }
                GlowButton("🎙", "TALK", enabled = !state.busy) { mic() }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.setLiveMode(!live) }, shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = if (live) Color(0xCC7B315F) else Color(0xDD126889)), border = BorderStroke(1.dp, if (live) Violet else Cyan), modifier = Modifier.fillMaxWidth(.72f)) {
                Text(if (live) "END LIVE MODE" else "START LIVE MODE", Modifier.padding(vertical = 5.dp), fontWeight = FontWeight.Bold)
            }
            if (state.busy || speaking) { Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = vm::stop, border = BorderStroke(1.dp, Color(0xFFFF8CA0))) { Text("STOP / INTERRUPT", color = Color(0xFFFFA5B2)) } }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun GlowButton(icon: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Cyan), colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xAA071521))) { Text("$icon  $label", color = Color.White) }
}

@Composable
private fun Info(title: String, body: String) { Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text(body, color = Muted) } }

@Composable
private fun Tools(vm: GemmaViewModel) { Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("TOOLS", color = Color.White, style = MaterialTheme.typography.headlineMedium); Tool("RESEARCH", "Use Gemma's existing Vision research tools.") { vm.onDraftChange("Research: ") }; Tool("CODE HELP", "Start a coding task.") { vm.onDraftChange("Help me with this code: ") }; Tool("IMAGE ANALYSIS", "Vision supports images; attachment upload still needs its Android contract.") {}; Tool("TRANSCRIBE", "Waiting for Vision's transcription endpoint.") {}; Tool("FILE SEARCH", "Waiting for the security-scoped read-only Vision file API.") {} } }

@Composable
private fun Tool(title: String, body: String, action: () -> Unit) { Surface(onClick = action, shape = RoundedCornerShape(18.dp), color = Panel, border = BorderStroke(1.dp, Color(0x443AC7EF))) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(title, color = Cyan, fontWeight = FontWeight.Bold); Text(body, color = Muted, style = MaterialTheme.typography.bodySmall) } } }

@Composable
private fun Bottom(active: Tab, onSelect: (Tab) -> Unit) { Row(Modifier.fillMaxWidth().navigationBarsPadding().background(Color(0xFA030811)).padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly) { Tab.entries.forEach { tab -> TextButton(onClick = { onSelect(tab) }) { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp)) { Text(when (tab) { Tab.CHAT -> "●"; Tab.VOICE -> "◉"; Tab.MEMORY -> "◇"; Tab.TOOLS -> "⌘" }, color = if (active == tab) Cyan else Color(0xFF65778E)); Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, color = if (active == tab) Cyan else Color(0xFF65778E), style = MaterialTheme.typography.labelSmall) } } } } }
