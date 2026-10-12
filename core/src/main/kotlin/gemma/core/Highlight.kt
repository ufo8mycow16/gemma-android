package gemma.core

/*
 * Syntax colouring for code blocks, without any library and without changing a
 * single character: the pieces it returns always join back into the original text.
 * The same rules as the web client (Python and GDScript first, then the usual others).
 */

enum class Tok { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, FUNCTION, TYPE, DECORATOR, VARIABLE }

data class CodePiece(val text: String, val tok: Tok)

object Highlight {
    private val keywords = mapOf(
        "python" to "False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case self",
        "gdscript" to "if elif else for while match break continue pass return class class_name extends is in as self signal func static const enum var onready export setget tool yield preload await super void null true false and or not breakpoint",
        "js" to "break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new return super switch this throw try typeof var void while with yield async await of null true false undefined",
        "kotlin" to "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias val var when while by catch constructor data enum finally get import init internal lateinit open override private protected public sealed set suspend",
        "java" to "abstract boolean break byte case catch char class const continue default do double else enum extends final finally float for if implements import instanceof int interface long new package private protected public return short static super switch this throw throws try void volatile while null true false",
        "c" to "auto break case char const continue default do double else enum extern float for goto if inline int long register return short signed sizeof static struct switch typedef union unsigned void volatile while class namespace template typename public private protected virtual new delete nullptr true false using include define",
        "bash" to "if then else elif fi for while do done case esac function in return export local echo cd sudo source",
        "sql" to "select from where insert into values update set delete create table index join left right inner outer on group by order having limit as and or not null primary key",
        "rust" to "as break const continue crate else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while async await dyn",
        "go" to "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var nil true false",
    ).mapValues { it.value.split(' ').toSet() }

    private val alias = mapOf(
        "py" to "python", "python3" to "python", "gd" to "gdscript", "godot" to "gdscript", "javascript" to "js", "ts" to "js",
        "typescript" to "js", "jsx" to "js", "tsx" to "js", "json" to "js", "kt" to "kotlin", "kts" to "kotlin", "sh" to "bash",
        "shell" to "bash", "zsh" to "bash", "console" to "bash", "cpp" to "c", "c++" to "c", "h" to "c", "hpp" to "c",
        "cs" to "java", "csharp" to "java", "rs" to "rust", "golang" to "go",
    )
    private val types = Regex("^(int|float|bool|str|String|Vector2|Vector3|Node|Node2D|Node3D|Array|Dictionary|dict|list|tuple|set|void|Color|Resource|Object)$")

    fun language(lang: String): String = lang.lowercase().let { alias[it] ?: it }

    fun pieces(code: String, lang: String): List<CodePiece> {
        val l = language(lang)
        val kws = keywords[l] ?: return listOf(CodePiece(code, Tok.PLAIN))
        val hash = l == "python" || l == "gdscript" || l == "bash"
        val out = ArrayList<CodePiece>()
        val plain = StringBuilder()
        fun add(text: String, tok: Tok) {
            if (tok == Tok.PLAIN) { plain.append(text); return }
            if (plain.isNotEmpty()) { out += CodePiece(plain.toString(), Tok.PLAIN); plain.setLength(0) }
            out += CodePiece(text, tok)
        }
        fun isId(c: Char) = c.isLetterOrDigit() || c == '_'
        var i = 0
        val n = code.length
        fun lineEnd(from: Int) = code.indexOf('\n', from).let { if (it < 0) n else it }
        while (i < n) {
            val c = code[i]
            when {
                hash && c == '#' -> { val j = lineEnd(i); add(code.substring(i, j), Tok.COMMENT); i = j }
                !hash && c == '/' && i + 1 < n && code[i + 1] == '/' -> { val j = lineEnd(i); add(code.substring(i, j), Tok.COMMENT); i = j }
                !hash && c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                    val e = code.indexOf("*/", i + 2); val j = if (e < 0) n else e + 2; add(code.substring(i, j), Tok.COMMENT); i = j
                }
                l == "sql" && c == '-' && i + 1 < n && code[i + 1] == '-' -> { val j = lineEnd(i); add(code.substring(i, j), Tok.COMMENT); i = j }
                (l == "python" || l == "gdscript") && (code.startsWith("\"\"\"", i) || code.startsWith("'''", i)) -> {
                    val q = code.substring(i, i + 3); val e = code.indexOf(q, i + 3); val j = if (e < 0) n else e + 3
                    add(code.substring(i, j), Tok.STRING); i = j
                }
                c == '"' || c == '\'' || (c == '`' && l == "js") -> {
                    var j = i + 1
                    while (j < n && code[j] != c && code[j] != '\n') { if (code[j] == '\\') j++; j++ }
                    j = minOf(n, j + 1); add(code.substring(i, j), Tok.STRING); i = j
                }
                c == '@' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_') -> {
                    var j = i + 1; while (j < n && (isId(code[j]) || code[j] == '.')) j++
                    add(code.substring(i, j), Tok.DECORATOR); i = j
                }
                c.isDigit() && (i == 0 || !isId(code[i - 1])) -> {
                    var j = i; while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '.')) j++
                    add(code.substring(i, j), Tok.NUMBER); i = j
                }
                c.isLetter() || c == '_' || c == '$' && l != "bash" -> {
                    var j = i; while (j < n && (isId(code[j]) || code[j] == '$')) j++
                    val w = code.substring(i, j)
                    val kw = if (l == "sql") w.lowercase() in kws else w in kws
                    val tok = when {
                        kw -> Tok.KEYWORD
                        types.matches(w) || (l != "bash" && w[0].isUpperCase() && w.any { it.isLowerCase() }) -> Tok.TYPE
                        j < n && code[j] == '(' -> Tok.FUNCTION
                        else -> Tok.PLAIN
                    }
                    add(w, tok); i = j
                }
                l == "bash" && c == '$' -> {
                    var j = i + 1; while (j < n && (isId(code[j]) || code[j] == '{' || code[j] == '}')) j++
                    add(code.substring(i, j), Tok.VARIABLE); i = j
                }
                else -> { add(c.toString(), Tok.PLAIN); i++ }
            }
        }
        if (plain.isNotEmpty()) out += CodePiece(plain.toString(), Tok.PLAIN)
        return out
    }

    /** A sensible file name for saving a block with no name of its own. */
    fun defaultFilename(lang: String): String = "snippet." + when (language(lang)) {
        "python" -> "py"; "gdscript" -> "gd"; "js" -> if (lang.lowercase() == "json") "json" else "js"; "kotlin" -> "kt"
        "java" -> "java"; "c" -> "c"; "bash" -> "sh"; "sql" -> "sql"; "rust" -> "rs"; "go" -> "go"
        else -> when (lang.lowercase()) { "html" -> "html"; "css" -> "css"; "yaml", "yml" -> "yml"; "toml" -> "toml"; "md", "markdown" -> "md"; else -> "txt" }
    }
}
