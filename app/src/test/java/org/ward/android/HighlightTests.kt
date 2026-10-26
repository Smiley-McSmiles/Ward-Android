package org.ward.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ward.android.ui.HighlightPalette
import org.ward.android.ui.highlightCode

/**
 * Guards the behaviour that regressed in the first highlighter: URLs eaten by `//`,
 * shell code with no structure, and prose coloured as source code.
 */
class HighlightTests {
    private val palette = HighlightPalette(
        plain = Color(0xFF000001), keyword = Color(0xFF000002), string = Color(0xFF000003),
        comment = Color(0xFF000004), number = Color(0xFF000005), function = Color(0xFF000006),
        type = Color(0xFF000007), tag = Color(0xFF000008), attribute = Color(0xFF000009),
        punctuation = Color(0xFF00000A), escape = Color(0xFF00000B), variable = Color(0xFF00000C),
        link = Color(0xFF00000D)
    )

    private fun AnnotatedString.styled(text: String, color: Color): Boolean = spanStyles.any {
        it.item.color == color && it.start < it.end && substring(it.start, it.end) == text
    }

    private fun AnnotatedString.styleOf(text: String): Color? = spanStyles.firstOrNull {
        it.start < it.end && substring(it.start, it.end) == text
    }?.item?.color

    private fun hl(code: String, lang: String) = highlightCode(code, lang, palette)

    @Test fun urlsAreNeverLineCommentsInTextBlocks() {
        val url = "vless://YOUR-UUID@SERVER-IP:443?encryption=none&security=reality&sni=www.example.com#" +
            "alma-exit"
        val out = hl(url, "text")
        assertEquals(url, out.text)
        assertTrue("URL should be link-coloured", out.styled(url, palette.link))
        assertFalse("URL must not be a comment", out.spanStyles.any { it.item.color == palette.comment })
    }

    @Test fun shellDistinguishesCommandsFlagsPathsAndVariables() {
        val out = hl("sudo firewall-cmd --permanent --add-port=443/tcp\nprintf '%s\\n' \"\$HOME\"", "sh")
        assertTrue(out.styled("firewall-cmd", palette.function))
        assertTrue(out.styled("--permanent", palette.attribute))
        assertTrue(out.styled("--add-port", palette.attribute))
        assertTrue(out.styled("443", palette.number))
        assertTrue(out.styled("/tcp", palette.type))
        assertTrue(out.styled("\$HOME", palette.variable))
        // shell single quotes are literal, so no escape span inside '%s\n'
        assertTrue(out.styled("'%s\\n'", palette.string))
        assertFalse(out.spanStyles.any { it.item.color == palette.escape })
    }

    @Test fun unterminatedStringDoesNotSwallowFollowingLines() {
        val code = "echo 'abc\nsudo systemctl restart nginx"
        val out = hl(code, "sh")
        assertTrue(out.styled("'abc", palette.string))
        assertTrue("next line still highlighted", out.styled("systemctl", palette.function))
    }

    @Test fun kotlinInterpolationAndComments() {
        val code = "// note\n@Composable\nfun greet(name: String) = \"hi \$name \${name.length}\""
        val out = hl(code, "kotlin")
        assertTrue(out.styled("// note", palette.comment))
        assertTrue(out.styled("@Composable", palette.attribute))
        assertTrue(out.styled("fun", palette.keyword))
        assertTrue(out.styled("greet", palette.function))
        assertTrue(out.styled("\$name", palette.variable))
        assertTrue(out.styled("\${name.length}", palette.variable))
    }

    @Test fun kotlinBlockCommentsNest() {
        val out = hl("/* outer /* inner */ still comment */ val x = 1", "kotlin")
        assertTrue(out.styled("/* outer /* inner */ still comment */", palette.comment))
        assertTrue(out.styled("val", palette.keyword))
    }

    @Test fun jsonKeysDifferFromValues() {
        val out = hl("{\"key\": \"value\", \"n\": 3}", "json")
        assertTrue(out.styled("\"key\"", palette.attribute))
        assertTrue(out.styled("\"value\"", palette.string))
        assertTrue(out.styled("3", palette.number))
    }

    @Test fun yamlKeysAndScalars() {
        val out = hl("server:\n  host: \"example.com\"\n  port: 8443\n  debug: true\n", "yaml")
        assertTrue(out.styled("server", palette.attribute))
        assertTrue(out.styled("host", palette.attribute))
        assertTrue(out.styled("\"example.com\"", palette.string))
        assertTrue(out.styled("8443", palette.number))
        assertTrue(out.styled("true", palette.keyword))
    }

    @Test fun sqlKeywordsAreCaseInsensitive() {
        val out = hl("Select id From users Where name Like 'a%' -- note", "sql")
        assertTrue(out.styled("Select", palette.keyword))
        assertTrue(out.styled("From", palette.keyword))
        assertTrue(out.styled("Where", palette.keyword))
        assertTrue(out.styled("'a%'", palette.string))
        assertTrue(out.styled("-- note", palette.comment))
    }

    @Test fun diffLinesAreColouredBySign() {
        val out = hl("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new", "diff")
        assertEquals(palette.string, out.styleOf("+new"))
        assertEquals(palette.tag, out.styleOf("-old"))
        assertEquals(palette.function, out.styleOf("@@ -1 +1 @@"))
    }

    @Test fun xmlTagsAttributesAndEntities() {
        val out = hl("<root a=\"1\">x &amp; y</root>", "xml")
        assertTrue(out.styled("root", palette.tag))
        assertTrue(out.styled("a", palette.attribute))
        assertTrue(out.styled("\"1\"", palette.string))
        assertTrue(out.styled("&amp;", palette.escape))
    }

    @Test fun proseInUnknownBlocksStaysPlain() {
        val out = hl("we went with the other plan // not code", "")
        assertEquals("text must be preserved", "we went with the other plan // not code", out.text)
        assertFalse("keywords must not leak into prose", out.styled("with", palette.keyword))
        assertFalse("slashes must not become comments", out.spanStyles.any { it.item.color == palette.comment })
    }

    @Test fun everyLanguagePreservesTextExactly() {
        val samples = listOf(
            "sh" to "sudo ls -la /tmp && echo \"hi \$USER\"",
            "kotlin" to "fun f() = \"\${1 + 1}\" // c",
            "python" to "def f(x):\n    return f\"{x}\"",
            "json" to "{\"a\": [1, true, null]}",
            "xml" to "<a b='c'>&#169;</a>",
            "sql" to "select 1 -- x",
            "yaml" to "a:\n  - b: 1",
            "diff" to "-a\n+b",
            "rust" to "fn main() { let s = \"x\"; }",
            "go" to "package main\nfunc main() {}",
            "js" to "const a = `x\${1}`",
            "unknown" to "plain words"
        )
        for ((lang, code) in samples) {
            assertEquals("offsets changed for $lang", code, hl(code, lang).text)
        }
    }

    @Test fun veryLargeBlocksFallBackToPlainWithoutHanging() {
        val big = "fun x() { /* ".repeat(6000)
        val out = hl(big, "kotlin")
        assertEquals(big, out.text)
        assertTrue("over-long blocks are left uncoloured", out.spanStyles.isEmpty())
    }
}
