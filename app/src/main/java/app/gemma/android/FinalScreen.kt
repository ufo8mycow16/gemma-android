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
import androidx.compose.foundation.shape.CircleShape
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
import gemma.core.Conversation
import kotlinx.coroutines.launch

private val Night = Color(0xFF02050B)
private val Deep = Color(0xFF071526)
private val Panel = Color(0xE80A1726)
private val PanelSoft = Color(0xC8102236)
private val Cyan = Color(0xFF67E8FF)
private val CyanSoft = Color(0xFFB6F5FF)
private val Violet = Color(0xFFA18BFF)
private val Green = Color(0xFF68E6A6)
private val Muted = Color(0xFFA6B5C8)
private enum class Tab { CHAT, VOICE, MEMORY, TOOLS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalScreen(vm: GemmaViewModel, onOpenSettings: () -> Unit, onRequestSpeech: () -> Unit) {
    val state by vm.chat.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val voice by vm.voiceEnabled.collectAsStateWithLifecycle()
    val live by vm.liveMode.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF06101C), modifier = Modifier.width(310.dp)) {
                DrawerHeader()
                Text("CONVERSATIONS", Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp), color = Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                if (conversations.isEmpty()) {
                    Text("No conversations loaded", Modifier.padding(20.dp), color = Muted)
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(conversations, key = { it.id }) { conversation ->
                            ConversationCard(conversation, state.conversationId == conversation.id) {
                                vm.selectConversation(conversation.id)
                                scope.launch { drawer.close() }
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF17314A))
                Text("Conversations live on Vision and stay in sync across devices.", Modifier.padding(18.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Night, Deep, Color(0xFF03101B), Night)))) {
            Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.radialGradient(listOf(Color(0x332DDCFF), Color.Transparent))))
            Column(Modifier.fillMaxSize()) {
                Top(state, menu = { vm.refreshConversations(); scope.launch { drawer.open() } }, settings = onOpenSettings)
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.CHAT -> Chat(state, vm)
                        Tab.VOICE -> Voice(state, voice, live, speaking, vm, onRequestSpeech)
                        Tab.MEMORY -> Info("Memory", "Gemma's persistent memory lives privately on Vision.")
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
private fun DrawerHeader() {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(48.dp), shape = CircleShape, color = Color(0xFF102B40), border = BorderStroke(1.dp, Cyan)) {
            Box(contentAlignment = Alignment.Center) { Text("G", color = Cyan, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.width(12.dp))
        Column { Text("GEMMA", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("PRIVATE AI • VISION", color = Cyan, style = MaterialTheme.typography.labelSmall) }
    }
    HorizontalDivider(color = Color(0xFF17314A))
}

@Composable
private fun ConversationCard(c: Conversation, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = if (selected) Color(0xFF12324A) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, Color(0x8867E8FF)) else null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) Green else Color(0xFF52687C)))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title.ifBlank { "Conversation ${c.id}" }, color = if (selected) Color.White else CyanSoft, maxLines = 2, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (selected) Text("CURRENT", color = Green, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Top(state: ChatState, menu: () -> Unit, settings: () -> Unit) {
    Surface(color = Color(0xB8040A12), shadowElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(menu) { Text("☰", color = Cyan, style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.conversationTitle.ifBlank { "GEMMA" }, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (state.conversationId != null) Green else Muted)); Spacer(Modifier.width(6.dp))
                    Text(state.statusLine.uppercase(), color = if (state.conversationId != null) Green else Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            IconButton(settings) { Icon(Icons.Default.Settings, "Settings", tint = Cyan) }
        }
    }
}

@Composable
private fun Portrait(state: ChatState, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(30.dp)).background(Color(0xFF10263A))) {
        Image(painterResource(R.drawable.gemma_portrait), "Gemma", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x08000000), Color.Transparent, Color(0xEE010610)))))
        Surface(Modifier.align(Alignment.BottomCenter).padding(14.dp), shape = RoundedCornerShape(22.dp), color = Color(0xC906101C), border = BorderStroke(1.dp, if (state.busy) Violet else Cyan)) {
            Text(if (state.busy) state.activity.ifBlank { "JUST THINKING…" } else "ONLINE", Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Chat(state: ChatState, vm: GemmaViewModel) {
    if (state.messages.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Portrait(state, Modifier.fillMaxWidth(.60f).aspectRatio(.78f))
            Spacer(Modifier.height(18.dp)); Text("Gemma", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Your private AI on Vision", color = Muted)
        }
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.hasOlder) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { OutlinedButton(onClick = vm::loadOlder, shape = RoundedCornerShape(18.dp)) { Text("Earlier messages") } } }
        items(state.messages, key = { it.key }) { Bubble(it, vm, state.voiceReady) }
    }
}

@Composable
private fun Bubble(message: ChatMessage, vm: GemmaViewModel, voiceReady: Boolean) {
    val mine = message.role == "user"; val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text(if (mine) "YOU" else "GEMMA", color = if (mine) Muted else Cyan, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
        Surface(shape = RoundedCornerShape(if (mine) 22.dp else 20.dp), color = if (mine) Color(0xEE1D3B61) else Panel,
            border = BorderStroke(1.dp, if (mine) Color(0x665B91DD) else Color(0x665EE5FF)), shadowElevation = 3.dp, modifier = Modifier.widthIn(max = 560.dp)) {
            Text(if (message.streaming && message.text.isBlank()) "…" else message.text, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), color = Color(0xFFF3F7FB), style = MaterialTheme.typography.bodyLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!mine && !message.streaming && message.text.isNotBlank()) {
                TextButton(onClick = { vm.speakMessage(message.text) }, enabled = voiceReady, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("🔊  PLAY", color = if (voiceReady) Cyan else Muted, style = MaterialTheme.typography.labelSmall) }
            }
            TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Gemma", message.text)) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("COPY", color = Muted, style = MaterialTheme.typography.labelSmall) }
        }
        message.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun Composer(state: ChatState, draft: String, vm: GemmaViewModel, mic: () -> Unit) {
    Surface(color = Color(0xF5050A12), shadowElevation = 12.dp) {
        Row(Modifier.fillMaxWidth().imePadding().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = mic, enabled = !state.busy) { Text("🎙", style = MaterialTheme.typography.titleLarge) }
            OutlinedTextField(draft, vm::onDraftChange, Modifier.weight(1f), placeholder = { Text("Message Gemma…", color = Muted) }, shape = RoundedCornerShape(25.dp), maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF29465F), focusedContainerColor = Color(0x99101D2B), unfocusedContainerColor = Color(0x77101D2B)))
            Spacer(Modifier.width(8.dp))
            if (state.busy) FilledIconButton(onClick = vm::stop, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF7B315F))) { Text("■", color = Color.White) }
            else FilledIconButton(onClick = vm::send, enabled = draft.isNotBlank() && state.conversationId != null, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF126E8C))) { Text("➤", color = Color.White) }
        }
    }
}

@Composable
private fun Voice(state: ChatState, voice: Boolean, live: Boolean, speaking: Boolean, vm: GemmaViewModel, mic: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Portrait(state, Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp))
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(24.dp), color = Color(0xC9071420), border = BorderStroke(1.dp, Color(0x7767E8FF))) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 15.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when { speaking -> "GEMMA IS SPEAKING"; state.busy -> state.activity.uppercase().ifBlank { "GEMMA IS THINKING" }; live -> "LISTENING"; else -> "VOICE MODE" }, color = Cyan, fontWeight = FontWeight.Bold)
                    Text(when { speaking -> "Tiffany voice • Vision"; live -> "Live conversation active"; state.voiceReady -> "Tiffany voice ready"; else -> "Voice unavailable on Vision" }, color = Color.White)
                }
            }
            Spacer(Modifier.height(16.dp))
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

@Composable private fun GlowButton(icon: String, label: String, enabled: Boolean = true, onClick: () -> Unit) { OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Cyan), colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xAA071521))) { Text("$icon  $label", color = Color.White) } }
@Composable private fun Info(title: String, body: String) { Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text(body, color = Muted) } }
@Composable private fun Tools(vm: GemmaViewModel) { Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("TOOLS", color = Color.White, style = MaterialTheme.typography.headlineMedium); Tool("RESEARCH", "Use Gemma's Vision research tools.") { vm.onDraftChange("Research: ") }; Tool("CODE HELP", "Start a coding task.") { vm.onDraftChange("Help me with this code: ") }; Tool("DOCUMENTS", "PDF, DOCX and TXT reading is available through Vision.") {}; Tool("IMAGE ANALYSIS", "Send an image for Gemma to inspect.") {} } }
@Composable private fun Tool(title: String, body: String, action: () -> Unit) { Surface(onClick = action, shape = RoundedCornerShape(18.dp), color = PanelSoft, border = BorderStroke(1.dp, Color(0x443AC7EF))) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(title, color = Cyan, fontWeight = FontWeight.Bold); Text(body, color = Muted, style = MaterialTheme.typography.bodySmall) } } }
@Composable private fun Bottom(active: Tab, onSelect: (Tab) -> Unit) { Surface(color = Color(0xFA030811), shadowElevation = 12.dp) { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly) { Tab.entries.forEach { tab -> TextButton(onClick = { onSelect(tab) }) { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp)) { Text(when (tab) { Tab.CHAT -> "●"; Tab.VOICE -> "◉"; Tab.MEMORY -> "◇"; Tab.TOOLS -> "⌘" }, color = if (active == tab) Cyan else Color(0xFF65778E)); Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, color = if (active == tab) Cyan else Color(0xFF65778E), style = MaterialTheme.typography.labelSmall) } } } } } }
