# Agent skills — `skills/`

Claude Code skills for the same applications, invoked by hand only (`disable-model-invocation`). Link a
skill's directory into `~/.claude/skills/` to use it.

| Skill               | What it does                                                                                                                                                                                                                                                                             |
|---------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jvm-memory-sizing` | Reads a running application's memory figures from Actuator, resident and native memory included when the application registers them, and proposes JVM flags sized against the deploy config's memory limit, plus a row for the project's measurement log. Changes nothing without asking |

`jvm-memory-sizing` reads resident memory from
[micrometer-jvm-extras](https://github.com/mweirauch/micrometer-jvm-extras) (`process.memory.rss`) and native
memory from [nmt-metrics](https://github.com/glandais/nmt-metrics) (`jvm.memory.nmt.committed`) when the
application registers them; without them it asks for the container's memory use. Its instructions are in
[skills/jvm-memory-sizing/SKILL.md](../skills/jvm-memory-sizing/SKILL.md).
