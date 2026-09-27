package io.github.vaadmin.gradle.messages

import java.lang.StringBuilder

/**
 * Parser for Java .properties files with UTF-8 encoding, supporting:
 * - Comments starting with '#' or '!' (after leading whitespace)
 * - Line continuation when a line ends with an unescaped backslash
 * - Key/value separators '=', ':', or whitespace
 * - Standard escapes: \t, \n, \r, \f, \\, space, \:, \=, and unicode \uXXXX
 *
 * Additionally builds logical "segments": consecutive comment lines form a segment header,
 * which is followed by key/value pairs until the next comment block or EOF. Blank lines are ignored.
 */
internal object PropertiesParsing {

    fun parse(input: String): ParsedFile {
        val logicalLines = readLogicalLines(input)
        val segments = mutableListOf<Segment>()
        var pendingComments = mutableListOf<String>()
        var currentSegment: Segment? = null

        for (ll in logicalLines) {
            if (ll.isBlank) continue
            if (ll.isComment) {
                // Start or continue a comment block
                pendingComments.add(ll.raw)
                continue
            }

            // Property line
            val (rawKey, rawValue) = splitKeyValue(ll.raw)
            val key = unescape(rawKey)
            val value = unescape(rawValue)

            // If we have pending comments, start a new segment
            if (pendingComments.isNotEmpty() || currentSegment == null) {
                currentSegment = Segment(comments = pendingComments.toList())
                segments.add(currentSegment)
                pendingComments = mutableListOf()
            }
            currentSegment.entries.add(PropertyEntry(key, value))
        }

        // If file started with properties and never had comments, ensure one empty segment exists
        if (segments.isEmpty() && pendingComments.isNotEmpty()) {
            segments.add(Segment(comments = pendingComments.toList()))
        }

        return ParsedFile(segments)
    }

    /**
     * Serialize a [ParsedFile] into .properties text.
     */
    fun toText(parsed: ParsedFile): String {
        val sb = StringBuilder()
        parsed.segments.forEachIndexed { index, segment ->
            // Write comments exactly as parsed
            for (c in segment.comments) {
                sb.append(c).append('\n')
            }
            // Write key=value lines using .properties escaping rules
            for (e in segment.entries) {
                sb.append(escape(e.key, escapeLeadingSpace = true))
                    .append('=')
                    // For values we don't need to escape a leading space specially,
                    // since the separator already disambiguates
                    .append(escape(e.value, escapeLeadingSpace = false))
                    .append('\n')
            }
            // Blank line between segments (but not after the last one)
            if (index < parsed.segments.lastIndex) sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Write a [ParsedFile] to the given [file] using the provided [charset] (default UTF-8).
     */
    fun write(parsed: ParsedFile, file: java.io.File, charset: java.nio.charset.Charset = java.nio.charset.StandardCharsets.UTF_8) {
        val text = toText(parsed)
        file.writeText(text, charset)
    }

    private data class LogicalLine(val raw: String, val isComment: Boolean, val isBlank: Boolean)

    private fun readLogicalLines(text: String): List<LogicalLine> {
        val result = mutableListOf<LogicalLine>()
        val physical = text.splitToSequence('\n')
        val buf = StringBuilder()
        var pending = false

        fun flushLine(line: String) {
            val trimmedLeading = line.dropWhile { it == ' ' || it == '\t' || it == '\u000C' }
            val isBlank = trimmedLeading.isEmpty()
            val isComment = !isBlank && (trimmedLeading.startsWith("#") || trimmedLeading.startsWith("!"))
            result.add(LogicalLine(line, isComment, isBlank))
        }

        for (rawLine in physical) {
            var line = rawLine
            // Remove CR if present (Windows newlines)
            if (line.endsWith('\r')) line = line.dropLast(1)

            if (!pending) buf.clear()

            // If this is a continuation line, per .properties rules, skip leading whitespace
            if (pending) {
                line = line.dropWhile { it == ' ' || it == '\t' || it == '\u000C' }
            }

            buf.append(line)

            // Count trailing backslashes to determine if the last one is escaping the newline
            var backslashCount = 0
            var i = buf.length - 1
            while (i >= 0 && buf[i] == '\\') {
                backslashCount++
                i--
            }
            val continues = backslashCount % 2 == 1
            if (continues) {
                // Remove one trailing backslash that escapes the newline
                buf.setLength(buf.length - 1)
                pending = true
                // Next physical line: leading whitespace will be skipped before appending
            } else {
                // finalize logical line
                val combined = buf.toString()
                // Per spec, when continuation happens, the newline is removed and leading whitespace on the continued line is ignored
                // We handle that by trimming next line's leading whitespace when appending
                flushLine(combined)
                pending = false
            }
        }
        // If file ended with an unfinished continuation, still flush
        if (pending) {
            flushLine(buf.toString())
        }
        return result
    }

    private fun splitKeyValue(line: String): Pair<String, String> {
        // Remove leading whitespace
        var idx = 0
        val n = line.length
        while (idx < n && (line[idx] == ' ' || line[idx] == '\t' || line[idx] == '\u000C')) idx++

        var keyEnd = -1
        var sepIdx = -1
        var i = idx
        while (i < n) {
            val c = line[i]
            if (c == '\\') {
                i += 2 // skip escaped char
                continue
            }
            if (c == '=' || c == ':' ) {
                sepIdx = i
                break
            }
            if (c == ' ' || c == '\t' || c == '\u000C') {
                // whitespace can be a separator if not escaped; we will look ahead for explicit sep or treat as sep
                sepIdx = i
                break
            }
            i++
        }

        if (sepIdx >= 0) {
            keyEnd = sepIdx
        } else {
            keyEnd = n
        }

        val rawKey = line.substring(idx, keyEnd)

        var valueStart = keyEnd
        if (valueStart < n) {
            // Skip over separator characters and following whitespace
            var sawSepChar = false
            if (line[valueStart] == '=' || line[valueStart] == ':') {
                sawSepChar = true
                valueStart++
            }
            // If whitespace was the separator, or after explicit sep, skip whitespace
            while (valueStart < n && (line[valueStart] == ' ' || line[valueStart] == '\t' || line[valueStart] == '\u000C')) valueStart++
            // If we hit an explicit sep later (rare case of whitespace then sep), skip one sep
            if (!sawSepChar && valueStart < n && (line[valueStart] == '=' || line[valueStart] == ':')) {
                valueStart++
                while (valueStart < n && (line[valueStart] == ' ' || line[valueStart] == '\t' || line[valueStart] == '\u000C')) valueStart++
            }
        }

        val rawValue = if (valueStart <= n) line.substring(valueStart) else ""
        return rawKey to rawValue
    }

    private fun hexDigit(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }

    fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= s.length) {
                // trailing backslash, keep as is
                out.append('\\')
                i++
                continue
            }
            val n = s[i + 1]
            when (n) {
                't' -> { out.append('\t'); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                ' ' -> { out.append(' '); i += 2 }
                ':' -> { out.append(':'); i += 2 }
                '=' -> { out.append('='); i += 2 }
                'u' -> {
                    // Unicode escape: \uXXXX
                    var code = 0
                    var ok = true
                    if (i + 6 <= s.length) {
                        for (k in 0 until 4) {
                            val d = hexDigit(s[i + 2 + k])
                            if (d < 0) { ok = false; break }
                            code = (code shl 4) or d
                        }
                    } else ok = false
                    if (ok) {
                        out.append(code.toChar())
                        i += 6
                    } else {
                        // not a valid unicode escape; keep as-is
                        out.append('\\').append('u')
                        i += 2
                    }
                }
                else -> {
                    // Unknown escape, keep the escaped char literally (as java.util.Properties would)
                    out.append(n)
                    i += 2
                }
            }
        }
        return out.toString()
    }

    @Suppress("unused")
    fun escape(s: String, escapeLeadingSpace: Boolean = true): String {
        val out = StringBuilder(s.length * 2)
        for ((idx, ch) in s.withIndex()) {
            when (ch) {
                ' ' -> if (idx == 0 && escapeLeadingSpace) out.append("\\ ") else out.append(' ')
                '\\' -> out.append("\\\\")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\u000C' -> out.append("\\f")
                '=' -> out.append("\\=")
                ':' -> out.append("\\:")
                '#' -> out.append("\\#")
                '!' -> out.append("\\!")
                else -> {
                    // Do not escape non-ASCII characters. Modern Java uses UTF-8 for message bundles.
                    // We still escape special separators and control characters above.
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }
}
