# Changelog

## 0.1.0 — unreleased

- `io.github.vaadmin.application`, `build-info` and `jib`: the image built with Jib from Spring Boot's
  production classpath and, for Vaadin, the production bundle; checks that the image matches the jar;
  tagged and labelled from `build-info.properties`; `linux/amd64` by default.
- `io.github.vaadmin.messages`: `messagesFormat` and `messagesCheck` for message bundles.
- Release scripts: `preflight`, `bump-image-tag`, `release`.
- Agent skill: `jvm-memory-sizing`.
