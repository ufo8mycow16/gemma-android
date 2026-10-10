package app.gemma.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: GemmaViewModel by viewModels()
    private fun acceptShare(i:Intent?){ if(i?.action==Intent.ACTION_SEND && i.type=="text/plain") i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf{it.isNotBlank()}?.let{viewModel.onDraftChange(it)} }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);acceptShare(intent)}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);acceptShare(intent);enableEdgeToEdge();setContent{GemmaTheme{
        var showSettings by rememberSaveable{mutableStateOf(!viewModel.isConfigured)};val listenNonce by viewModel.listenNonce.collectAsState()
        val speechLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->if(result.resultCode==Activity.RESULT_OK)viewModel.onSpeechRecognized(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()) else if(viewModel.liveMode.value)viewModel.setLiveMode(false)}
        val requestSpeech={val si=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag());putExtra(RecognizerIntent.EXTRA_PROMPT,"Talk to Gemma");putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)};runCatching{speechLauncher.launch(si)};Unit}
        LaunchedEffect(listenNonce){if(listenNonce>0&&!showSettings)requestSpeech()};LifecycleEventEffect(Lifecycle.Event.ON_RESUME){viewModel.onForeground()}
        if(showSettings){BackHandler(enabled=viewModel.isConfigured){showSettings=false};SettingsScreen(viewModel,onDone={showSettings=false})}else FinalScreen(viewModel,onOpenSettings={showSettings=true},onRequestSpeech=requestSpeech)
    }}}
}
private val GemmaColors=darkColorScheme(primary=Color(0xFF65D9FF),onPrimary=Color(0xFF001E2B),primaryContainer=Color(0xFF173C58),onPrimaryContainer=Color(0xFFD7F3FF),secondary=Color(0xFF9E91FF),background=Color(0xFF030711),surface=Color(0xFF07101C),surfaceVariant=Color(0xFF111C2C),onBackground=Color(0xFFF2F6FC),onSurface=Color(0xFFF2F6FC),onSurfaceVariant=Color(0xFFB7C7DA),error=Color(0xFFFF8C9A))
@Composable fun GemmaTheme(content:@Composable()->Unit)=MaterialTheme(colorScheme=GemmaColors,content=content)
