package org.ward.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** Theme-aware colours used by the lightweight markdown code highlighter. */
data class HighlightPalette(
    val plain: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val function: Color,
    val type: Color,
    val tag: Color,
    val attribute: Color,
    val punctuation: Color
)

private data class LanguageProfile(
    val keywords: Set<String>,
    val lineComments: List<String> = emptyList(),
    val blockComment: Pair<String, String>? = null,
    val singleQuotes: Boolean = true,
    val backticks: Boolean = false,
    val annotations: Boolean = false,
    val variables: Boolean = false,
    val xml: Boolean = false
)

private val commonKeywords = setOf(
    "if", "else", "for", "while", "do", "return", "break", "continue", "try", "catch", "finally",
    "throw", "new", "this", "super", "null", "true", "false", "in", "is", "as", "import", "package",
    "class", "interface", "enum", "object", "fun", "val", "var", "when", "match", "case", "switch",
    "default", "static", "const", "let", "function", "def", "from", "with", "yield", "lambda",
    "pass", "raise", "except", "elif", "async", "await", "struct", "impl", "trait", "pub", "use",
    "mod", "fn", "mut", "ref", "loop", "where", "type", "unsafe", "extern", "crate", "go", "defer",
    "chan", "map", "range", "select", "func", "namespace", "template", "typename", "public",
    "private", "protected", "internal", "override", "open", "abstract", "final", "sealed", "data",
    "companion", "init", "constructor", "suspend", "lateinit", "inline", "operator", "infix",
    "typealias", "annotation", "vararg", "reified", "crossinline", "noinline", "by", "get", "set"
)

private val kotlinKeywords = commonKeywords + setOf("it", "field", "out")
private val pythonKeywords = commonKeywords + setOf("and", "or", "not", "None", "True", "False", "nonlocal", "global", "assert")
private val jsKeywords = commonKeywords + setOf("undefined", "typeof", "instanceof", "delete", "void", "export", "extends", "implements")
private val cKeywords = commonKeywords + setOf("int", "long", "short", "char", "float", "double", "void", "signed", "unsigned", "sizeof", "typedef", "union", "goto", "volatile", "register", "auto")
private val rustKeywords = commonKeywords + setOf("Self", "self", "move", "dyn", "async", "await", "macro", "macro_rules", "mod", "crate")
private val goKeywords = commonKeywords + setOf("package", "defer", "fallthrough", "goroutine", "range", "select", "chan", "map", "struct", "interface")
private val sqlKeywords = setOf(
    "select", "from", "where", "insert", "into", "values", "update", "delete", "create", "table",
    "alter", "drop", "index", "join", "left", "right", "inner", "outer", "on", "group", "by",
    "order", "having", "limit", "offset", "and", "or", "not", "null", "is", "in", "as", "distinct",
    "union", "all", "case", "when", "then", "else", "end", "primary", "key", "foreign", "references",
    "default", "unique", "check", "view", "trigger", "procedure", "function", "begin", "commit",
    "rollback", "transaction", "grant", "revoke"
)
private val shellKeywords = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
    "function", "in", "select", "time", "coproc", "return", "exit", "export", "local", "readonly",
    "declare", "unset", "shift", "set", "trap", "source", "alias", "echo", "printf", "cd", "pwd"
)
private val jsonKeywords = setOf("true", "false", "null")
private val genericKeywords = commonKeywords

private fun profileFor(language: String): LanguageProfile {
    val lang = language.trim().lowercase().substringBefore(' ').substringBefore('{').substringBefore(':')
    return when (lang) {
        "kt", "kotlin", "kts" -> LanguageProfile(kotlinKeywords, listOf("//"), "/*" to "*/", annotations = true)
        "java", "c", "h", "cpp", "c++", "cc", "cxx", "cs", "csharp", "objc" ->
            LanguageProfile(cKeywords, listOf("//"), "/*" to "*/", annotations = true)
        "py", "python", "py3" -> LanguageProfile(pythonKeywords, listOf("#"))
        "js", "javascript", "jsx", "ts", "typescript", "tsx", "mjs", "cjs" ->
            LanguageProfile(jsKeywords, listOf("//"), "/*" to "*/", backticks = true)
        "json", "jsonc", "json5" -> LanguageProfile(jsonKeywords, if (lang == "jsonc") listOf("//") else emptyList(), backticks = false)
        "xml", "html", "xhtml", "svg", "plist" ->
            LanguageProfile(emptySet(), listOf(), "<!--" to "-->", xml = true)
        "sh", "bash", "shell", "zsh", "fish", "console" ->
            LanguageProfile(shellKeywords, listOf("#"), variables = true)
        "sql", "mysql", "postgres", "postgresql", "sqlite" ->
            LanguageProfile(sqlKeywords, listOf("--"), "/*" to "*/")
        "rs", "rust" -> LanguageProfile(rustKeywords, listOf("//"), "/*" to "*/", singleQuotes = false)
        "go", "golang" -> LanguageProfile(goKeywords, listOf("//"), "/*" to "*/", backticks = true)
        "css", "scss", "less" -> LanguageProfile(commonKeywords, listOf("//"), "/*" to "*/")
        "yaml", "yml", "toml", "ini", "conf", "properties" -> LanguageProfile(commonKeywords, listOf("#"))
        "diff", "patch" -> LanguageProfile(commonKeywords, listOf())
        else -> LanguageProfile(genericKeywords, listOf("//", "#"), "/*" to "*/")
    }
}

/**
 * Highlights a fenced code block using a compact scanner rather than a heavyweight editor
 * dependency. It covers comments, strings, numbers, keywords, function names, types,
 * annotations, shell variables and XML/HTML tags/attributes.
 */
fun highlightCode(code: String, language: String, palette: HighlightPalette): AnnotatedString {
    val profile = profileFor(language)
    val builder = AnnotatedString.Builder()
    if (profile.xml) appendXml(builder, code, palette) else appendSource(builder, code, profile, palette)
    return builder.toAnnotatedString()
}

private fun appendSource(b: AnnotatedString.Builder, code: String, profile: LanguageProfile,
                         palette: HighlightPalette) {
    var i = 0
    while (i < code.length) {
        val c = code[i]
        val lineComment = profile.lineComments.firstOrNull { code.startsWith(it, i) }
        if (lineComment != null) {
            var end = code.indexOf('\n', i)
            if (end < 0) end = code.length
            appendStyled(b, code, i, end, palette.comment, italic = true)
            i = end
            continue
        }
        val block = profile.blockComment
        if (block != null && code.startsWith(block.first, i)) {
            val close = code.indexOf(block.second, i + block.first.length)
            val end = if (close < 0) code.length else close + block.second.length
            appendStyled(b, code, i, end, palette.comment, italic = true)
            i = end
            continue
        }
        if (c == '"' || (profile.singleQuotes && c == '\'') || (profile.backticks && c == '`')) {
            val end = readString(code, i, c)
            appendStyled(b, code, i, end, palette.string)
            i = end
            continue
        }
        if (c.isDigit()) {
            var end = i + 1
            while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_' || code[end] == '.')) end++
            appendStyled(b, code, i, end, palette.number)
            i = end
            continue
        }
        if (profile.annotations && c == '@') {
            var end = i + 1
            while (end < code.length && isIdentifierPart(code[end])) end++
            appendStyled(b, code, i, end, palette.attribute, bold = true)
            i = end
            continue
        }
        if (profile.variables && c == '$') {
            var end = i + 1
            if (end < code.length && code[end] == '{') {
                val close = code.indexOf('}', end)
                end = if (close < 0) code.length else close + 1
            } else {
                while (end < code.length && isIdentifierPart(code[end])) end++
            }
            appendStyled(b, code, i, end, palette.attribute)
            i = end
            continue
        }
        if (isIdentifierStart(c)) {
            var end = i + 1
            while (end < code.length && isIdentifierPart(code[end])) end++
            val word = code.substring(i, end)
            val style = when {
                word in profile.keywords -> SpanStyle(color = palette.keyword, fontWeight = FontWeight.SemiBold)
                word.firstOrNull()?.isUpperCase() == true -> SpanStyle(color = palette.type)
                nextNonSpace(code, end) == '(' -> SpanStyle(color = palette.function)
                else -> null
            }
            if (style != null) appendStyled(b, code, i, end, style) else b.append(word)
            i = end
            continue
        }
        if (c in "{}()[];,.:=+-*/%<>!&|^~?") {
            appendStyled(b, code, i, i + 1, palette.punctuation)
            i++
            continue
        }
        b.append(c)
        i++
    }
}

private fun appendXml(b: AnnotatedString.Builder, code: String, palette: HighlightPalette) {
    var i = 0
    while (i < code.length) {
        if (code.startsWith("<!--", i)) {
            val close = code.indexOf("-->", i + 4)
            val end = if (close < 0) code.length else close + 3
            appendStyled(b, code, i, end, palette.comment, italic = true)
            i = end
            continue
        }
        if (code.startsWith("<![CDATA[", i)) {
            val close = code.indexOf("]]>", i + 9)
            val end = if (close < 0) code.length else close + 3
            appendStyled(b, code, i, end, palette.string)
            i = end
            continue
        }
        if (code[i] == '<') {
            var j = i + 1
            if (j < code.length && code[j] == '/') j++
            appendStyled(b, code, i, j, palette.punctuation)
            val nameStart = j
            while (j < code.length && (isIdentifierPart(code[j]) || code[j] == ':' || code[j] == '-')) j++
            appendStyled(b, code, nameStart, j, palette.tag, bold = true)
            while (j < code.length && code[j] != '>') {
                when {
                    code[j].isWhitespace() || code[j] == '=' || code[j] == '/' -> {
                        appendStyled(b, code, j, j + 1, palette.punctuation)
                        j++
                    }
                    code[j] == '"' || code[j] == '\'' -> {
                        val end = readString(code, j, code[j])
                        appendStyled(b, code, j, end, palette.string)
                        j = end
                    }
                    isIdentifierStart(code[j]) -> {
                        val start = j
                        while (j < code.length && (isIdentifierPart(code[j]) || code[j] == ':' || code[j] == '-')) j++
                        appendStyled(b, code, start, j, palette.attribute)
                    }
                    else -> {
                        appendStyled(b, code, j, j + 1, palette.punctuation)
                        j++
                    }
                }
            }
            if (j < code.length) {
                appendStyled(b, code, j, j + 1, palette.punctuation)
                j++
            }
            i = j
            continue
        }
        if (code[i] == '&') {
            val close = code.indexOf(';', i)
            if (close > i && close - i < 12) {
                appendStyled(b, code, i, close + 1, palette.number)
                i = close + 1
                continue
            }
        }
        b.append(code[i])
        i++
    }
}

private fun readString(code: String, start: Int, quote: Char): Int {
    if (start + 2 < code.length && code[start + 1] == quote && code[start + 2] == quote) {
        val close = code.indexOf("$quote$quote$quote", start + 3)
        return if (close < 0) code.length else close + 3
    }
    var i = start + 1
    while (i < code.length) {
        if (code[i] == '\\') {
            i += 2
        } else if (code[i] == quote) {
            return i + 1
        } else {
            i++
        }
    }
    return code.length
}

private fun nextNonSpace(code: String, from: Int): Char? {
    var i = from
    while (i < code.length && code[i].isWhitespace()) i++
    return code.getOrNull(i)
}

private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_'

private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

private fun appendStyled(b: AnnotatedString.Builder, code: String, start: Int, end: Int, color: Color,
                         italic: Boolean = false, bold: Boolean = false) {
    appendStyled(b, code, start, end, SpanStyle(
        color = color,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
    ))
}

private fun appendStyled(b: AnnotatedString.Builder, code: String, start: Int, end: Int, style: SpanStyle) {
    if (end <= start) return
    val from = b.length
    b.append(code.substring(start, end))
    b.addStyle(style, from, b.length)
}
