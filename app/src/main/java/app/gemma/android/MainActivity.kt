package app.gemma.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class MainActivity : ComponentActivity() {
    private val viewModel: GemmaViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GemmaTheme {
                var showSettings by rememberSaveable { mutableStateOf(!viewModel.isConfigured) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }
                if (showSettings) {
                    BackHandler(enabled = viewModel.isConfigured) { showSettings = false }
                    SettingsScreen(viewModel, onDone = { showSettings = false })
                } else ChatScreen(viewModel, onOpenSettings = { showSettings = true })
            }
        }
    }
}

private val GemmaColors = darkColorScheme(
    primary = Color(0xFF65D9FF), onPrimary = Color(0xFF001E2B),
    primaryContainer = Color(0xFF173C58), onPrimaryContainer = Color(0xFFD7F3FF),
    secondary = Color(0xFF9E91FF), background = Color(0xFF030711),
    surface = Color(0xFF07101C), surfaceVariant = Color(0xFF111C2C),
    onBackground = Color(0xFFF2F6FC), onSurface = Color(0xFFF2F6FC),
    onSurfaceVariant = Color(0xFFB7C7DA), error = Color(0xFFFF8C9A),
)

@Composable fun GemmaTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = GemmaColors, content = content)
