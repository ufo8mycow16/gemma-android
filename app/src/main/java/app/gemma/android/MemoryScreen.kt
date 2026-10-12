package app.gemma.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gemma.core.MemoryItem

private val categories = listOf("all" to "All", "people" to "People", "projects" to "Projects", "preferences" to "Preferences", "other" to "Other")

/** What Gemma remembers, read straight from Vision. Removing an item here removes it from her memory. */
@Composable
fun MemoryScreen(viewModel: GemmaViewModel) {
    val ui by viewModel.memory.collectAsStateWithLifecycle()
    var forgetting by remember { mutableStateOf<MemoryItem?>(null) }
    LaunchedEffect(Unit) { viewModel.loadMemory() }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 14.dp)) {
        Text("Memory", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        OutlinedTextField(
            value = ui.query, onValueChange = { viewModel.loadMemory(query = it) }, singleLine = true,
            placeholder = { Text("Search what Gemma remembers") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().testTag("memory-search"),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { (id, label) ->
                val count = ui.page?.counts?.get(id)
                FilterChip(
                    selected = ui.category == id,
                    onClick = { viewModel.loadMemory(category = id) },
                    label = { Text(if (count != null) "$label  $count" else label) },
                )
            }
        }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        val page = ui.page
        if (page == null && ui.loading) Text("Loading…", color = Space.Muted, modifier = Modifier.padding(8.dp))

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val summary = page?.summary
            if (summary != null && ui.category == "all" && ui.query.isBlank()) {
                item(key = "summary") {
                    Surface(shape = RoundedCornerShape(14.dp), color = Space.Raised, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Her running summary of your main chat", style = MaterialTheme.typography.labelMedium, color = Space.Blue)
                            Text(summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            if (page != null && page.memories.isEmpty() && !ui.loading) {
                item(key = "none") { Text("Nothing here.", color = Space.Muted, modifier = Modifier.padding(8.dp)) }
            }
            items(page?.memories ?: emptyList(), key = { it.id }) { m ->
                Surface(shape = RoundedCornerShape(14.dp), color = Space.Surface, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (m.subject.isNotBlank()) Text(m.subject, style = MaterialTheme.typography.labelMedium, color = Space.Blue)
                            Text(m.fact, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = { forgetting = m }) { Icon(Icons.Filled.Delete, contentDescription = "Forget this", tint = Space.Muted) }
                    }
                }
            }
        }
    }

    forgetting?.let { m ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget this?") },
            text = { Text(m.fact) },
            confirmButton = { TextButton(onClick = { viewModel.forget(m.id); forgetting = null }) { Text("Forget", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Keep") } },
        )
    }
}
