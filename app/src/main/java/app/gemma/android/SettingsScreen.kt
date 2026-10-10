package app.gemma.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: GemmaViewModel, onDone: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(viewModel.serverAddress) }
    // The stored key is never shown again; leaving this empty keeps the one already saved.
    var key by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val test by viewModel.test.collectAsStateWithLifecycle()
    val developer by viewModel.developerMode.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
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
                    "This address is not encrypted. That is fine on your home network; away from home, use a private network or an https address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text(test.message, color = if (test.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Developer details")
                    Text("Connection and turn events, for troubleshooting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = developer, onCheckedChange = viewModel::setDeveloperMode)
            }
            if (developer) {
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
