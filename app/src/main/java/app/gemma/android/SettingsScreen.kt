package app.gemma.android

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: GemmaViewModel, onDone: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(viewModel.serverAddress) }
    // The stored key is never shown again; leaving this empty keeps the one already saved.
    var key by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val test by viewModel.test.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        viewModel.setPrefs(viewModel.prefs.value.copy(shareLocation = granted.values.any { it }))
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(
        containerColor = Space.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Space.Background),
                title = { Text("Settings") },
                navigationIcon = {
                    if (viewModel.isConfigured) {
                        IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Gemma lives on Vision. This app only needs to know where Vision is.", style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = address,
                onValueChange = { address = it; problem = null },
                label = { Text("Gemma server address") },
                placeholder = { Text("http://192.168.1.20:7860") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("address"),
            )
            if (address.trim().startsWith("http://")) {
                Text(
                    "This address is not encrypted. That is fine on your home network or over a private link such as Tailscale; don't use it over the open internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Space.Muted,
                )
            }

            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(if (viewModel.hasAccessKey) "Access key (saved; enter a new one to replace it)" else "Access key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("key"),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { viewModel.testConnection(address, key) }, enabled = !test.running) { Text("Test connection") }
                Button(onClick = {
                    problem = viewModel.saveSettings(address, key)
                    if (problem == null) onDone()
                }) { Text("Save") }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (test.message.isNotBlank()) {
                Text(test.message, color = if (test.ok) Space.Blue else MaterialTheme.colorScheme.error)
            }

            Section("Voice Settings") {
                Toggle("Voice Output", "Gemma's voice, made on Vision and played here", prefs.voiceOutput) {
                    viewModel.setPrefs(prefs.copy(voiceOutput = it))
                }
                Line("Voice", if (chat.voiceReady) "Gemma's own voice, from Vision" else "Not running on Vision right now")
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text("Speaking Style")
                    Text("How much expression Vision adds to her delivery", style = MaterialTheme.typography.bodySmall, color = Space.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        listOf("plain" to "Plain", "balanced" to "Balanced", "expressive" to "Expressive").forEach { (id, label) ->
                            FilterChip(selected = prefs.style == id, onClick = { viewModel.setPrefs(prefs.copy(style = id)) }, label = { Text(label) })
                        }
                    }
                }
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text("Response Speed   ${(prefs.speed * 100).roundToInt()}%")
                    Slider(
                        value = prefs.speed, valueRange = 0.8f..1.4f, steps = 5,
                        onValueChange = { viewModel.setPrefs(prefs.copy(speed = (it * 10).roundToInt() / 10f)) },
                    )
                }
                Toggle("Auto Play", "Play responses automatically", prefs.autoPlay) { viewModel.setPrefs(prefs.copy(autoPlay = it)) }
                Toggle("Interrupt with Speech", "In a voice conversation with earphones in, talking over her stops her. On the loudspeaker, tap to interrupt.", prefs.interrupt) {
                    viewModel.setPrefs(prefs.copy(interrupt = it))
                }
            }

            Section("Appearance") {
                Line("Theme", "Deep Space")
                Toggle("Compact layout", "Tighter message bubbles", prefs.compact) { viewModel.setPrefs(prefs.copy(compact = it)) }
            }

            Section("This phone") {
                Toggle("Share location with Gemma", "Sent with each message so she knows where you are. Not stored in her memory.", prefs.shareLocation) { on ->
                    if (!on) viewModel.setPrefs(prefs.copy(shareLocation = false))
                    else if (context.allowed(Manifest.permission.ACCESS_FINE_LOCATION) || context.allowed(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        viewModel.setPrefs(prefs.copy(shareLocation = true))
                    } else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                Toggle("Notifications", "Reminders and messages from Gemma. Checked about every 15 minutes while the app is closed.", prefs.notifications) { on ->
                    viewModel.setPrefs(prefs.copy(notifications = on))
                    if (on && android.os.Build.VERSION.SDK_INT >= 33 && !context.allowed(Manifest.permission.POST_NOTIFICATIONS)) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            Section("Troubleshooting") {
                Toggle("Developer details", "Connection and turn events", prefs.developer) { viewModel.setPrefs(prefs.copy(developer = it)) }
                if (prefs.developer) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Server: ${viewModel.serverAddress.ifBlank { "(not set)" }}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text("State: ${chat.statusLine}; conversation ${chat.conversationId ?: "-"}; turn ${chat.activeTurnId ?: "-"}",
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text("Model ${if (chat.modelReady) "ready" else "unavailable"}; voice ${if (chat.voiceReady) "ready" else "unavailable"}",
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            diagnostics.takeLast(60).forEach { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = Space.Surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Space.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Line(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Space.Muted)
    }
}
