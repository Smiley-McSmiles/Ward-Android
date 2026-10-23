package org.ward.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed class Block {
    data class Paragraph(val text: String) : Block()
    data class Code(val lang: String, val text: String) : Block()
    data class Quote(val lines: List<String>) : Block()
    data class Bullet(val items: List<String>) : Block()
    data class Table(val rows: List<List<String>>) : Block()
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface,
                 largeEmoji: Boolean = true) {
    val blocks = parseBlocks(text)
    Column(modifier) {
        for (b in blocks) {
            when (b) {
                is Block.Code -> {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(b.text.trimEnd(), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            modifier = Modifier.padding(10.dp), color = color)
                    }
                }
                is Block.Quote -> Row(Modifier.padding(vertical = 2.dp)) {
                    Box(Modifier.width(3.dp).heightIn(min = 18.dp).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(8.dp))
                    Text(b.lines.joinToString("\n"), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
                }
                is Block.Bullet -> Column(Modifier.padding(vertical = 2.dp)) {
                    b.items.forEach { item ->
                        Row { Text("•  ", color = color); InlineMarkdown(item, color) }
                    }
                }
                is Block.Table -> Column(Modifier.padding(vertical = 4.dp)) {
                    b.rows.forEachIndexed { i, row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { cell ->
                                Text(cell, modifier = Modifier.weight(1f).padding(4.dp),
                                    fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.bodySmall, color = color)
                            }
                        }
                        if (i == 0) HorizontalDivider()
                    }
                }
                is Block.Paragraph -> {
                    if (b.text.isNotBlank()) {
                        val emojiOnly = largeEmoji && b.text.length <= 12 && b.text.none { it.isLetterOrDigit() }
                        if (emojiOnly) Text(b.text, fontSize = 42.sp, modifier = Modifier.padding(vertical = 2.dp))
                        else InlineMarkdown(b.text, color)
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineMarkdown(text: String, color: Color) {
    val uri = LocalUriHandler.current
    val annotated = inline(text, color, MaterialTheme.colorScheme.primary)
    Text(annotated, style = MaterialTheme.typography.bodyLarge, color = color,
        modifier = Modifier.clickable {
            annotated.getStringAnnotations("URL", 0, annotated.length).firstOrNull()?.let { a ->
                runCatching { uri.openUri(a.item) }
            }
        })
}

private fun parseBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = text.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            val lang = line.trim().removePrefix("```")
            i++
            val code = mutableListOf<String>()
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.add(lines[i]); i++ }
            i++
            out.add(Block.Code(lang, code.joinToString("\n")))
        } else if (line.trimStart().startsWith(">")) {
            val q = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                q.add(lines[i].trimStart().removePrefix(">").removePrefix(" ")); i++
            }
            out.add(Block.Quote(q))
        } else if (line.trimStart().startsWith("|") && i + 1 < lines.size && lines[i + 1].contains("---")) {
            val rows = mutableListOf<List<String>>()
            rows.add(line.trim().trim('|').split('|').map { it.trim() })
            i += 2
            while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                rows.add(lines[i].trim().trim('|').split('|').map { it.trim() }); i++
            }
            out.add(Block.Table(rows))
        } else if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ||
            line.trimStart().matches(Regex("^\\d+[.)] .*"))) {
            val items = mutableListOf<String>()
            while (i < lines.size && (lines[i].trimStart().startsWith("- ") || lines[i].trimStart().startsWith("* ") ||
                    lines[i].trimStart().matches(Regex("^\\d+[.)] .*")))) {
                items.add(lines[i].trimStart().replaceFirst(Regex("^([-*]|\\d+[.)]) "), "")); i++
            }
            out.add(Block.Bullet(items))
        } else if (line.isBlank()) {
            i++
        } else {
            val p = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() && !lines[i].trimStart().startsWith("```") &&
                !lines[i].trimStart().startsWith(">") && !lines[i].trimStart().startsWith("|") &&
                !lines[i].trimStart().startsWith("- ") && !lines[i].trimStart().startsWith("* ")) {
                p.add(lines[i]); i++
            }
            out.add(Block.Paragraph(p.joinToString("\n")))
        }
    }
    return out
}

private fun inline(text: String, color: Color, linkColor: Color): AnnotatedString {
    val b = AnnotatedString.Builder()
    appendInline(b, text, null, color, linkColor)
    return b.toAnnotatedString()
}

private fun merge(base: SpanStyle?, style: SpanStyle): SpanStyle =
    base?.merge(style) ?: style

private fun appendInline(b: AnnotatedString.Builder, src: String, base: SpanStyle?, color: Color, linkColor: Color) {
    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = color.copy(alpha = 0.08f))
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val italic = SpanStyle(fontStyle = FontStyle.Italic)
    val strike = SpanStyle(textDecoration = TextDecoration.LineThrough)
    fun append(text: String, style: SpanStyle?) {
        if (text.isEmpty()) return
        val start = b.length
        b.append(text)
        if (style != null) b.addStyle(style, start, b.length)
    }
    var i = 0
    while (i < src.length) {
        val c = src[i]
        // Backslash escapes the next punctuation character.
        if (c == '\\' && i + 1 < src.length) {
            append(src[i + 1].toString(), base); i += 2; continue
        }
        // Inline code wins over every other inline rule, and its content is literal.
        if (c == '`') {
            val end = src.indexOf('`', i + 1)
            if (end > i + 1) {
                append(src.substring(i + 1, end), merge(base, codeStyle)); i = end + 1; continue
            }
        }
        // [label](url)
        if (c == '[') {
            val close = src.indexOf(']', i + 1)
            if (close > i && close + 1 < src.length && src[close + 1] == '(') {
                val end = src.indexOf(')', close + 2)
                if (end > close + 2) {
                    val label = src.substring(i + 1, close)
                    val url = src.substring(close + 2, end)
                    val start = b.length
                    appendInline(b, label, merge(base, SpanStyle(color = linkColor,
                        textDecoration = TextDecoration.Underline)), color, linkColor)
                    b.addStringAnnotation("URL", url, start, b.length)
                    i = end + 1; continue
                }
            }
        }
        // Bare URLs.
        if (c == 'h' && (src.startsWith("http://", i) || src.startsWith("https://", i))) {
            var end = i
            while (end < src.length && !src[end].isWhitespace() && src[end] !in ")]}") end++
            append(src.substring(i, end), merge(base, SpanStyle(color = linkColor,
                textDecoration = TextDecoration.Underline)))
            b.addStringAnnotation("URL", src.substring(i, end), b.length - (end - i), b.length)
            i = end; continue
        }
        // Strong and emphasis. The longer delimiters must be tested first.
        if (src.startsWith("***", i)) {
            val end = src.indexOf("***", i + 3)
            if (end > i + 3) {
                appendInline(b, src.substring(i + 3, end), merge(merge(base, bold), italic), color, linkColor)
                i = end + 3; continue
            }
        }
        if (src.startsWith("**", i) || src.startsWith("__", i)) {
            val delim = src.substring(i, i + 2)
            val end = src.indexOf(delim, i + 2)
            if (end > i + 2) {
                appendInline(b, src.substring(i + 2, end), merge(base, bold), color, linkColor)
                i = end + 2; continue
            }
        }
        if (src.startsWith("~~", i)) {
            val end = src.indexOf("~~", i + 2)
            if (end > i + 2) {
                appendInline(b, src.substring(i + 2, end), merge(base, strike), color, linkColor)
                i = end + 2; continue
            }
        }
        if (c == '*' || c == '_') {
            val end = src.indexOf(c, i + 1)
            val prev = if (i > 0) src[i - 1] else ' '
            val next = if (end >= 0 && end + 1 < src.length) src[end + 1] else ' '
            // Underscores inside words (foo_bar_baz) are not emphasis.
            val wordBoundary = c != '_' || (!prev.isLetterOrDigit() && !next.isLetterOrDigit())
            if (end > i + 1 && wordBoundary) {
                appendInline(b, src.substring(i + 1, end), merge(base, italic), color, linkColor)
                i = end + 1; continue
            }
        }
        append(c.toString(), base)
        i++
    }
}
