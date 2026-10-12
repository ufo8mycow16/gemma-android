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

/** Gemma's words with their formatting shown: bold, lists, links, code. The words themselves are never changed. */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
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
                is MdBlock.Code -> Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF05080F))
                        .horizontalScroll(rememberScrollState()).padding(10.dp)
                ) {
                    Text(block.text, color = color, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(Space.Line))
            }
        }
    }
}
