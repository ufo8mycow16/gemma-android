package app.gemma.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gemma.core.ChatMessage
import gemma.core.ChatState
import gemma.core.Conversation
import kotlinx.coroutines.launch

private val Night=Color(0xFF01050B); private val Deep=Color(0xFF03111D); private val Panel=Color(0xEE071522)
private val Cyan=Color(0xFF39DDF8); private val Cyan2=Color(0xFF7CEBFF); private val Green=Color(0xFF54E69A); private val Muted=Color(0xFF8EA4BE)
private enum class Tab { CHAT, VOICE, MEMORY, TOOLS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FinalScreen(vm:GemmaViewModel,onOpenSettings:()->Unit,onRequestSpeech:()->Unit){
 val state by vm.chat.collectAsStateWithLifecycle(); val draft by vm.draft.collectAsStateWithLifecycle(); val voice by vm.voiceEnabled.collectAsStateWithLifecycle(); val live by vm.liveMode.collectAsStateWithLifecycle(); val speaking by vm.speaking.collectAsStateWithLifecycle(); val conversations by vm.conversations.collectAsStateWithLifecycle()
 var tab by rememberSaveable{mutableStateOf(Tab.CHAT)}; val drawer=rememberDrawerState(DrawerValue.Closed); val scope=rememberCoroutineScope()
 ModalNavigationDrawer(drawerState=drawer,drawerContent={ModalDrawerSheet(drawerContainerColor=Color(0xFF04101B),modifier=Modifier.width(315.dp)){DrawerHeader();Text("CONVERSATIONS",Modifier.padding(20.dp,18.dp,20.dp,8.dp),color=Muted,fontWeight=FontWeight.Bold);LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){items(conversations,key={it.id}){c->ConversationCard(c,state.conversationId==c.id){vm.selectConversation(c.id);scope.launch{drawer.close()}}}}}}){
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Night,Deep,Night)))){Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.radialGradient(listOf(Color(0x2839DDF8),Color.Transparent))));Column(Modifier.fillMaxSize()){Header(state,live,{vm.refreshConversations();scope.launch{drawer.open()}},onOpenSettings);Nav(tab){tab=it};Box(Modifier.weight(1f)){when(tab){Tab.CHAT->Chat(state,vm);Tab.VOICE->LiveVoice(state,voice,live,speaking,vm,onRequestSpeech);Tab.MEMORY->Info("Memory","Gemma's persistent memory lives privately on Vision.");Tab.TOOLS->Info("Tools","Research, documents, images and location-aware tools live on Vision.")}};if(tab==Tab.CHAT)Composer(state,draft,vm,onRequestSpeech)} }
 }
}

@Composable private fun Header(s:ChatState,live:Boolean,menu:()->Unit,settings:()->Unit){Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp,10.dp),verticalAlignment=Alignment.CenterVertically){IconButton(menu){Text("☰",color=Cyan,style=MaterialTheme.typography.headlineSmall)};Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){Text("GEMMA",color=Cyan2,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.ExtraBold);Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(9.dp).clip(CircleShape).background(if(s.conversationId!=null)Green else Muted));Spacer(Modifier.width(7.dp));Text(if(live)"CONNECTED · LIVE MODE" else s.statusLine.uppercase(),color=if(s.conversationId!=null)Green else Muted,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)}};IconButton(settings){Icon(Icons.Default.Settings,"Settings",tint=Cyan)}}}
@Composable private fun Nav(active:Tab,on:(Tab)->Unit){Surface(color=Color(0xA9040B13),shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,Color(0x5539DDF8)),modifier=Modifier.padding(horizontal=16.dp,vertical=5.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){Tab.entries.forEach{t->TextButton(onClick={on(t)},modifier=Modifier.weight(1f)){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(when(t){Tab.CHAT->"◯";Tab.VOICE->"▥";Tab.MEMORY->"▤";Tab.TOOLS->"▦"},color=if(active==t)Cyan else Muted,style=MaterialTheme.typography.titleLarge);Text(t.name.lowercase().replaceFirstChar{it.uppercase()},color=if(active==t)Cyan else Muted,style=MaterialTheme.typography.labelSmall,fontWeight=if(active==t)FontWeight.Bold else FontWeight.Normal)}}}}}}

@Composable private fun LiveVoice(s:ChatState,voice:Boolean,live:Boolean,speaking:Boolean,vm:GemmaViewModel,mic:()->Unit){
 val transition=rememberInfiniteTransition(label="wave");val pulse by transition.animateFloat(0.45f,1f,infiniteRepeatable(tween(650),RepeatMode.Reverse),label="pulse")
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,12.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
  item{Surface(shape=RoundedCornerShape(28.dp),color=Panel,border=BorderStroke(1.dp,Color(0xAA27BCEB)),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(150.dp).shadow(18.dp,CircleShape).clip(CircleShape).background(Cyan).padding(3.dp).clip(CircleShape)){Image(painterResource(R.drawable.gemma_portrait),"Gemma",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)};Spacer(Modifier.width(18.dp));Column(Modifier.weight(1f)){Text("GEMMA",color=Cyan2,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text(when{speaking->"Speaking…";s.busy->s.activity.ifBlank{"Thinking…"};live->"Listening…";else->"Voice ready"},color=Color.White,style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(14.dp));Wave(pulse,speaking||s.busy||live);Spacer(Modifier.height(15.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Pill(if(live)"🎙  Mic on" else "🎙  Mic off",live);Pill(if(voice)"🔊  Voice on" else "🔇  Muted",voice)}}};Spacer(Modifier.height(16.dp));HorizontalDivider(color=Color(0x4427BCEB));Spacer(Modifier.height(8.dp);Text("LIVE CONVERSATION",color=Muted,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)}}}
  val recent=s.messages.takeLast(4);items(recent,key={it.key}){m->Bubble(m,vm,s.voiceReady)}
  item{Row(Modifier.fillMaxWidth().padding(top=4.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){ActionSquare("⌨","Type"){};Surface(onClick={if(live)mic() else vm.setLiveMode(true)},shape=CircleShape,color=Color(0xFF07547A),border=BorderStroke(3.dp,Cyan),shadowElevation=18.dp,modifier=Modifier.size(112.dp)){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("🎙",style=MaterialTheme.typography.headlineLarge);Text(if(live)"Speak" else "Start",color=Color.White,fontWeight=FontWeight.Bold)}};ActionSquare("×","Stop"){vm.stop()}};Text(if(live)"Tap to speak" else "Tap Start for Live Mode",Modifier.fillMaxWidth().padding(top=8.dp),textAlign=TextAlign.Center,color=Color.White)}
 }
}
@Composable private fun Wave(level:Float,active:Boolean){Row(Modifier.fillMaxWidth().height(42.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){repeat(13){i->val base=when(i%7){0->.25f;1->.45f;2->.7f;3->1f;4->.75f;5->.5f;else->.3f};Box(Modifier.width(3.dp).fillMaxHeight(if(active)base*level.coerceAtLeast(.55f) else .18f).clip(CircleShape).background(Cyan))}}}
@Composable private fun Pill(text:String,on:Boolean){Surface(shape=RoundedCornerShape(18.dp),color=Color(0x88102232),border=BorderStroke(1.dp,if(on)Cyan else Muted)){Text(text,Modifier.padding(10.dp,7.dp),color=if(on)Cyan2 else Muted,style=MaterialTheme.typography.labelSmall)}}
@Composable private fun ActionSquare(icon:String,label:String,on:()->Unit){Surface(onClick=on,shape=RoundedCornerShape(24.dp),color=Color(0xCC0B1825),border=BorderStroke(1.dp,Color(0x6639DDF8)),modifier=Modifier.size(88.dp)){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text(icon,color=Cyan,style=MaterialTheme.typography.headlineMedium);Text(label,color=Muted)}}}

@Composable private fun Chat(s:ChatState,vm:GemmaViewModel){if(s.messages.isEmpty())Column(Modifier.fillMaxSize().padding(30.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Box(Modifier.size(210.dp).clip(CircleShape).background(Cyan).padding(3.dp).clip(CircleShape)){Image(painterResource(R.drawable.gemma_portrait),"Gemma",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)};Spacer(Modifier.height(20.dp));Text("Gemma",color=Color.White,style=MaterialTheme.typography.headlineMedium);Text("Your private AI on Vision",color=Muted)}else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(15.dp)){if(s.hasOlder)item{OutlinedButton(vm::loadOlder){Text("Earlier messages")}};items(s.messages,key={it.key}){Bubble(it,vm,s.voiceReady)}}}
@Composable private fun Bubble(m:ChatMessage,vm:GemmaViewModel,voiceReady:Boolean){val mine=m.role=="user";val context=LocalContext.current;Column(Modifier.fillMaxWidth(),horizontalAlignment=if(mine)Alignment.End else Alignment.Start){Text(if(mine)"YOU" else "GEMMA",color=if(mine)Muted else Cyan,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold);Surface(shape=RoundedCornerShape(24.dp),color=if(mine)Color(0xFF15365A) else Panel,border=BorderStroke(1.dp,if(mine)Color(0x665A92D8) else Color(0x7739DDF8)),modifier=Modifier.fillMaxWidth(if(mine).82f else .94f)){Text(if(m.streaming&&m.text.isBlank())"…" else m.text,Modifier.padding(17.dp),color=Color(0xFFF3F7FB),style=MaterialTheme.typography.bodyLarge)};Row{if(!mine&&!m.streaming&&m.text.isNotBlank())TextButton({vm.speakMessage(m.text)},enabled=voiceReady){Text("▶  Play",color=if(voiceReady)Cyan else Muted)};TextButton({(context.getSystemService(Context.CLIPBOARD_SERVICE)as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Gemma",m.text))}){Text("▣  Copy",color=Muted)}}}}
@Composable private fun Composer(s:ChatState,draft:String,vm:GemmaViewModel,mic:()->Unit){Surface(color=Color(0xF702080E),shadowElevation=14.dp){Row(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp),verticalAlignment=Alignment.CenterVertically){IconButton(mic,enabled=!s.busy){Text("🎙",style=MaterialTheme.typography.titleLarge)};OutlinedTextField(draft,vm::onDraftChange,Modifier.weight(1f),placeholder={Text("Message Gemma…",color=Muted)},shape=RoundedCornerShape(28.dp),maxLines=4,colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Cyan,unfocusedBorderColor=Color(0xFF29465F),focusedContainerColor=Color(0x99101D2B),unfocusedContainerColor=Color(0x77101D2B)));Spacer(Modifier.width(8.dp));FilledIconButton(if(s.busy)vm::stop else vm::send,enabled=s.busy||(draft.isNotBlank()&&s.conversationId!=null),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFF0A6D91))){Text(if(s.busy)"■" else "➤",color=Color.White)}}}}
@Composable private fun DrawerHeader(){Row(Modifier.fillMaxWidth().statusBarsPadding().padding(20.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF0D3147)),contentAlignment=Alignment.Center){Text("G",color=Cyan,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge)};Spacer(Modifier.width(12.dp));Column{Text("GEMMA",color=Color.White,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge);Text("PRIVATE AI · VISION",color=Cyan,style=MaterialTheme.typography.labelSmall)}}}
@Composable private fun ConversationCard(c:Conversation,selected:Boolean,on:()->Unit){Surface(onClick=on,shape=RoundedCornerShape(16.dp),color=if(selected)Color(0xFF113047) else Color.Transparent,border=if(selected)BorderStroke(1.dp,Color(0x7739DDF8)) else null){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(8.dp).clip(CircleShape).background(if(selected)Green else Muted));Spacer(Modifier.width(10.dp));Text(c.title.ifBlank{"Conversation ${c.id}"},color=if(selected)Color.White else Cyan2)}}}
@Composable private fun Info(title:String,body:String){Column(Modifier.fillMaxSize().padding(30.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text(title,color=Color.White,style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(10.dp));Text(body,color=Muted,textAlign=TextAlign.Center)}}
