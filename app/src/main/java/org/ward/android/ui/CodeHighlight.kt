package org.ward.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Theme-aware colours used by the markdown code highlighter.
 *
 * The first ten fields are the original palette; the rest default to a sensible
 * existing colour so call sites that build the palette positionally keep working.
 */
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
    val punctuation: Color,
    val escape: Color = string,
    val variable: Color = attribute,
    val link: Color = function
)

/** Languages the scanner has real rules for. */
private enum class Lang {
    PLAIN, KOTLIN, JAVA, CSHARP, C, CPP, PYTHON, JS, TS, JSON, XML, SHELL, SQL,
    RUST, GO, CSS, CONFIG, DIFF, RUBY, PHP, LUA, DOCKER
}

private class Profile(
    val lang: Lang,
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val builtins: Set<String> = emptySet(),
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val nestedComments: Boolean = false,
    val doubleQuoted: Boolean = true,
    val singleQuoted: Boolean = true,
    val backtickQuoted: Boolean = false,
    val tripleQuoted: Boolean = false,
    val rawBacktick: Boolean = false,
    val charLiterals: Boolean = false,
    val annotations: Boolean = false,
    val preprocessor: Boolean = false,
    val sigil: Char? = null,
    val interpolates: Boolean = false,
    val caseInsensitive: Boolean = false,
    val shell: Boolean = false,
    val typeByCase: Boolean = false,
    val sql: Boolean = false,
    val config: Boolean = false,
    val diff: Boolean = false,
    val docStrings: Boolean = false
)

private val MAX_HIGHLIGHT_CHARS = 60_000

// ---------------------------------------------------------------- vocabulary

private val kotlinKeywords = setOf(
    "as", "break", "by", "catch", "class", "companion", "constructor", "continue", "crossinline",
    "data", "delegate", "do", "else", "enum", "expect", "external", "field", "file", "finally", "for",
    "fun", "get", "if", "import", "in", "infix", "init", "inline", "inner", "interface", "internal",
    "is", "it", "lateinit", "noinline", "object", "open", "operator", "out", "override", "package",
    "param", "private", "property", "protected", "public", "receiver", "reified", "return", "sealed",
    "set", "setparam", "super", "suspend", "tailrec", "this", "throw", "try", "typealias", "typeof",
    "val", "value", "var", "vararg", "when", "where", "while", "true", "false", "null"
)
private val kotlinTypes = setOf(
    "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char", "String", "Unit", "Any",
    "Nothing", "Array", "List", "MutableList", "Map", "MutableMap", "Set", "MutableSet", "Pair",
    "Triple", "Sequence", "Flow", "Result", "Throwable", "Regex", "ByteArray"
)

private val javaKeywords = setOf(
    "abstract", "assert", "break", "case", "catch", "class", "const", "continue", "default", "do",
    "else", "enum", "extends", "final", "finally", "for", "goto", "if", "implements", "import",
    "instanceof", "interface", "native", "new", "package", "private", "protected", "public", "record",
    "return", "sealed", "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
    "throws", "transient", "try", "var", "volatile", "while", "true", "false", "null", "yield"
)
private val javaTypes = setOf(
    "boolean", "byte", "char", "double", "float", "int", "long", "short", "void", "String", "Object",
    "Integer", "Long", "Double", "Float", "Boolean", "Character", "Byte", "Short", "List", "Map",
    "Set", "Optional", "Exception", "RuntimeException"
)

private val cKeywords = setOf(
    "auto", "break", "case", "const", "continue", "default", "do", "else", "enum", "extern", "for",
    "goto", "if", "inline", "register", "restrict", "return", "sizeof", "static", "struct", "switch",
    "typedef", "union", "volatile", "while", "class", "namespace", "template", "typename", "public",
    "private", "protected", "virtual", "override", "new", "delete", "this", "using", "try", "catch",
    "throw", "noexcept", "constexpr", "nullptr", "true", "false", "null", "nullptr_t", "operator",
    "friend", "explicit", "mutable", "decltype", "static_assert", "co_await", "co_return", "co_yield"
)
private val cTypes = setOf(
    "void", "char", "short", "int", "long", "float", "double", "signed", "unsigned", "bool", "size_t",
    "wchar_t", "char16_t", "char32_t", "int8_t", "int16_t", "int32_t", "int64_t", "uint8_t",
    "uint16_t", "uint32_t", "uint64_t", "ssize_t", "ptrdiff_t", "FILE", "std", "string", "vector",
    "map", "set", "unordered_map", "unique_ptr", "shared_ptr"
)

private val pythonKeywords = setOf(
    "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
    "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
    "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
    "True", "False", "None", "self", "match", "case"
)
private val pythonBuiltins = setOf(
    "print", "len", "range", "str", "int", "float", "list", "dict", "set", "tuple", "open", "enumerate",
    "zip", "map", "filter", "sorted", "sum", "min", "max", "abs", "round", "isinstance", "super",
    "type", "repr", "format", "input", "bytes", "bool", "next", "iter", "getattr", "setattr"
)

private val jsKeywords = setOf(
    "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
    "delete", "do", "else", "enum", "export", "extends", "finally", "for", "function", "if", "import",
    "in", "instanceof", "let", "new", "of", "return", "static", "super", "switch", "this", "throw",
    "try", "typeof", "var", "void", "while", "with", "yield", "true", "false", "null", "undefined",
    "interface", "type", "namespace", "declare", "readonly", "private", "public", "protected",
    "implements", "abstract", "as", "satisfies", "keyof", "infer", "never", "unknown", "any"
)

private val rustKeywords = setOf(
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern",
    "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref",
    "return", "self", "Self", "static", "struct", "super", "trait", "type", "unsafe", "use", "where",
    "while", "true", "false", "None", "Some", "Ok", "Err"
)
private val rustTypes = setOf(
    "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize", "f32",
    "f64", "bool", "char", "str", "String", "Vec", "Option", "Result", "Box", "Rc", "Arc", "HashMap"
)

private val goKeywords = setOf(
    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for",
    "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select",
    "struct", "switch", "type", "var", "nil", "true", "false", "iota"
)
private val goTypes = setOf(
    "bool", "byte", "complex64", "complex128", "error", "float32", "float64", "int", "int8", "int16",
    "int32", "int64", "rune", "string", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
    "any", "make", "new", "len", "cap", "append", "copy", "delete", "panic", "recover", "print"
)

private val shellControl = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
    "in", "function", "select", "time", "coproc", "{", "}", "!", "return", "break", "continue"
)
private val shellBuiltins = setOf(
    "echo", "printf", "cd", "pwd", "read", "export", "local", "declare", "typeset", "readonly", "unset",
    "shift", "set", "trap", "source", "alias", "unalias", "eval", "exec", "wait", "kill", "test", "true",
    "false", "exit", "type", "hash", "umask", "ulimit", "bg", "fg", "jobs", "getopts", "let", "mapfile"
)
private val shellWrappers = setOf(
    "sudo", "doas", "env", "nice", "ionice", "nohup", "time", "command", "builtin", "exec", "xargs",
    "watch", "timeout", "stdbuf", "chroot", "runuser", "su", "pkexec"
)
private val shellFileTests = setOf("-e", "-f", "-d", "-r", "-w", "-x", "-s", "-z", "-n", "-L", "-h", "-p", "-S", "-b", "-c")

private val sqlKeywords = setOf(
    "select", "from", "where", "insert", "into", "values", "update", "delete", "create", "table",
    "alter", "drop", "index", "join", "left", "right", "inner", "outer", "full", "cross", "on",
    "group", "by", "order", "having", "limit", "offset", "and", "or", "not", "null", "is", "in",
    "as", "distinct", "union", "all", "case", "when", "then", "else", "end", "primary", "key",
    "foreign", "references", "default", "unique", "check", "view", "trigger", "procedure", "function",
    "begin", "commit", "rollback", "transaction", "grant", "revoke", "with", "recursive", "exists",
    "between", "like", "ilike", "asc", "desc", "if", "constraint", "cascade", "add", "column",
    "returning", "conflict", "do", "nothing", "vacuum", "analyze", "explain", "truncate", "replace",
    "true", "false", "interval", "cast", "coalesce", "nullif", "window", "partition", "over"
)
private val sqlTypes = setOf(
    "int", "integer", "bigint", "smallint", "serial", "bigserial", "text", "varchar", "char",
    "boolean", "bool", "date", "time", "timestamp", "timestamptz", "numeric", "decimal", "real",
    "double", "precision", "float", "json", "jsonb", "uuid", "bytea", "array", "enum"
)

private val rubyKeywords = setOf(
    "BEGIN", "END", "alias", "and", "begin", "break", "case", "class", "def", "defined?", "do",
    "else", "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not",
    "or", "redo", "rescue", "retry", "return", "self", "super", "then", "true", "undef", "unless",
    "until", "when", "while", "yield", "require", "require_relative", "attr_accessor", "attr_reader"
)

private val phpKeywords = setOf(
    "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class", "clone", "const",
    "continue", "declare", "default", "do", "echo", "else", "elseif", "empty", "enddeclare", "endfor",
    "endforeach", "endif", "endswitch", "endwhile", "enum", "extends", "final", "finally", "fn", "for",
    "foreach", "function", "global", "goto", "if", "implements", "include", "include_once", "instanceof",
    "insteadof", "interface", "isset", "list", "match", "namespace", "new", "or", "print", "private",
    "protected", "public", "readonly", "require", "require_once", "return", "static", "switch", "throw",
    "trait", "try", "unset", "use", "var", "while", "xor", "yield", "true", "false", "null"
)

private val luaKeywords = setOf(
    "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
    "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while"
)

private val cssKeywords = setOf(
    "important", "inherit", "initial", "unset", "none", "auto", "block", "inline", "flex", "grid",
    "absolute", "relative", "fixed", "sticky", "var", "calc", "rgb", "rgba", "hsl", "hsla", "url"
)

private val jsonLiterals = setOf("true", "false", "null")

private val configBooleans = setOf(
    "true", "false", "yes", "no", "on", "off", "null", "nil", "True", "False", "None", "~"
)

// ---------------------------------------------------------------- profiles

private val profiles: Map<Lang, Profile> by lazy {
    mapOf(
        Lang.KOTLIN to Profile(
            Lang.KOTLIN, kotlinKeywords, kotlinTypes, emptySet(), listOf("//"), listOf("/*" to "*/"),
            nestedComments = true, backtickQuoted = true, tripleQuoted = true, annotations = true,
            sigil = '$', interpolates = true, typeByCase = true
        ),
        Lang.JAVA to Profile(
            Lang.JAVA, javaKeywords, javaTypes, emptySet(), listOf("//"), listOf("/*" to "*/"),
            tripleQuoted = true, charLiterals = true, annotations = true, typeByCase = true
        ),
        Lang.CSHARP to Profile(
            Lang.CSHARP, javaKeywords + setOf("get", "set", "async", "await", "using", "var", "namespace", "readonly", "partial", "record"),
            javaTypes, emptySet(), listOf("//"), listOf("/*" to "*/"), tripleQuoted = true,
            charLiterals = true, annotations = true, preprocessor = true, typeByCase = true
        ),
        Lang.C to Profile(
            Lang.C, cKeywords, cTypes, emptySet(), listOf("//"), listOf("/*" to "*/"),
            charLiterals = true, preprocessor = true
        ),
        Lang.CPP to Profile(
            Lang.CPP, cKeywords, cTypes, emptySet(), listOf("//"), listOf("/*" to "*/"),
            charLiterals = true, preprocessor = true, typeByCase = true
        ),
        Lang.PYTHON to Profile(
            Lang.PYTHON, pythonKeywords, emptySet(), pythonBuiltins, listOf("#"), emptyList(),
            tripleQuoted = true, singleQuoted = true, annotations = true, docStrings = true
        ),
        Lang.JS to Profile(
            Lang.JS, jsKeywords, emptySet(), setOf("console", "JSON", "Math", "Object", "Array", "Promise"),
            listOf("//"), listOf("/*" to "*/"), backtickQuoted = true, rawBacktick = true,
            interpolates = true, sigil = '$'
        ),
        Lang.TS to Profile(
            Lang.TS, jsKeywords + setOf("enum", "implements", "private", "protected", "public", "readonly", "declare", "namespace", "abstract", "type", "interface", "as", "satisfies"),
            setOf("string", "number", "boolean", "any", "unknown", "never", "void", "object", "bigint", "symbol"),
            setOf("console", "JSON", "Math", "Object", "Array", "Promise"),
            listOf("//"), listOf("/*" to "*/"), backtickQuoted = true, rawBacktick = true,
            interpolates = true, sigil = '$', typeByCase = true
        ),
        Lang.JSON to Profile(
            Lang.JSON, jsonLiterals, emptySet(), emptySet(), emptyList(), emptyList(),
            singleQuoted = false
        ),
        Lang.XML to Profile(Lang.XML, emptySet(), emptySet(), emptySet(), emptyList(), emptyList()),
        Lang.SHELL to Profile(
            Lang.SHELL, shellControl, emptySet(), shellBuiltins, listOf("#"), emptyList(),
            sigil = '$', interpolates = true, shell = true
        ),
        Lang.SQL to Profile(
            Lang.SQL, sqlKeywords, sqlTypes, emptySet(), listOf("--"), listOf("/*" to "*/"),
            caseInsensitive = true, sql = true
        ),
        Lang.RUST to Profile(
            Lang.RUST, rustKeywords, rustTypes, emptySet(), listOf("//"), listOf("/*" to "*/"),
            nestedComments = true, annotations = true, rawBacktick = false, charLiterals = true,
            typeByCase = true, tripleQuoted = false
        ),
        Lang.GO to Profile(
            Lang.GO, goKeywords, goTypes, emptySet(), listOf("//"), listOf("/*" to "*/"),
            backtickQuoted = true, rawBacktick = true, charLiterals = true, typeByCase = true
        ),
        Lang.CSS to Profile(
            Lang.CSS, cssKeywords, emptySet(), emptySet(), listOf("//"), listOf("/*" to "*/"),
            backtickQuoted = false
        ),
        Lang.CONFIG to Profile(
            Lang.CONFIG, configBooleans, emptySet(), emptySet(), listOf("#"), emptyList(),
            config = true
        ),
        Lang.DIFF to Profile(Lang.DIFF, emptySet(), emptySet(), emptySet(), emptyList(), emptyList(), diff = true),
        Lang.RUBY to Profile(
            Lang.RUBY, rubyKeywords, emptySet(), emptySet(), listOf("#"), emptyList(),
            tripleQuoted = false, annotations = false, sigil = '$', interpolates = true
        ),
        Lang.PHP to Profile(
            Lang.PHP, phpKeywords, emptySet(), emptySet(), listOf("//", "#"), listOf("/*" to "*/"),
            sigil = '$', interpolates = true, typeByCase = true
        ),
        Lang.LUA to Profile(
            Lang.LUA, luaKeywords, emptySet(), emptySet(), listOf("--"), listOf("--[[" to "]]"),
            singleQuoted = true
        ),
        Lang.DOCKER to Profile(
            Lang.DOCKER, setOf(
                "FROM", "RUN", "CMD", "LABEL", "EXPOSE", "ENV", "ADD", "COPY", "ENTRYPOINT", "VOLUME",
                "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK", "SHELL", "AS"
            ), emptySet(), emptySet(), listOf("#"), emptyList(), singleQuoted = true
        ),
        Lang.PLAIN to Profile(Lang.PLAIN, emptySet(), emptySet(), emptySet(), emptyList(), emptyList(), singleQuoted = false)
    )
}

private val aliases: Map<String, Lang> by lazy {
    mapOf(
        "kt" to Lang.KOTLIN, "kotlin" to Lang.KOTLIN, "kts" to Lang.KOTLIN, "gradle" to Lang.KOTLIN,
        "java" to Lang.JAVA, "cs" to Lang.CSHARP, "csharp" to Lang.CSHARP, "c#" to Lang.CSHARP, "dotnet" to Lang.CSHARP,
        "c" to Lang.C, "h" to Lang.C, "objc" to Lang.C,
        "cpp" to Lang.CPP, "c++" to Lang.CPP, "cc" to Lang.CPP, "cxx" to Lang.CPP, "hpp" to Lang.CPP,
        "py" to Lang.PYTHON, "python" to Lang.PYTHON, "py3" to Lang.PYTHON,
        "js" to Lang.JS, "javascript" to Lang.JS, "jsx" to Lang.JS, "mjs" to Lang.JS, "cjs" to Lang.JS,
        "ts" to Lang.TS, "typescript" to Lang.TS, "tsx" to Lang.TS,
        "json" to Lang.JSON, "jsonc" to Lang.JSON, "json5" to Lang.JSON, "geojson" to Lang.JSON,
        "xml" to Lang.XML, "html" to Lang.XML, "xhtml" to Lang.XML, "svg" to Lang.XML,
        "plist" to Lang.XML, "xaml" to Lang.XML, "vue" to Lang.XML, "xsl" to Lang.XML,
        "sh" to Lang.SHELL, "bash" to Lang.SHELL, "zsh" to Lang.SHELL, "shell" to Lang.SHELL,
        "shellscript" to Lang.SHELL, "console" to Lang.SHELL, "shell-session" to Lang.SHELL,
        "fish" to Lang.SHELL, "dockerfile" to Lang.DOCKER, "docker" to Lang.DOCKER,
        "sql" to Lang.SQL, "mysql" to Lang.SQL, "postgres" to Lang.SQL, "postgresql" to Lang.SQL,
        "sqlite" to Lang.SQL, "plsql" to Lang.SQL, "tsql" to Lang.SQL,
        "rs" to Lang.RUST, "rust" to Lang.RUST,
        "go" to Lang.GO, "golang" to Lang.GO,
        "css" to Lang.CSS, "scss" to Lang.CSS, "sass" to Lang.CSS, "less" to Lang.CSS,
        "yaml" to Lang.CONFIG, "yml" to Lang.CONFIG, "toml" to Lang.CONFIG, "ini" to Lang.CONFIG,
        "conf" to Lang.CONFIG, "config" to Lang.CONFIG, "properties" to Lang.CONFIG,
        "env" to Lang.CONFIG, "dotenv" to Lang.CONFIG, "systemd" to Lang.CONFIG,
        "diff" to Lang.DIFF, "patch" to Lang.DIFF,
        "rb" to Lang.RUBY, "ruby" to Lang.RUBY,
        "php" to Lang.PHP,
        "lua" to Lang.LUA,
        "text" to Lang.PLAIN, "txt" to Lang.PLAIN, "plain" to Lang.PLAIN, "plaintext" to Lang.PLAIN,
        "output" to Lang.PLAIN, "log" to Lang.PLAIN, "logs" to Lang.PLAIN, "none" to Lang.PLAIN,
        "raw" to Lang.PLAIN, "chat" to Lang.PLAIN
    )
}

private fun profileFor(language: String, code: String): Profile {
    val tag = language.trim().lowercase()
        .substringBefore(' ').substringBefore('{').substringBefore(':').substringBefore(',')
        .trim().removePrefix("language-").removePrefix("lang-")
    val lang = aliases[tag] ?: if (tag.isNotEmpty() && tag != "code") guessLang(code) else guessLang(code)
    return profiles[lang] ?: profiles.getValue(Lang.PLAIN)
}

/** Cheap content sniffing for unlabelled fences; errs towards PLAIN. */
private fun guessLang(code: String): Lang {
    val t = code.trimStart().take(4000)
    if (t.isEmpty()) return Lang.PLAIN
    if (t.startsWith("#!")) return Lang.SHELL
    if (t.startsWith("<?xml") || t.startsWith("<!DOCTYPE") || t.startsWith("<html") || t.startsWith("<svg")) return Lang.XML
    if ((t.startsWith("{") || t.startsWith("[")) && Regex("\"[^\"\\n]{0,80}\"\\s*:").containsMatchIn(t)) return Lang.JSON
    Regex("(?im)^\\s*(select|insert|update|delete|create|alter)\\b[^\\n]*\\b(from|into|table|set|values|where)\\b")
        .find(t)?.let { return Lang.SQL }
    Regex("(?m)^\\s*(fun|val|var|class|object|package|import)\\s+\\S").find(t)?.let { return Lang.KOTLIN }
    Regex("(?m)^\\s*(def|from\\s+\\w+\\s+import|import\\s+\\w+|class\\s+\\w+\\s*(\\(|:))").find(t)?.let { return Lang.PYTHON }
    if (Regex("(?m)^\\s*func\\s+\\w+\\s*\\(").containsMatchIn(t) || t.contains("package main")) return Lang.GO
    if (Regex("(?m)^\\s*(fn|impl|let\\s+mut|use\\s+std)\\b").containsMatchIn(t)) return Lang.RUST
    Regex("(?m)^\\s*(function|const|let|var|import|export)\\b[^\\n]*[;{]").find(t)?.let { return Lang.JS }
    if (Regex("(?m)^\\s*(sudo|ssh|cd|ls|cat|grep|curl|wget|docker|git|apt|dnf|systemctl|firewall-cmd|mkdir|chmod|export)\\s+").containsMatchIn(t)) return Lang.SHELL
    return Lang.PLAIN
}

// ---------------------------------------------------------------- styles

private class Styles(p: HighlightPalette) {
    val plain = SpanStyle(color = p.plain)
    val keyword = SpanStyle(color = p.keyword, fontWeight = FontWeight.SemiBold)
    val string = SpanStyle(color = p.string)
    val escape = SpanStyle(color = p.escape)
    val comment = SpanStyle(color = p.comment, fontStyle = FontStyle.Italic)
    val number = SpanStyle(color = p.number)
    val function = SpanStyle(color = p.function)
    val command = SpanStyle(color = p.function, fontWeight = FontWeight.Medium)
    val type = SpanStyle(color = p.type)
    val tag = SpanStyle(color = p.tag, fontWeight = FontWeight.SemiBold)
    val attribute = SpanStyle(color = p.attribute)
    val punctuation = SpanStyle(color = p.punctuation)
    val variable = SpanStyle(color = p.variable)
    val link = SpanStyle(color = p.link)
}

// ---------------------------------------------------------------- entry point

/**
 * Highlights a fenced code block with a compact, allocation-light lexer.
 *
 * Correctness rules that matter in a chat client:
 *  - a `//` inside a URL (`https://…`) is never a comment;
 *  - an unterminated quote never swallows the rest of the block;
 *  - unlabelled or `text` blocks stay plain, except for link-coloured URLs;
 *  - unknown languages fall back to content sniffing, then to plain text.
 */
fun highlightCode(code: String, language: String, palette: HighlightPalette): AnnotatedString {
    val builder = AnnotatedString.Builder()
    if (code.isEmpty()) return builder.toAnnotatedString()
    if (code.length > MAX_HIGHLIGHT_CHARS) { builder.append(code); return builder.toAnnotatedString() }
    val profile = profileFor(language, code)
    val styles = Styles(palette)
    val out = when {
        profile.diff -> lexDiff(code, styles)
        profile.config -> lexConfig(code, styles)
        profile.lang == Lang.XML -> lexXml(code, styles)
        profile.lang == Lang.PLAIN -> lexPlain(code, styles)
        else -> Lexer(code, profile, styles).lex()
    }
    return out
}

// ---------------------------------------------------------------- plain text

private fun lexPlain(code: String, s: Styles): AnnotatedString {
    val b = AnnotatedString.Builder()
    var i = 0
    while (i < code.length) {
        val urlEnd = matchUrl(code, i)
        if (urlEnd > i) { b.appendStyled(code.substring(i, urlEnd), s.link); i = urlEnd; continue }
        val next = findNextUrlStart(code, i)
        b.append(code, i, next)
        i = next
    }
    return b.toAnnotatedString()
}

private fun findNextUrlStart(code: String, from: Int): Int {
    var i = from
    while (i < code.length) {
        if (code[i].isLetter() && matchUrl(code, i) > i) return i
        i++
    }
    return code.length
}

/** Returns the end index of a URL starting at [start], or [start] if there is none. */
private fun matchUrl(code: String, start: Int): Int {
    if (start >= code.length || !code[start].isLetter()) return start
    var i = start
    while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '+' || code[i] == '-' || code[i] == '.')) i++
    if (i - start < 2 || i + 2 >= code.length) return start
    if (!code.startsWith("://", i)) return start
    i += 3
    val bodyStart = i
    while (i < code.length && !code[i].isWhitespace() && code[i] !in "\"'<>()[]{}\\") i++
    // trim trailing punctuation that is more likely sentence punctuation than URL
    while (i > bodyStart && code[i - 1] in ".,;:!?") i--
    return if (i > bodyStart) i else start
}

// ---------------------------------------------------------------- diff

private fun lexDiff(code: String, s: Styles): AnnotatedString {
    val b = AnnotatedString.Builder()
    var i = 0
    while (i < code.length) {
        var end = code.indexOf('\n', i)
        if (end < 0) end = code.length
        val line = code.substring(i, end)
        val style = when {
            line.startsWith("+++") || line.startsWith("---") -> s.comment
            line.startsWith("@@") -> s.function
            line.startsWith("+") -> s.string
            line.startsWith("-") -> s.tag
            line.startsWith("diff ") || line.startsWith("index ") -> s.keyword
            else -> null
        }
        if (style != null) b.appendStyled(line, style) else b.append(line)
        if (end < code.length) b.append('\n')
        i = if (end < code.length) end + 1 else end
    }
    return b.toAnnotatedString()
}

// ---------------------------------------------------------------- xml / html

private fun lexXml(code: String, s: Styles): AnnotatedString {
    val b = AnnotatedString.Builder()
    var i = 0
    fun emit(from: Int, to: Int, style: SpanStyle) = b.appendStyled(code.substring(from, to), style)
    while (i < code.length) {
        if (code.startsWith("<!--", i)) {
            val close = code.indexOf("-->", i + 4)
            val end = if (close < 0) code.length else close + 3
            emit(i, end, s.comment); i = end; continue
        }
        if (code.startsWith("<![CDATA[", i)) {
            val close = code.indexOf("]]>", i + 9)
            val end = if (close < 0) code.length else close + 3
            emit(i, end, s.string); i = end; continue
        }
        if (code.startsWith("<?", i)) {
            val close = code.indexOf("?>", i + 2)
            val end = if (close < 0) code.length else close + 2
            emit(i, end, s.tag); i = end; continue
        }
        if (code.startsWith("<!", i)) {
            val close = code.indexOf('>', i + 2)
            val end = if (close < 0) code.length else close + 1
            emit(i, end, s.comment); i = end; continue
        }
        if (code[i] == '<') {
            var j = i + 1
            if (j < code.length && code[j] == '/') j++
            emit(i, minOf(j, code.length), s.punctuation)
            val nameStart = j
            while (j < code.length && (isIdentPart(code[j]) || code[j] == ':' || code[j] == '-' || code[j] == '.')) j++
            if (j > nameStart) emit(nameStart, j, s.tag)
            while (j < code.length && code[j] != '>') {
                when {
                    code[j].isWhitespace() || code[j] == '/' || code[j] == '=' -> {
                        emit(j, j + 1, s.punctuation); j++
                    }
                    code[j] == '"' || code[j] == '\'' -> {
                        val q = code[j]
                        var k = j + 1
                        while (k < code.length && code[k] != q && code[k] != '\n') k++
                        val end = if (k < code.length && code[k] == q) k + 1 else k
                        emit(j, end, s.string); j = end
                    }
                    isIdentStart(code[j]) -> {
                        val start = j
                        while (j < code.length && (isIdentPart(code[j]) || code[j] == ':' || code[j] == '-' || code[j] == '.')) j++
                        emit(start, j, s.attribute)
                    }
                    else -> { emit(j, j + 1, s.punctuation); j++ }
                }
            }
            if (j < code.length) { emit(j, j + 1, s.punctuation); j++ }
            i = j
            continue
        }
        if (code[i] == '&') {
            val close = code.indexOf(';', i)
            if (close in (i + 1)..(i + 12)) { emit(i, close + 1, s.escape); i = close + 1; continue }
        }
        // text content, one char at a time (cheap and keeps offsets exact)
        b.append(code[i]); i++
    }
    return b.toAnnotatedString()
}

// ---------------------------------------------------------------- config files

private fun lexConfig(code: String, s: Styles): AnnotatedString {
    val b = AnnotatedString.Builder()
    var i = 0
    while (i < code.length) {
        var end = code.indexOf('\n', i)
        if (end < 0) end = code.length
        var p = i
        val indentEnd = run {
            var k = i
            while (k < end && (code[k] == ' ' || code[k] == '\t')) k++
            k
        }
        b.append(code, i, indentEnd)
        p = indentEnd

        // whole-line comments
        if (p < end && (code[p] == '#' || code[p] == ';')) {
            b.appendStyled(code.substring(p, end), s.comment)
            if (end < code.length) b.append('\n')
            i = if (end < code.length) end + 1 else end
            continue
        }
        // section headers ([section]) and document markers (---)
        if (p < end && code[p] == '[') {
            val close = code.indexOf(']', p)
            if (close in p until end) {
                b.appendStyled(code.substring(p, close + 1), s.tag)
                p = close + 1
            }
        }
        if (p < end) {
            // YAML list item: "- key: value" / "- value"
            if (code[p] == '-' && p + 1 < end && code[p + 1] == ' ') {
                b.appendStyled("-", s.punctuation)
                p++
                while (p < end && code[p] == ' ') { b.append(code[p]); p++ }
            }
            val sep = if (p < end) findConfigSeparator(code, p, end) else -1
            if (sep >= 0) {
                val keyText = code.substring(p, sep).trimEnd()
                if (keyText.isNotEmpty()) b.appendStyled(keyText, s.attribute)
                b.append(code, p + keyText.length, sep)
                b.appendStyled(code[sep].toString(), s.punctuation)
                p = sep + 1
            }
        }
        // value part
        while (p < end) {
            val c = code[p]
            when {
                c == '#' && (p == 0 || code[p - 1].isWhitespace()) -> {
                    b.appendStyled(code.substring(p, end), s.comment); p = end
                }
                c == '"' || c == '\'' -> {
                    var k = p + 1
                    while (k < end && code[k] != c) { if (code[k] == '\\') k++; k++ }
                    val qe = if (k < end) k + 1 else k
                    b.appendStyled(code.substring(p, qe), s.string); p = qe
                }
                c == '$' && p + 1 < end && (isIdentStart(code[p + 1]) || code[p + 1] == '{') -> {
                    val ve = readSigilEnd(code, p, end)
                    b.appendStyled(code.substring(p, ve), s.variable); p = ve
                }
                c.isDigit() -> {
                    val ne = readNumberEnd(code, p, end)
                    b.appendStyled(code.substring(p, ne), s.number); p = ne
                }
                isIdentStart(c) -> {
                    var k = p
                    while (k < end && isIdentPart(code[k])) k++
                    val word = code.substring(p, k)
                    val style = when (word) {
                        in jsonLiterals, in configBooleans -> s.keyword
                        else -> if (word.startsWith("http") && matchUrl(code, p) > p) s.link else null
                    }
                    if (style != null) b.appendStyled(word, style) else b.append(word)
                    p = k
                }
                c == '&' || c == '*' || c == '!' -> {
                    var k = p + 1
                    while (k < end && (isIdentPart(code[k]) || code[k] == '-' || code[k] == '#')) k++
                    b.appendStyled(code.substring(p, k), s.variable); p = k
                }
                else -> { b.append(c); p++ }
            }
        }
        if (end < code.length) b.append('\n')
        i = if (end < code.length) end + 1 else end
    }
    return b.toAnnotatedString()
}

private fun findConfigSeparator(code: String, from: Int, end: Int): Int {
    var i = from
    while (i < end) {
        val c = code[i]
        if (c == ':' || c == '=') return i
        if (c == ' ' || c == '\t') return -1
        if (c == '"' || c == '\'') return -1
        i++
    }
    return -1
}

// ---------------------------------------------------------------- main lexer

private class Lexer(private val code: String, private val p: Profile, private val s: Styles) {
    private val b = AnnotatedString.Builder()
    private val n = code.length
    private var i = 0
    private var lineStart = true
    private var commandNext = p.shell

    fun lex(): AnnotatedString {
        while (i < n) {
            val c = code[i]
            if (c == '\n') {
                b.append(c); i++; lineStart = true; commandNext = p.shell; continue
            }
            if (c == ' ' || c == '\t' || c == '\r') { b.append(c); i++; continue }
            val atLineStart = lineStart
            lineStart = false

            if (atLineStart && p.shell && c == '#' && i + 1 < n && code[i + 1] == '!') {
                readToEol(s.comment); continue
            }
            if (atLineStart && p.shell && (c == '$' || c == '>') && i + 1 < n && code[i + 1] == ' ') {
                // shell prompt marker: "$ cmd" / "> cmd"
                b.appendStyled(c.toString(), s.comment); i++; continue
            }
            if (p.preprocessor && c == '#' && atLineStart) { readToEol(s.keyword); continue }
            if (readComment()) continue
            if (readString(c)) continue
            if (p.sigil != null && c == p.sigil && isSigilStart(i)) { readSigil(); continue }
            if (p.annotations && c == '@' && i + 1 < n && isIdentStart(code[i + 1])) { readDecorator(); continue }
            if (p.shell && c == '-' && i + 1 < n && isFlagStart(i)) { readFlag(); continue }
            if (p.shell && (c == '/' || c == '~' || (c == '.' && i + 1 < n && (code[i + 1] == '/' || code[i + 1] == '.')))) {
                readPath(); continue
            }
            if (c.isDigit()) { readNumber(); continue }
            if (c == '.' && i + 1 < n && code[i + 1].isDigit()) { readNumber(); continue }
            if (isIdentStart(c)) { readWord(); continue }
            if (p.shell && (c == '<' || c == '|' || c == ';' || c == '&' || c == '(' || c == '{')) {
                commandNext = true
            }
            if (p.shell && c in "=!<>") { b.appendStyled(c.toString(), s.punctuation); i++; continue }
            b.append(c); i++
        }
        return b.toAnnotatedString()
    }

    // -- helpers ------------------------------------------------------------

    private fun emit(from: Int, to: Int, style: SpanStyle) {
        if (to <= from) return
        b.appendStyled(code.substring(from, to), style)
    }

    private fun readToEol(style: SpanStyle) {
        var end = code.indexOf('\n', i)
        if (end < 0) end = n
        emit(i, end, style)
        i = end
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || (p.sigil == null && c == '_')
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '?' || c == '!'

    private fun isSigilStart(at: Int): Boolean {
        val nxt = code.getOrNull(at + 1) ?: return false
        return isIdentStart(nxt) || nxt == '{' || nxt == '(' || nxt.isDigit() ||
            nxt == '?' || nxt == '@' || nxt == '*' || nxt == '#' || nxt == '$' || nxt == '!'
    }

    private fun isFlagStart(at: Int): Boolean {
        val nxt = code.getOrNull(at + 1) ?: return false
        if (nxt == '-') {
            val third = code.getOrNull(at + 2) ?: return false
            return isIdentStart(third) || third == '?'
        }
        return isIdentStart(nxt) || nxt.isDigit() || nxt == '?'
    }

    // -- comments -----------------------------------------------------------

    private fun readComment(): Boolean {
        for (lc in p.lineComments) {
            if (!code.startsWith(lc, i)) continue
            // a "//" that follows a scheme colon is part of a URL, not a comment
            if (lc == "//" && i > 0 && code[i - 1] == ':') continue
            if (lc == "#" && i > 0 && !code[i - 1].isWhitespace() && p.shell) {
                // "foo#bar" is not a comment in shell words
                if (!code[i - 1].let { it == ';' || it == '&' || it == '|' || it == '(' }) continue
            }
            readToEol(s.comment)
            return true
        }
        for ((open, close) in p.blockComments) {
            if (!code.startsWith(open, i)) continue
            readBlockComment(open, close)
            return true
        }
        return false
    }

    private fun readBlockComment(open: String, close: String) {
        val start = i
        i += open.length
        var depth = 1
        while (i < n) {
            if (p.nestedComments && code.startsWith(open, i)) { depth++; i += open.length; continue }
            if (code.startsWith(close, i)) {
                depth--
                i += close.length
                if (depth == 0) break
                continue
            }
            i++
        }
        emit(start, i, s.comment)
    }

    // -- strings ------------------------------------------------------------

    private fun readString(c: Char): Boolean {
        if (p.lang == Lang.JSON && c == '"') return readJsonString()
        val isTriple = p.tripleQuoted && (c == '"' || c == '\'') &&
            i + 2 < n && code[i + 1] == c && code[i + 2] == c
        val allowed = when (c) {
            '"' -> p.doubleQuoted
            '\'' -> p.singleQuoted
            '`' -> p.backtickQuoted
            else -> false
        }
        if (!isTriple && !allowed) return false
        // a lone apostrophe inside a word is not a string (don't, it's) — except in typed langs
        if (c == '\'' && !isTriple && i > 0 && isIdentPart(code[i - 1]) && !p.charLiterals) return false

        val start = i
        val delimLen = if (isTriple) 3 else 1
        // Shell single-quoted text is literal: no escapes, no interpolation.
        val escapes = !(p.shell && c == '\'') && !(p.rawBacktick && c == '`')
        i += delimLen
        var segStart = start
        val multiline = isTriple || (p.rawBacktick && c == '`')
        while (i < n) {
            val ch = code[i]
            if (ch == '\n' && !multiline) break
            if (delimLen == 1 && ch == c) { i++; break }
            if (delimLen == 3 && code.startsWith("$c$c$c", i)) { i += 3; break }
            if (escapes && ch == '\\') {
                emit(segStart, i, s.string)
                val escEnd = minOf(i + 2, n)
                emit(i, escEnd, s.escape)
                i = escEnd
                segStart = i
                continue
            }
            if (p.interpolates && ch == p.sigil && c != '\'' && i + 1 < n) {
                val nxt = code[i + 1]
                if (isIdentStart(nxt) || nxt == '{') {
                    emit(segStart, i, s.string)
                    val ve = readSigilEnd(code, i, n)
                    emit(i, ve, s.variable)
                    i = ve
                    segStart = i
                    continue
                }
            }
            i++
        }
        emit(segStart, i, s.string)
        return true
    }

    private fun readJsonString(): Boolean {
        val start = i
        i++
        while (i < n) {
            val ch = code[i]
            if (ch == '\\') { i = minOf(i + 2, n); continue }
            if (ch == '"') { i++; break }
            if (ch == '\n') break
            i++
        }
        val isKey = nextNonSpace(i) == ':'
        emit(start, i, if (isKey) s.attribute else s.string)
        return true
    }

    // -- numbers ------------------------------------------------------------

    private fun readNumber() {
        val start = i
        if (i + 1 < n && code[i] == '0' && (code[i + 1] == 'x' || code[i + 1] == 'X' || code[i + 1] == 'b' || code[i + 1] == 'B' || code[i + 1] == 'o' || code[i + 1] == 'O')) {
            i += 2
            while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
        } else {
            while (i < n && (code[i].isDigit() || code[i] == '_')) i++
            if (i + 1 < n && code[i] == '.' && code[i + 1].isDigit()) {
                i++
                while (i < n && (code[i].isDigit() || code[i] == '_')) i++
            }
            if (i < n && (code[i] == 'e' || code[i] == 'E')) {
                var k = i + 1
                if (k < n && (code[k] == '+' || code[k] == '-')) k++
                if (k < n && code[k].isDigit()) {
                    i = k
                    while (i < n && code[i].isDigit()) i++
                }
            }
            if (i < n && code[i] in "uUlLfFdD" && (i + 1 >= n || !isIdentPart(code[i + 1]))) i++
        }
        emit(start, i, s.number)
    }

    // -- words --------------------------------------------------------------

    private fun readWord() {
        val start = i
        while (i < n) {
            val ch = code[i]
            if (isIdentPart(ch)) { i++; continue }
            // command names may contain hyphens: firewall-cmd, apt-get, docker-compose
            if (p.shell && ch == '-' && i + 1 < n && isIdentPart(code[i + 1])) { i += 2; continue }
            break
        }
        val word = code.substring(start, i)
        val key = if (p.caseInsensitive) word.lowercase() else word
        val after = nextNonSpace(i)
        val atCommand = p.shell && commandNext

        val style: SpanStyle? = when {
            key in p.keywords -> if (p.shell) s.keyword else s.keyword
            p.shell && key in shellFileTests -> s.attribute
            key in p.types -> s.type
            key in p.builtins -> s.function
            p.shell && atCommand -> s.command
            after == '(' -> s.function
            p.typeByCase && word.length > 1 && word[0].isUpperCase() && word.any { it.isLowerCase() } -> s.type
            p.sql && after == '(' -> s.function
            else -> null
        }

        // an identifier glued to "://" is a URL, not an identifier
        if (i + 2 < n && code.startsWith("://", i)) {
            val urlEnd = matchUrl(code, start)
            if (urlEnd > start) { emit(start, urlEnd, s.link); i = urlEnd; return }
        }

        if (style != null) emit(start, i, style) else b.append(word)

        if (p.shell && atCommand) {
            commandNext = key in shellWrappers || key in shellControl
        } else if (p.shell && style == s.function && after == '(') {
            commandNext = false
        }
    }

    private fun readDecorator() {
        val start = i
        i++
        while (i < n && (isIdentPart(code[i]) || code[i] == '.' || code[i] == ':')) i++
        emit(start, i, s.attribute)
    }

    private fun readSigil() {
        val end = readSigilEnd(code, i, n)
        emit(i, end, s.variable)
        i = end
    }

    private fun readFlag() {
        val start = i
        i++
        if (i < n && code[i] == '-') i++
        while (i < n && (isIdentPart(code[i]) || code[i] == '-')) i++
        emit(start, i, s.attribute)
    }

    private fun readPath() {
        val start = i
        while (i < n && !code[i].isWhitespace() && code[i] !in "\"'|;&><`()") i++
        val tokenEnd = i
        var end = tokenEnd
        while (end > start && code[end - 1] in ",;:") end--
        if (end == start) { b.append(code.substring(start, tokenEnd)) }
        else {
            emit(start, end, s.type)
            if (end < tokenEnd) b.append(code.substring(end, tokenEnd))
        }
    }

    private fun nextNonSpace(from: Int): Char? {
        var k = from
        while (k < n && (code[k] == ' ' || code[k] == '\t')) k++
        return code.getOrNull(k)
    }
}

// ---------------------------------------------------------------- shared helpers

private fun readSigilEnd(code: String, start: Int, limit: Int): Int {
    var i = start + 1
    if (i >= limit) return i
    when (code[i]) {
        '{' -> {
            var depth = 0
            while (i < limit) {
                if (code[i] == '{') depth++
                else if (code[i] == '}') { depth--; if (depth == 0) { i++; break } }
                i++
            }
            return i
        }
        '(' -> {
            var depth = 0
            while (i < limit) {
                if (code[i] == '(') depth++
                else if (code[i] == ')') { depth--; if (depth == 0) { i++; break } }
                i++
            }
            return i
        }
    }
    if (!(code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '?' || code[i] == '@' ||
                code[i] == '*' || code[i] == '#' || code[i] == '$' || code[i] == '!')) return start + 1
    while (i < limit && (code[i].isLetterOrDigit() || code[i] == '_')) i++
    return i
}

private fun readNumberEnd(code: String, start: Int, limit: Int): Int {
    var i = start
    if (i + 1 < limit && code[i] == '0' && (code[i + 1] == 'x' || code[i + 1] == 'X')) {
        i += 2
        while (i < limit && (code[i].isLetterOrDigit() || code[i] == '_')) i++
        return i
    }
    while (i < limit && (code[i].isDigit() || code[i] == '_')) i++
    if (i + 1 < limit && code[i] == '.' && code[i + 1].isDigit()) {
        i++
        while (i < limit && (code[i].isDigit() || code[i] == '_')) i++
    }
    return i
}

private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_'
private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

private fun AnnotatedString.Builder.appendStyled(text: String, style: SpanStyle) {
    if (text.isEmpty()) return
    val from = length
    append(text)
    addStyle(style, from, length)
}
