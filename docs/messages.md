# Message bundles — `io.github.vaadmin.messages`

Two tasks in the style of `ktlintFormat` and `ktlintCheck`:

- `messagesFormat` sorts each bundle in place — entries by key within each comment-delimited group,
  and the groups by their first key. Only bundles whose order changes are rewritten, so review the
  result with `git diff`. It also warns about values shared by several keys.
- `messagesCheck` fails when a bundle is not sorted, and prints nothing else.

Neither runs with the build — they are maintenance tasks, for running by hand, in CI or before a
commit. To check with `check`, add `tasks.check { dependsOn("messagesCheck") }`.

```kotlin
plugins {
    id("io.github.vaadmin.messages") version "0.1.0"
}

vaadminMessages {
    // default: src/main/resources/messages*.properties
    bundles.setFrom(fileTree("src/main/resources/vaadin-i18n") { include("translations*.properties") })
}
```

A rewritten bundle is re-escaped as well as reordered — `!` and `:` in values become `\!` and `\:`, and
whitespace after `=` is dropped. `java.util.Properties` reads both forms the same, so the values do not
change, but the first diff is larger than the reordering alone.
