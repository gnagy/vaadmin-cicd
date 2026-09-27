// Reads the JVM memory figures the jvm-memory-sizing skill sizes from. Read-only.
// Evaluate on the application's own origin, signed in; returns one object, sizes in MB.
async () => {
  const get = async (name, tag) => {
    const query = tag ? '?tag=' + tag[0] + ':' + encodeURIComponent(tag[1]) : '';
    const response = await fetch('/actuator/metrics/' + name + query, { credentials: 'same-origin' });
    return response.ok ? response.json() : null;
  };
  const metric = async (name, tag) => (await get(name, tag))?.measurements?.[0]?.value ?? null;
  const mb = (v) => (v == null ? null : Math.round(v / 1048576));
  const codeHeaps = ["CodeHeap 'profiled nmethods'", "CodeHeap 'non-profiled nmethods'", "CodeHeap 'non-nmethods'"];
  let codeCache = 0;
  for (const id of codeHeaps) codeCache += (await metric('jvm.memory.used', ['id', id])) ?? 0;
  // NMT per category, from nmt-metrics. Always read by tag: the untagged value adds "total" to the rest.
  const nmt = await get('jvm.memory.nmt.committed');
  let nativeCommittedMB = null;
  if (nmt) {
    nativeCommittedMB = {};
    for (const category of nmt.availableTags.find((t) => t.tag === 'category')?.values ?? []) {
      nativeCommittedMB[category] = mb(await metric('jvm.memory.nmt.committed', ['category', category]));
    }
  }
  const uptime = await metric('process.uptime');
  return {
    uptimeHours: uptime == null ? null : Math.round(uptime / 3600),
    classesLoaded: await metric('jvm.classes.loaded'),
    threadsLive: await metric('jvm.threads.live'),
    threadsPeak: await metric('jvm.threads.peak'),
    osThreads: await metric('process.threads'),
    metaspaceUsedMB: mb(await metric('jvm.memory.used', ['id', 'Metaspace'])),
    codeCacheUsedMB: mb(codeCache || null),
    directUsedMB: mb(await metric('jvm.buffer.memory.used', ['id', 'direct'])),
    heapAfterGcMB: mb(await metric('jvm.gc.live.data.size')),
    heapCommittedMB: mb(await metric('jvm.memory.committed', ['area', 'heap'])),
    heapMaxMB: mb(await metric('jvm.memory.max', ['area', 'heap'])),
    residentMB: mb(await metric('process.memory.rss')),
    swapMB: mb(await metric('process.memory.swap')),
    limitMB: mb(await metric('process.memory.limit.hard')),
    nativeCommittedMB,
  };
}
