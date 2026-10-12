package gemma.core

/*
 * Just enough Markdown to show Gemma's replies the way she wrote them: bold,
 * italics, code, links, headings, lists, quotes. It never changes her words;
 * anything it does not recognise is shown exactly as written.
 */

data class MdSpan(
    val text: String, val bold: Boolean = false, val italic: Boolean = false,
    val code: Boolean = false, val link: String? = null,
)

sealed class MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock()
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock()
    /** `marker` is "•" for a bullet or "3." for a numbered item. */
    data class Item(val indent: Int, val marker: String, val spans: List<MdSpan>) : MdBlock()
    data class Quote(val spans: List<MdSpan>) : MdBlock()
    /** A code block. `lang` and `filename` come from the fence line (```python app.py); `open` = still being written. */
    data class Code(val text: String, val lang: String = "", val filename: String = "", val open: Boolean = false) : MdBlock()
    object Rule : MdBlock()
}

object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*$")
    private val bullet = Regex("^(\\s*)[-*•+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun parse(text: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                out += MdBlock.Paragraph(inline(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                // An unclosed fence is a block still being written: it shows as code at once, never as stray backticks.
                val end = (i + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("```") }
                flush()
                val (lang, name) = fenceInfo(trimmed.removePrefix("```").trimStart('`'))
                val body = lines.subList(i + 1, end ?: lines.size).joinToString("\n")
                out += MdBlock.Code(body, lang, name.ifEmpty { filenameComment(body) }, open = end == null)
                i = (end ?: lines.size) + 1
                continue
            }
            val h = heading.find(line)
            val b = bullet.find(line)
            val n = numbered.find(line)
            when {
                trimmed.isEmpty() -> flush()
                rule.matches(line) -> { flush(); out += MdBlock.Rule }
                h != null -> { flush(); out += MdBlock.Heading(h.groupValues[1].length, inline(h.groupValues[2])) }
                b != null -> { flush(); out += MdBlock.Item(b.groupValues[1].length / 2, "•", inline(b.groupValues[2])) }
                n != null -> { flush(); out += MdBlock.Item(n.groupValues[1].length / 2, n.groupValues[2] + ".", inline(n.groupValues[3])) }
                trimmed.startsWith(">") -> { flush(); out += MdBlock.Quote(inline(trimmed.removePrefix(">").trim())) }
                else -> paragraph += line.trimEnd()
            }
            i++
        }
        flush()
        return out
    }

    private val fileLike = Regex("^[\\w./-]+\\.[A-Za-z0-9]{1,6}$")
    private val fileComment = Regex("^\\s*(?:#|//)\\s*(?:file:\\s*)?([\\w./-]+\\.(?:py|gd|kt|kts|java|js|ts|c|cpp|h|sh|json|toml|ya?ml|md|tscn|cfg|html|css|sql|rs|go))\\s*$", RegexOption.IGNORE_CASE)

    /** "python app.py", "gdscript title=player.gd" or "python" → (language, filename). */
    fun fenceInfo(info: String): Pair<String, String> {
        val parts = info.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "" to ""
        var lang = parts[0]
        var name = ""
        for (p in parts.drop(1)) {
            val v = p.substringAfter('=', p).trim('"', '\'')
            if (fileLike.matches(v)) { name = v; break }
        }
        if (name.isEmpty() && fileLike.matches(lang) && lang.contains('.')) { name = lang; lang = lang.substringAfterLast('.') }
        return lang.lowercase() to name
    }

    private fun filenameComment(body: String): String = fileComment.find(body.lineSequence().firstOrNull() ?: "")?.groupValues?.get(1) ?: ""

    fun inline(text: String): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        scan(text, bold = false, italic = false, out = out)
        return out
    }

    private fun safeLink(url: String) = url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")

    private fun scan(s: String, bold: Boolean, italic: Boolean, out: MutableList<MdSpan>) {
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out += MdSpan(plain.toString(), bold, italic)
                plain.setLength(0)
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] in "*_`[]\\#") {
                plain.append(s[i + 1]); i += 2; continue
            }
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush(); out += MdSpan(s.substring(i + 1, end), bold, italic, code = true); i = end + 1; continue
                }
            }
            if ((s.startsWith("**", i) || s.startsWith("__", i)) && i + 2 < s.length && !s[i + 2].isWhitespace()) {
                val end = s.indexOf(s.substring(i, i + 2), i + 2)
                if (end > i + 2) {
                    flush(); scan(s.substring(i + 2, end), true, italic, out); i = end + 2; continue
                }
            }
            if ((c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != c &&
                !(c == '_' && i > 0 && s[i - 1].isLetterOrDigit())
            ) {
                var end = s.indexOf(c, i + 1)
                while (end != -1) {
                    val doubled = end + 1 < s.length && s[end + 1] == c
                    val insideWord = c == '_' && end + 1 < s.length && s[end + 1].isLetterOrDigit()
                    if (doubled) { end = s.indexOf(c, end + 2); continue }
                    if (s[end - 1].isWhitespace() || insideWord) { end = s.indexOf(c, end + 1); continue }
                    break
                }
                if (end > i + 1 && !s.substring(i + 1, end).contains("\n\n")) {
                    flush(); scan(s.substring(i + 1, end), bold, true, out); i = end + 1; continue
                }
            }
            if (c == '[') {
                val close = s.indexOf("](", i + 1)
                val endParen = if (close > i) s.indexOf(')', close + 2) else -1
                if (close > i + 1 && endParen > close + 2 && !s.substring(i + 1, close).contains('\n')) {
                    val url = s.substring(close + 2, endParen).trim()
                    if (safeLink(url)) {
                        flush(); out += MdSpan(s.substring(i + 1, close), bold, italic, link = url); i = endParen + 1; continue
                    }
                }
            }
            if ((s.startsWith("http://", i) || s.startsWith("https://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit())) {
                var end = i
                while (end < s.length && !s[end].isWhitespace() && s[end] !in "<>\"") end++
                while (end > i && s[end - 1] in ".,;:!?)]'*_") end--
                if (end > i + 10) {
                    flush(); val url = s.substring(i, end); out += MdSpan(url, bold, italic, link = url); i = end; continue
                }
            }
            plain.append(c); i++
        }
        flush()
    }
}
