package io.github.vaadmin.gradle.messages

import org.gradle.api.logging.Logger
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Sorting and checking message bundles.
 *
 * A bundle is formatted when its entries are sorted by key within each comment-delimited group, and the
 * groups are sorted by their first key. Only the order is judged: spacing, separators and escaping are
 * left alone until the order changes and the file is rewritten.
 */
internal object MessageBundles {
    fun parse(file: File): ParsedFile = PropertiesParsing.parse(file.readText(StandardCharsets.UTF_8))

    fun sort(parsed: ParsedFile): ParsedFile {
        val segmentsWithSortedEntries =
            parsed.segments.map { segment ->
                segment.copy(entries = segment.entries.sortedBy { it.key }.toMutableList())
            }
        return parsed.copy(segments = segmentsWithSortedEntries.sortedWith(compareBy { it.entries.firstOrNull()?.key ?: "" }))
    }

    fun isFormatted(parsed: ParsedFile): Boolean = parsed == sort(parsed)

    fun write(
        parsed: ParsedFile,
        file: File,
    ) = PropertiesParsing.write(parsed, file, StandardCharsets.UTF_8)

    /** Values used by more than one key — often a copy-paste slip or a missing translation. */
    fun logDuplicateValues(
        file: File,
        parsed: ParsedFile,
        logger: Logger,
    ) {
        val duplicates =
            parsed.segments
                .flatMap { it.entries }
                .groupBy({ it.value }, { it.key })
                .filterValues { it.size > 1 }
        duplicates.forEach { (value, keys) ->
            logger.warn("${file.name}: duplicate value '$value' used by keys: ${keys.sorted().joinToString(", ")}")
        }
        if (duplicates.isNotEmpty()) {
            logger.warn("${file.name}: ${duplicates.size} duplicate value group(s). Review for copy-paste or missing translations.")
        }
    }
}
