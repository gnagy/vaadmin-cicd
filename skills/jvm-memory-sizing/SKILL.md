---
name: jvm-memory-sizing
description: Review a Spring Boot application's JVM memory flags against what it actually uses in one environment, and propose a diff to its deploy config and a row for its measurement log.
disable-model-invocation: true
argument-hint: "[environment, e.g. test or prod]"
---

# JVM memory sizing

Size the JVM of one Spring Boot application in one environment from what it actually uses: read the
running application's figures, compare them with the flags and the memory limit in its deploy config,
and propose new flags. The flags are static, kept beside the limit they are sized against, and reviewed
by hand when something changes; this skill is that review.

**It reads and proposes. It changes nothing on its own**: no container is stopped or restarted, no
deploy config is edited and no log row is written until the user has seen the proposal and said so.

## When to run it

- after **a week or more of uptime** that includes a busy period;
- after **adding many features or a large dependency**, or when loaded classes have grown more than about
  10% over the last reading;
- after **a Spring Boot, Vaadin or JDK upgrade**, or a change of builder or base image;
- **before changing the memory limit**.

A reading taken minutes after a start is a lower bound. Say so in the report rather than sizing from it.

## 1. Find the project's values

Look them up in the project's instructions, its wiki and its deploy repo before asking. Ask for what is
still missing; do not guess a host or a URL.

| Value               | What it is                                                                        |
|---------------------|-----------------------------------------------------------------------------------|
| **Environment**     | The argument, e.g. `test` or `prod`                                               |
| **Deploy config**   | The file with the memory limit and the JVM flags: a compose file, a manifest      |
| **Service**         | The service or container name in it                                               |
| **Application URL** | Where the environment's UI is served; Actuator is under `/actuator` there         |
| **Container total** | The container's own memory use, asked of the user: see below                      |
| **Measurement log** | Where the project records readings: a wiki note, a doc. Earlier rows are baseline |

From the deploy config, read:

- the memory limit (`deploy.resources.limits.memory`, `resources.limits.memory`) and the CPU limit;
- the JVM flags and the variable that carries them;
- the image, and from it **the builder**:
  - **Paketo** (`BPL_*` variables, `/workspace` paths): flags go in `JAVA_TOOL_OPTIONS`, the calculator
    still runs and must be given `BPL_JVM_HEAD_ROOM=0` and a `BPL_JVM_THREAD_COUNT`, or it refuses to
    start once the regions add up close to the limit.
  - **Jib or a plain JRE base**: nothing computes the flags. They go in `JDK_JAVA_OPTIONS`, which only the
    `java` launcher reads, together with `MALLOC_ARENA_MAX=2` and `-XX:+ExitOnOutOfMemoryError`, which
    Paketo used to set and a hand-written config loses. Without `-Xmx` the heap is 25% of the limit.

## 2. Read the running application

### Actuator, in the browser

Actuator is usually behind the application's sign-in. Open the application URL in the browser the
project uses for UI checks, let the user sign in if needed, and evaluate
[actuator-readings.js](actuator-readings.js) on the application's own origin. It only reads, and returns
one object:

| Field               | Metric                                              | Note                                   |
|---------------------|-----------------------------------------------------|----------------------------------------|
| `uptimeHours`       | `process.uptime`                                    | Under a day: say the reading is early  |
| `classesLoaded`     | `jvm.classes.loaded`                                | Compare with the last log row          |
| `threadsLive`       | `jvm.threads.live`                                  |                                        |
| `threadsPeak`       | `jvm.threads.peak`                                  | Highest since start                    |
| `metaspaceUsedMB`   | `jvm.memory.used`, `id:Metaspace`                   | Only grows, so close to its peak       |
| `codeCacheUsedMB`   | `jvm.memory.used`, the three `CodeHeap` ids, summed | Only grows, so close to its peak       |
| `directUsedMB`      | `jvm.buffer.memory.used`, `id:direct`               |                                        |
| `heapAfterGcMB`     | `jvm.gc.live.data.size`                             | What survives a collection             |
| `heapCommittedMB`   | `jvm.memory.committed`, `area:heap`                 |                                        |
| `heapMaxMB`         | `jvm.memory.max`, `area:heap`                       | Confirms the `-Xmx` actually in effect |
| `osThreads`         | `process.threads`                                   | All OS threads, GC and JIT included    |
| `residentMB`        | `process.memory.rss`                                | The process's resident memory          |
| `swapMB`            | `process.memory.swap`                               | Anything above 0 is a finding          |
| `limitMB`           | `process.memory.limit.hard`                         | The limit the container actually got   |
| `nativeCommittedMB` | `jvm.memory.nmt.committed`, per `category`          | NMT, committed MB per area             |

A missing metric comes back as `null`; report it as not available rather than as zero.

### Native memory: from the application, or from the user

Actuator's own metrics cannot see JVM native memory. The last five fields fill that in when the
application registers them:

- `process.*` from [micrometer-jvm-extras](https://github.com/mweirauch/micrometer-jvm-extras)'
  `ProcessMemoryMetrics` and `ProcessThreadMetrics`, read from `/proc` and the cgroup, Linux only;
- `jvm.memory.nmt.*` from [nmt-metrics](https://github.com/glandais/nmt-metrics), which configures
  itself in Spring Boot and registers nothing unless the JVM runs with `-XX:NativeMemoryTracking=summary`.
  Read it per `category`: the untagged value adds `total` to the categories and comes out doubled.

When `residentMB` is `null`, ask the user for the container's current memory use, read from whatever
their environment offers, and do not go looking for access to the host yourself. With neither, take the
native figure from the last log row and say in the report that it is assumed. When `limitMB` differs
from the deploy config's limit, the flags are sized against the wrong number: report that first.

**Native overhead = resident memory (or the container's total) − (heap committed + metaspace + code
cache + direct).** `nativeCommittedMB` says where it goes: `gc` grows with the heap, `symbol` and
`class` with the classes, `thread` with the threads. Resident memory runs a few tens of MB above NMT's
`total`, for shared libraries and the C allocator that NMT does not track. NMT itself costs about 10 MB,
so turn it on to find where native memory goes, not for every reading.

### NMT at a stop, without the metrics

An application with NMT on but without nmt-metrics still prints the summary when the JVM stops, given
`-XX:+PrintNMTStatistics`. Stopping is the user's call, never this skill's, and the old container's logs
must be read before `up -d` removes them.

## 3. Turn the reading into flags

| Flag                    | From            | Rule                                                                            |
|-------------------------|-----------------|---------------------------------------------------------------------------------|
| `MaxMetaspaceSize`      | metaspace used  | reading + 40–50%, rounded up to 16 MB                                           |
| `ReservedCodeCacheSize` | code cache used | reading + 50–65%, rounded up to 16 MB                                           |
| Thread budget           | `threadsPeak`   | peak + 25%                                                                      |
| `MaxDirectMemorySize`   | direct used     | keep 128 MB unless the reading passes ~60 MB, then reading × 2                  |
| Native headroom         | native overhead | reading + 25%, plus 1 MB per thread between `threadsLive` and the thread budget |
| `-Xmx`                  | the limit       | what is left, rounded down to 16 MB                                             |

Thread stacks are native memory, so the native reading already holds the live threads' stacks; the
headroom adds room for the rest of the budget rather than counting every thread again. With no container
total, take the native figure from the last log row and say in the report that it is assumed.

**The sum must fit:** `-Xmx` + `MaxMetaspaceSize` + `ReservedCodeCacheSize` + `MaxDirectMemorySize` +
native headroom ≤ the memory limit. Show the sum in the report. On a Paketo image, also check the
calculator's own sum — the same regions plus 1 MB per budgeted thread — or the container will not start.

Then check the result, and report each that fails:

- **Heap after GC** at the busiest reading stays under half of the proposed `-Xmx`. If not, the limit is
  too small for this application, and that is the finding, not a smaller margin.
- **A cap under its reading** means the current config is already wrong: say so first.
- **Any flag the config does not set** falls back to a JVM default; the direct memory default is tiny and
  a Jib image with no `-Xmx` gets 25% of the limit.

Keep `-XX:+ExitOnOutOfMemoryError`. GC stays the JVM's default choice (G1 at 2+ CPUs and ~2 GB) unless
the project says otherwise; do not propose a collector change as part of sizing.

## 4. Report, then propose

Lead with the verdict: **the current flags fit**, **they fit but waste memory**, or **they are wrong**,
then the evidence. Then:

1. **A table**, one row per region: reading, current cap, proposed cap, margin.
2. **The sum** against the limit, for the current and the proposed flags.
3. **The diff to the deploy config**: the flags, in the variable the builder reads, with a comment saying
   they are sized against the limit and naming where the readings are logged. Keep the comment on the
   limit line that says the flags depend on it.
4. **The log row** for the measurement log, in the log's own columns.

Apply the diff and write the row only after the user agrees. A new sizing lands as its own change: never
together with a change of builder, base image or framework version, because a regression would then have
two possible causes.
