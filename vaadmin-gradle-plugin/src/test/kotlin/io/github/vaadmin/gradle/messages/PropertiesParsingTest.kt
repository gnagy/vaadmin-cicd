package io.github.vaadmin.gradle.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropertiesParsingTest {

    @Test
    fun parsesSegmentsDelimitedByComments() {
        val input = """
            # First block title
            # more details
            a=b
            c : d

            ! second block
            e f
        """.trimIndent()

        val parsed = PropertiesParsing.parse(input)

        assertEquals(2, parsed.segments.size)

        // First segment
        val s1 = parsed.segments[0]
        assertEquals(listOf("# First block title", "# more details"), s1.comments)
        assertEquals(2, s1.entries.size)
        assertEquals("a", s1.entries[0].key)
        assertEquals("b", s1.entries[0].value)
        assertEquals("c", s1.entries[1].key)
        assertEquals("d", s1.entries[1].value)

        // Second segment
        val s2 = parsed.segments[1]
        assertEquals(listOf("! second block"), s2.comments)
        assertEquals(1, s2.entries.size)
        assertEquals("e", s2.entries[0].key)
        assertEquals("f", s2.entries[0].value)
    }

    @Test
    fun supportsDifferentSeparatorsAndEscapes() {
        val input = """
            # header
            plain=one
            colon:two
            space three
            escaped\ key\:=value with \t tab and \n newline and \r carriage and \f form and \\\\ backslash and\ leading space
            unicode=Hello\u00E9
        """.trimIndent()

        val parsed = PropertiesParsing.parse(input)
        assertEquals(1, parsed.segments.size)
        val entries = parsed.segments[0].entries
        assertEquals(5, entries.size)

        assertEquals("plain", entries[0].key)
        assertEquals("one", entries[0].value)

        assertEquals("colon", entries[1].key)
        assertEquals("two", entries[1].value)

        assertEquals("space", entries[2].key)
        assertEquals("three", entries[2].value)

        assertEquals("escaped key:", entries[3].key)
        val expectedEscapedValue = "value with \t tab and \n newline and \r carriage and \u000C form and \\\\ backslash and leading space"
        assertEquals(expectedEscapedValue, entries[3].value)

        assertEquals("unicode", entries[4].key)
        assertEquals("Helloé", entries[4].value)
    }

    @Test
    fun handlesLineContinuationAndTrimsLeadingWhitespaceOnContinuedLines() {
        val input = """
            key=first line\
                second line\
            \nthird
        """.trimIndent()

        val parsed = PropertiesParsing.parse(input)
        assertEquals(1, parsed.segments.size)
        val e = parsed.segments[0].entries.single()
        assertEquals("key", e.key)
        // Continuations remove the backslash+newline and drop leading spaces on continued lines
        // Also the literal "\\n" in the input becomes a newline via unescape
        assertEquals("first linesecond line\nthird", e.value)
    }

    @Test
    fun fileWithOnlyCommentsYieldsCommentOnlySegment() {
        val input = """
            # only
            # comments
        """.trimIndent()

        val parsed = PropertiesParsing.parse(input)
        assertEquals(1, parsed.segments.size)
        assertTrue(parsed.segments[0].entries.isEmpty())
        assertEquals(listOf("# only", "# comments"), parsed.segments[0].comments)
    }

    @Test
    fun fileStartingWithPropertyCreatesEmptyCommentSegment() {
        val input = "key=value\nnext=two"
        val parsed = PropertiesParsing.parse(input)
        assertEquals(1, parsed.segments.size)
        assertTrue(parsed.segments[0].comments.isEmpty())
        assertEquals(2, parsed.segments[0].entries.size)
    }

    @Test
    fun writesUnicodeAsUtf8LiteralsNotEscapes() {
        val parsed = ParsedFile(
            segments = listOf(
                Segment(
                    comments = listOf("# unicode test"),
                    entries = mutableListOf(
                        PropertyEntry("greeting", "Helló világ"),
                        PropertyEntry("chinese", "中文测试"),
                        PropertyEntry("emoji", "Smile 🙂")
                    )
                )
            )
        )

        val text = PropertiesParsing.toText(parsed)
        // Should contain literal characters, not \uXXXX sequences
        assertTrue(text.contains("Helló világ"))
        assertTrue(text.contains("中文测试"))
        assertTrue(text.contains("Smile 🙂"))
        assertTrue(!text.contains("\\u"))
    }

    @Test
    fun parsesUnicodeEscapesAndRewritesAsLiterals() {
        val input = "unicode=Hello\\u00E9" // Helloé
        val parsed = PropertiesParsing.parse(input)
        val text = PropertiesParsing.toText(parsed)
        // Should be written back as a literal é, not as a unicode escape
        assertTrue(text.contains("Helloé"))
        assertTrue(!text.contains("\\u00E9"))
    }
}
