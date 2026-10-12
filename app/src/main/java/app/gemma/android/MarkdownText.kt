package app.gemma.android

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import gemma.core.Highlight
import gemma.core.Tok
import gemma.core.Markdown
import gemma.core.MdBlock
import gemma.core.MdSpan

private fun styled(spans: List<MdSpan>): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.SemiBold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) Color(0x33FFFFFF) else Color.Unspecified,
        )
        val link = span.link
        if (link != null) {
            withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = Space.Blue, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(span.text) }
            }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}

private val TokColor = mapOf(
    Tok.KEYWORD to Color(0xFFC792EA), Tok.STRING to Color(0xFFC3E88D), Tok.COMMENT to Color(0xFF5F6D8C),
    Tok.NUMBER to Color(0xFFF78C6C), Tok.FUNCTION to Color(0xFF82AAFF), Tok.TYPE to Color(0xFFFFCB6B),
    Tok.DECORATOR to Color(0xFF89DDFF), Tok.VARIABLE to Color(0xFFFF9CAC),
)

/** Code with colours; the characters are exactly the code's own. */
fun highlighted(code: String, lang: String): AnnotatedString = buildAnnotatedString {
    for (p in Highlight.pieces(code, lang)) {
        val c = TokColor[p.tok]
        if (c == null) append(p.text)
        else withStyle(SpanStyle(color = c, fontStyle = if (p.tok == Tok.COMMENT) FontStyle.Italic else null)) { append(p.text) }
    }
}

/** A code block: language and file name, Copy and Save, colours, sideways scrolling instead of wrapping. */
@Composable
fun CodeBlock(code: String, lang: String, filename: String, open: Boolean, onSave: ((String, String) -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    val lines = remember(code) { code.count { it == '\n' } + 1 }
    var expanded by remember { mutableStateOf(lines <= 40) }
    val text = remember(code, lang) { highlighted(code, lang) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF070C18)).border(1.dp, Color(0xFF1D2A48), RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF0C1428)).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "text" }, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = Color(0xFFA9BBE6))
            if (filename.isNotBlank()) Text("  $filename", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = Space.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            if (open) Text("writing…", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF5B84B), modifier = Modifier.padding(end = 6.dp))
            if (onSave != null && !open) IconButton(onClick = { onSave(filename.ifBlank { Highlight.defaultFilename(lang) }, code) }, modifier = Modifier.size(36.dp)) {
                Icon(Glyph.Download, contentDescription = "Save code as a file", tint = Space.Muted, modifier = Modifier.size(17.dp))
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }, modifier = Modifier.size(36.dp).testTag("copy-code")) {
                Icon(Glyph.Copy, contentDescription = if (copied) "Code copied" else "Copy code", tint = if (copied) Space.Blue else Space.Muted, modifier = Modifier.size(16.dp))
            }
        }
        Box(Modifier.fillMaxWidth().then(if (expanded) Modifier else Modifier.heightIn(max = 260.dp)).horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(text, color = Space.Text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, softWrap = false)
        }
        if (lines > 40) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "Show less" else "Show all $lines lines", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Gemma's words with their formatting shown: bold, lists, links, code. The words themselves are never changed. */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier, onSaveCode: ((String, String) -> Unit)? = null) {
    val blocks = remember(text) { Markdown.parse(text) }
    val body = MaterialTheme.typography.bodyLarge
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> Text(styled(block.spans), color = color, style = body)
                is MdBlock.Heading -> Text(
                    styled(block.spans), color = color,
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                is MdBlock.Item -> Row(Modifier.padding(start = (block.indent * 14).dp)) {
                    Text(block.marker, color = color, style = body, modifier = Modifier.width(if (block.marker == "•") 16.dp else 26.dp))
                    Text(styled(block.spans), color = color, style = body)
                }
                is MdBlock.Quote -> Row {
                    Box(Modifier.width(3.dp).height(20.dp).background(Space.Line))
                    Text(styled(block.spans), color = Space.Muted, style = body, modifier = Modifier.padding(start = 8.dp))
                }
                is MdBlock.Code -> CodeBlock(block.text, block.lang, block.filename, block.open, onSaveCode)
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(Space.Line))
            }
        }
    }
}
