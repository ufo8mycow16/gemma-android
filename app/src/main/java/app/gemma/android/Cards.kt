package app.gemma.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import gemma.core.Approval
import gemma.core.Artifact
import gemma.core.DocProgress
import gemma.core.Highlight
import gemma.core.Job

/*
 * The cards under a message: files, pictures, background tasks, document progress and
 * approvals. Every number on them comes from Vision; nothing here counts on its own.
 */

private val CardShape = RoundedCornerShape(14.dp)
private val CardColor = Color(0xFF0C1322)

internal fun sizeText(n: Long): String = when {
    n < 0 -> ""
    n < 1024 -> "$n B"
    n < 1048576 -> "${(n / 1024.0).let { if (it < 10) "%.1f".format(it) else "%.0f".format(it) }} KB"
    else -> "%.1f MB".format(n / 1048576.0)
}

@Composable
private fun Card(modifier: Modifier = Modifier, border: Color = Space.Line, content: @Composable () -> Unit) {
    Surface(shape = CardShape, color = CardColor, modifier = modifier.fillMaxWidth().widthIn(max = 520.dp).border(1.dp, border, CardShape)) { content() }
}

@Composable
fun ArtifactCard(a: Artifact, viewModel: GemmaViewModel) {
    if (a.isImage) {
        ImageCard(a, viewModel)
        return
    }
    val by = when (a.createdBy) { "user" -> "You attached"; "gemma" -> "Gemma made"; "claude_code" -> "Claude Code"; "build_tool" -> "Build"; else -> "" }
    Card(Modifier.testTag("file-card")) {
        Row(Modifier.clickable { viewModel.openArtifact(a) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF16244A)), contentAlignment = Alignment.Center) {
                Text(a.filename.substringAfterLast('.', "file").take(4).uppercase(), style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace, color = Color(0xFFA9C4FF))
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(a.filename, color = Space.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(listOf(sizeText(a.size), by, a.description).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = Space.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { viewModel.saveArtifact(a) }) { Icon(Glyph.Download, contentDescription = "Save ${a.filename}", tint = Space.Muted) }
        }
    }
}

@Composable
fun ImageCard(a: Artifact, viewModel: GemmaViewModel) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, a.url) { value = viewModel.serverImage(a.url) }
    var big by remember { mutableStateOf(false) }
    Card(Modifier.testTag("image-card")) {
        Column {
            val b = bitmap
            if (b != null) {
                Image(b.asImageBitmap(), contentDescription = a.prompt ?: a.filename, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clickable { big = true })
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
            }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.prompt ?: a.description.ifBlank { a.filename }, style = MaterialTheme.typography.labelMedium, color = Space.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.editPictureDraft(a) }) { Icon(Glyph.Edit, contentDescription = "Ask Gemma to edit this picture", tint = Space.Muted, modifier = Modifier.size(18.dp)) }
                IconButton(onClick = { viewModel.saveArtifact(a) }) { Icon(Glyph.Download, contentDescription = "Save picture", tint = Space.Muted, modifier = Modifier.size(18.dp)) }
            }
        }
    }
    val b = bitmap
    if (big && b != null) {
        Dialog(onDismissRequest = { big = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { big = false }, contentAlignment = Alignment.Center) {
                Image(b.asImageBitmap(), contentDescription = a.prompt ?: a.filename, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun JobCard(j: Job, viewModel: GemmaViewModel) {
    val pct = j.percent
    val label = when (j.state) {
        "done" -> "Done"; "failed" -> "Failed"; "cancelled" -> "Cancelled"; "interrupted" -> "Interrupted"
        "queued" -> "Queued"; else -> pct?.let { "$it%" } ?: "…"
    }
    Card(Modifier.testTag("job-card")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(j.title, color = Space.Text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(label, color = if (j.state == "failed") Space.Red else Space.Text, fontFamily = FontFamily.Monospace,
                    style = if (pct != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("job-percent"))
            }
            if (j.running) {
                if (pct != null) LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val detail = if (j.state == "failed") j.error ?: "" else j.progressText
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.labelMedium, color = Space.Muted)
            j.summary?.let { Text(it.take(1200), style = MaterialTheme.typography.bodyMedium, color = Space.Text) }
            if (j.filesChanged.isNotEmpty()) Text("Changed: " + j.filesChanged.joinToString(", "), style = MaterialTheme.typography.labelMedium, color = Space.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (j.running) OutlinedButton(onClick = { viewModel.cancelJob(j.id) }, enabled = !j.cancelRequested) {
                    Text(if (j.cancelRequested) "Cancelling…" else "Cancel")
                }
                if (j.workspace != null) OutlinedButton(onClick = { viewModel.draftAbout("Show me the changes in workspace ${j.workspace}.") }) { Text("Review changes") }
            }
        }
    }
}

private val DocStages = listOf("reading" to "Reading", "chunking" to "Chunking", "indexing" to "Indexing", "analysing" to "Analysing", "synthesising" to "Synthesising")

/** PROCESSING with the server's own percentage and section counts, then COMPLETE or FAILED. */
@Composable
fun DocumentCard(d: DocProgress, outcome: String?) {
    val headline = when (outcome) { "complete" -> "COMPLETE"; "failed" -> "FAILED"; else -> d.percent?.let { "$it%" } ?: "PROCESSING" }
    Card(Modifier.testTag("document-card")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (d.name.isNotBlank()) "Processing ${d.name}" else "Processing document", color = Space.Text, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(headline, fontFamily = FontFamily.Monospace, color = if (outcome == "failed") Space.Red else Space.Text,
                    style = if (outcome == null && d.percent != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge)
            }
            when {
                outcome == "complete" -> LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                outcome == null && d.percent != null -> LinearProgressIndicator(progress = { d.percent!! / 100f }, modifier = Modifier.fillMaxWidth())
                outcome == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row {
                Text(d.label.replace(Regex("\\s·\\s\\d+%$"), ""), style = MaterialTheme.typography.labelMedium, color = Space.Muted, modifier = Modifier.weight(1f))
                if (d.total != null) Text("${d.done ?: 0} of ${d.total} sections", style = MaterialTheme.typography.labelMedium, color = Space.Muted)
            }
            val at = DocStages.indexOfFirst { it.first == d.stage }.let { if (outcome == "complete") DocStages.size else it }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DocStages.forEachIndexed { i, (_, name) ->
                    Text((if (i < at) "✓ " else "") + name, style = MaterialTheme.typography.labelSmall,
                        color = when { i < at -> Color(0xFF3ECF8E); i == at -> Space.Text; else -> Space.Muted })
                }
            }
        }
    }
}

@Composable
fun ApprovalCard(p: Approval, viewModel: GemmaViewModel) {
    var pressed by remember(p.state) { mutableStateOf(false) }
    Card(Modifier.testTag("approval-card"), border = Color(0xFF3A4F86)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, color = Space.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${p.risk} risk", style = MaterialTheme.typography.labelSmall, color = if (p.risk == "high") Color(0xFFFF9A9A) else Color(0xFFF5B84B))
            }
            p.fields.forEach { (k, v) ->
                Row {
                    Text(k, style = MaterialTheme.typography.labelMedium, color = Space.Muted, modifier = Modifier.widthIn(min = 84.dp).padding(end = 10.dp))
                    Text(v, style = MaterialTheme.typography.bodyMedium, color = Space.Text)
                }
            }
            if (p.body.isNotBlank()) {
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF070C18)) {
                    SelectionContainer {
                        Text(p.body.take(3000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Space.Text,
                            modifier = Modifier.padding(10.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
            Text("${p.plugin} · ${p.tool}", style = MaterialTheme.typography.labelSmall, color = Space.Muted)
            if (p.pending) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pressed = true; viewModel.decide(p.id, allow = true) }, enabled = !pressed) { Text("Allow once") }
                    OutlinedButton(onClick = { pressed = true; viewModel.decide(p.id, allow = true, always = true) }, enabled = !pressed) { Text("Always") }
                    TextButton(onClick = { pressed = true; viewModel.decide(p.id, allow = false) }, enabled = !pressed) { Text("Cancel") }
                }
            } else {
                val (text, color) = when (p.state) {
                    "executed" -> "Done" to Color(0xFF3ECF8E)
                    "approved" -> "Approved — running…" to Color(0xFF3ECF8E)
                    "failed" -> ("It didn't work" + (p.resultError?.let { ": $it" } ?: "")) to Space.Red
                    "denied" -> "Cancelled — nothing was done" to Space.Muted
                    "expired" -> "Expired — nothing was done" to Space.Muted
                    else -> p.state to Space.Muted
                }
                Text(text, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("approval-state"))
            }
        }
    }
}

/** A file from Vision, read on the phone: code with colours, text as text. */
@Composable
fun ArtifactViewer(v: ViewerUi, viewModel: GemmaViewModel) {
    Dialog(onDismissRequest = { viewModel.closeViewer() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Space.Background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(v.artifact.filename, style = MaterialTheme.typography.titleMedium, color = Space.Text, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { viewModel.saveArtifact(v.artifact) }) { Text("Save") }
                    TextButton(onClick = { viewModel.closeViewer() }) { Text("Close") }
                }
                when {
                    v.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    v.error != null -> Text(v.error, color = Space.Red)
                    else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        val lang = v.artifact.language ?: v.artifact.filename.substringAfterLast('.', "")
                        if (lang == "markdown" || v.artifact.filename.endsWith(".md")) MarkdownText(v.text ?: "", Space.Text)
                        else CodeBlock(v.text ?: "", Highlight.language(lang), v.artifact.filename, open = false, onSave = null)
                    }
                }
            }
        }
    }
}

