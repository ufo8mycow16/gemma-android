package app.gemma.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gemma.core.ChatMessage
import gemma.core.ChatState

private val Space0 = Color(0xFF030711); private val Space1 = Color(0xFF071427); private val Glass = Color(0xC8121D2F)
private val Cyan = Color(0xFF65D9FF); private val Violet = Color(0xFF8B7CFF); private val SoftText = Color(0xFFB7C7DA)
private enum class GemmaTab { CHAT, VOICE, MEMORY, TOOLS }

@Composable fun ChatScreen(viewModel: GemmaViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.chat.collectAsStateWithLifecycle(); val draft by viewModel.draft.collectAsStateWithLifecycle(); val voice by viewModel.voiceEnabled.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(GemmaTab.CHAT) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Space0, Space1, Space0)))) {
        Box(Modifier.fillMaxWidth().height(360.dp).background(Brush.radialGradient(listOf(Color(0x55315FA8), Color.Transparent))))
        Column(Modifier.fillMaxSize()) {
            Header(state, onOpenSettings)
            Box(Modifier.weight(1f)) { when (tab) {
                GemmaTab.CHAT -> Conversation(state, viewModel)
                GemmaTab.VOICE -> VoicePanel(state, voice, viewModel)
                GemmaTab.MEMORY -> ComingSoon("Memory", "Gemma's shared memory will appear here once Vision exposes the memory API.")
                GemmaTab.TOOLS -> ComingSoon("Tools", "Research, transcription, files and image tools will live here.")
            } }
            if (tab == GemmaTab.CHAT) Composer(state, draft, viewModel)
            BottomRail(tab) { tab = it }
        }
    }
}

@Composable private fun Conversation(state: ChatState, viewModel: GemmaViewModel) {
    val listState = rememberLazyListState(); val last = state.messages.lastOrNull()
    LaunchedEffect(state.messages.size, last?.text?.length) { if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.size - 1 + if (state.hasOlder) 1 else 0, Int.MAX_VALUE / 2) }
    if (state.messages.isEmpty()) Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { GemmaOrb(state); Spacer(Modifier.height(18.dp)); Text("Gemma", color=Color.White, style=MaterialTheme.typography.headlineMedium); Text(if(state.conversationId==null) state.statusLine else "Ready when you are", color=SoftText) }
    else LazyColumn(state=listState, modifier=Modifier.fillMaxSize(), contentPadding=PaddingValues(horizontal=14.dp, vertical=12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(state.hasOlder) item(key="older") { Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) { TextButton(onClick=viewModel::loadOlder){ Text("Earlier messages",color=Cyan) } } }
        items(state.messages,key={it.key}) { MessageBubble(it) }
    }
}

@Composable private fun GemmaOrb(state: ChatState) {
    Surface(shape=CircleShape,color=Color(0x221F9DE5),border=BorderStroke(2.dp, if(state.busy) Violet else Cyan),modifier=Modifier.size(120.dp)) { Box(contentAlignment=Alignment.Center){ Text("G",color=Color.White,style=MaterialTheme.typography.displayMedium,fontWeight=FontWeight.Light) } }
}

@Composable private fun VoicePanel(state: ChatState, enabled: Boolean, viewModel: GemmaViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
        GemmaOrb(state); Spacer(Modifier.height(22.dp)); Text("Gemma", color=Color.White, style=MaterialTheme.typography.headlineMedium)
        Text(if(state.activity.isNotBlank()) state.activity else if(state.voiceReady) "Voice ready" else "Voice unavailable on Vision", color=if(state.voiceReady) Cyan else SoftText)
        Spacer(Modifier.height(32.dp)); Button(onClick={viewModel.setVoiceEnabled(!enabled)}, colors=ButtonDefaults.buttonColors(containerColor=if(enabled) Color(0xFF176E91) else Color(0xFF26374B)), shape=RoundedCornerShape(24.dp)) { Text(if(enabled) "🔊  Speaker on" else "🔇  Speaker off") }
        Spacer(Modifier.height(12.dp)); if(state.busy) OutlinedButton(onClick=viewModel::stop, border=BorderStroke(1.dp,Color(0xFFFF8C9A))) { Text("■  Stop",color=Color(0xFFFFA4AF)) }
        Spacer(Modifier.height(24.dp)); Text("Live conversation and microphone controls are next. This build already requests Vision's generated speech and plays it on the phone.", color=SoftText, style=MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun ComingSoon(title:String, body:String){ Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){ Text(title,color=Color.White,style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(12.dp));Text(body,color=SoftText) } }

@Composable private fun Header(state:ChatState,onOpenSettings:()->Unit){ Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start=16.dp,end=8.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){ Text("☰",color=SoftText,style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(end=14.dp));Column(Modifier.weight(1f)){Text("Gemma",color=Color.White,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).clip(CircleShape).background(if(state.conversationId!=null) Color(0xFF56E39F) else Color(0xFFFFB45B)));Spacer(Modifier.width(6.dp));Text(state.statusLine,color=SoftText,style=MaterialTheme.typography.labelMedium)}};IconButton(onClick=onOpenSettings){Icon(Icons.Filled.Settings,"Settings",tint=SoftText)} } }

@Composable private fun MessageBubble(message:ChatMessage){ val mine=message.role=="user";val speaker=if(mine)"You" else "Gemma";var showLog by rememberSaveable(message.key){mutableStateOf(false)};val context=LocalContext.current
    Column(Modifier.fillMaxWidth(),horizontalAlignment=if(mine)Alignment.End else Alignment.Start){Text(speaker,style=MaterialTheme.typography.labelSmall,color=if(mine)Color(0xFFA8B8CF) else Cyan,modifier=Modifier.padding(horizontal=6.dp,vertical=2.dp));Surface(shape=RoundedCornerShape(19.dp),color=if(mine)Color(0xCC24375D) else Glass,border=BorderStroke(1.dp,if(mine)Color(0x443C75D7) else Color(0x334CCBFF)),modifier=Modifier.widthIn(max=540.dp).semantics{contentDescription="$speaker said: ${message.text}"}){SelectionContainer{Text(if(message.streaming&&message.text.isEmpty())"…" else message.text,Modifier.padding(horizontal=15.dp,vertical=12.dp),color=Color(0xFFF2F6FC),style=MaterialTheme.typography.bodyLarge)}};Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;cm.setPrimaryClip(ClipData.newPlainText("Gemma message",message.text))},contentPadding=PaddingValues(horizontal=7.dp,vertical=0.dp)){Text("Copy",color=SoftText,style=MaterialTheme.typography.labelSmall)};message.researchLog?.let{TextButton(onClick={showLog=!showLog},contentPadding=PaddingValues(horizontal=7.dp,vertical=0.dp)){Text(if(showLog)"Hide research" else "Research",color=Cyan,style=MaterialTheme.typography.labelSmall)}}};if(message.stopped)Text("Stopped",color=SoftText,style=MaterialTheme.typography.labelSmall);message.problem?.let{Text(it,color=Color(0xFFFF8C9A),style=MaterialTheme.typography.labelSmall)};if(showLog)message.researchLog?.let{log->Surface(shape=RoundedCornerShape(12.dp),color=Color(0xE80A101C),modifier=Modifier.widthIn(max=540.dp)){SelectionContainer{Text(log,Modifier.padding(12.dp),color=SoftText,style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace)}}}}
}

@Composable private fun Composer(state:ChatState,draft:String,viewModel:GemmaViewModel){Column(Modifier.fillMaxWidth().background(Color(0xE6060B14)).imePadding().padding(horizontal=12.dp,vertical=7.dp)){state.notice?.let{Row(verticalAlignment=Alignment.CenterVertically){Text(it,Modifier.weight(1f),color=Color(0xFFFF8C9A));TextButton(onClick=viewModel::dismissNotice){Text("OK")}}};if(state.activity.isNotBlank())Text(state.activity,color=Cyan,style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(start=8.dp,bottom=4.dp));Row(verticalAlignment=Alignment.CenterVertically){Surface(shape=CircleShape,color=Color(0x182FAFEA),border=BorderStroke(1.dp,Color(0x334CCBFF))){Text("＋",color=Cyan,modifier=Modifier.padding(horizontal=12.dp,vertical=7.dp),style=MaterialTheme.typography.titleLarge)};Spacer(Modifier.width(8.dp));OutlinedTextField(value=draft,onValueChange=viewModel::onDraftChange,modifier=Modifier.weight(1f).heightIn(min=52.dp).testTag("draft"),placeholder={Text("Message Gemma…",color=Color(0xFF75869A))},maxLines=5,shape=RoundedCornerShape(24.dp),colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,focusedBorderColor=Cyan,unfocusedBorderColor=Color(0xFF26374B),focusedContainerColor=Color(0xB20B1421),unfocusedContainerColor=Color(0xB20B1421)));Spacer(Modifier.width(8.dp));if(state.busy)Button(onClick=viewModel::stop,enabled=!state.stopping,shape=CircleShape,colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB4455A)),contentPadding=PaddingValues(14.dp)){Text("■")}else Button(onClick=viewModel::send,enabled=draft.isNotBlank()&&state.conversationId!=null,shape=CircleShape,colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF176E91)),contentPadding=PaddingValues(14.dp)){Text("➤")}}}}

@Composable private fun BottomRail(active:GemmaTab,onSelect:(GemmaTab)->Unit){Row(Modifier.fillMaxWidth().navigationBarsPadding().background(Color(0xF2050911)).padding(vertical=7.dp),horizontalArrangement=Arrangement.SpaceEvenly){RailItem("●","Chat",active==GemmaTab.CHAT){onSelect(GemmaTab.CHAT)};RailItem("◉","Voice",active==GemmaTab.VOICE){onSelect(GemmaTab.VOICE)};RailItem("◇","Memory",active==GemmaTab.MEMORY){onSelect(GemmaTab.MEMORY)};RailItem("⌘","Tools",active==GemmaTab.TOOLS){onSelect(GemmaTab.TOOLS)}}}
@Composable private fun RailItem(icon:String,label:String,active:Boolean,onClick:()->Unit){TextButton(onClick=onClick,contentPadding=PaddingValues(0.dp)){Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.width(70.dp)){Text(icon,color=if(active)Cyan else Color(0xFF718196),style=MaterialTheme.typography.titleMedium);Text(label,color=if(active)Cyan else Color(0xFF718196),style=MaterialTheme.typography.labelSmall)}}}
