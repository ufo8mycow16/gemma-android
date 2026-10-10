package app.gemma.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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

                // Coming back to the app (screen on, switching back, network returned): check in with Vision again.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }

                if (showSettings) {
                    BackHandler(enabled = viewModel.isConfigured) { showSettings = false }
                    SettingsScreen(viewModel, onDone = { showSettings = false })
                } else {
                    ChatScreen(viewModel, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}

/** Follows the phone's light/dark setting, and its own colours where Android offers them. */
@Composable
fun GemmaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
