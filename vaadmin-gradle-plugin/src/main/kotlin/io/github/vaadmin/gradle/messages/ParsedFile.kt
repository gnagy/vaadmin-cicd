package io.github.vaadmin.gradle.messages

internal data class ParsedFile(
    val segments: List<Segment>
)

internal data class Segment(
    val comments: List<String>,
    val entries: MutableList<PropertyEntry> = mutableListOf()
)

internal data class PropertyEntry(
    val key: String,
    val value: String,
)
