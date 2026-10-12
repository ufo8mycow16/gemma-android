package app.gemma.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gemma.core.ConversationInfo

/** The list of chats. They all live on Vision; Gemma's memory is shared across every one of them. */
@Composable
fun Sidebar(viewModel: GemmaViewModel, onPicked: () -> Unit) {
    val ui by viewModel.sidebar.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ConversationInfo?>(null) }
    var deleting by remember { mutableStateOf<ConversationInfo?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
        Button(onClick = { viewModel.newChat(onPicked) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("  New chat")
        }
        OutlinedTextField(
            value = ui.query, onValueChange = viewModel::searchChats, singleLine = true,
            placeholder = { Text("Search chats") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("chat-search"),
        )
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp)) }

        val hits = ui.hits
        if (hits != null) {
            if (hits.isEmpty()) Text("Nothing found.", color = Space.Muted, modifier = Modifier.padding(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(hits, key = { it.messageId }) { hit ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { if (viewModel.openChat(hit.conversationId, hit.title)) onPicked() }
                            .padding(10.dp)
                    ) {
                        Text(hit.title.ifBlank { "Chat" }, style = MaterialTheme.typography.labelMedium, color = Space.Blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(hit.snippet, style = MaterialTheme.typography.bodySmall, color = Space.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            if (ui.loading && ui.chats.isEmpty()) Text("Loading…", color = Space.Muted, modifier = Modifier.padding(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(ui.chats, key = { it.id }) { c ->
                    ChatRow(
                        c, open = c.id == chat.conversationId,
                        onOpen = { if (viewModel.openChat(c.id, c.title)) onPicked() },
                        onRename = { renaming = c }, onDelete = { deleting = c },
                    )
                }
            }
        }
    }

    renaming?.let { c ->
        var title by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { viewModel.renameChat(c.id, title); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove this chat?") },
            text = { Text("\"${c.title}\" will leave the list on every device. What Gemma has remembered from it stays in her memory.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteChat(c.id); deleting = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChatRow(c: ConversationInfo, open: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(if (open) Color(0x334C8DFF) else Color.Transparent)
            .clickable(onClick = onOpen).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.title.ifBlank { "Chat" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal)
            if (c.preview.isNotBlank()) {
                Text(c.preview, style = MaterialTheme.typography.bodySmall, color = Space.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Chat options", tint = Space.Muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
