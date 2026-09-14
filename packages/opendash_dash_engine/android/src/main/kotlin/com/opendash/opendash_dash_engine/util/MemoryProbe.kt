package com.opendash.opendash_dash_engine.util

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/**
 * How much memory this process is holding, and how close the system is to reclaiming it.
 *
 * **Why this exists.** On 2026-09-14 a Xiaomi with 3.6 GiB of RAM killed the app twice in
 * twenty minutes with `LOW_MEMORY_KILL`, and the only thing the ride files could say was that
 * it happened. [ExitInfoCollector] now records `pss`/`rss` from the exit record, but that is a
 * reading from the system's LAST SAMPLING of the process, not from the moment it died — and it
 * is absent entirely for a process that died before being sampled, which a 5-12 s session
 * often does. Even when present, one number at the end answers "how much" and not "since
 * when", and a footprint flat at 400 MiB needs a different fix from one that climbed there
 * over four minutes. This line is the reading taken while the process is alive.
 *
 * **Both halves of the line matter.** Our own footprint says whether we are the problem; the
 * system's `avail`/`low` says whether anything would have survived. A kill at 300 MiB on a
 * phone with 80 MiB free is a different finding from a kill at 900 MiB with a gigabyte spare.
 *
 * **Cost.** [Debug.getMemoryInfo] walks `/proc/self/smaps`, which is tens of milliseconds —
 * real money against a 250 ms frame budget. Call it from a coroutine of its own on IO, never
 * from the frame loop, and no more than once a minute.
 */
fun memorySummary(context: Context): String {
    val dbg = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
    fun stat(key: String): Long = dbg.getMemoryStat(key)?.toLongOrNull() ?: -1L
    fun mib(kb: Long): String = if (kb < 0) "?" else "${kb / 1024}MiB"

    val sys = ActivityManager.MemoryInfo()
    val haveSys = runCatching {
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(sys) != null
    }.getOrDefault(false)

    // swap alongside pss, and not as decoration: `summary.total-pss` does NOT include swapped
    // pages, so under ZRAM pressure — which is the state a 3.6 GiB phone spends its time in —
    // the logged footprint FALLS while the real one grows. Reading pss alone there would point
    // the next post-mortem at the wrong conclusion entirely. Found by review.
    //
    // The four components are the big ones, not a partition: `private-other` and `system` are
    // left out, so they sum to less than pss by design. Hence "of which".
    return "pss=${mib(stat("summary.total-pss"))} swap=${mib(stat("summary.total-swap"))} " +
        "of which java=${mib(stat("summary.java-heap"))} " +
        "native=${mib(stat("summary.native-heap"))} " +
        "graphics=${mib(stat("summary.graphics"))} " +
        "code=${mib(stat("summary.code"))} " +
        // The system side. `low` is the flag the killer acts on, `avail` against `threshold`
        // says how much headroom is left before it does. Printed as unknown rather than as
        // zeroes when the service could not be read: `avail=0MiB low=false` contradicts
        // itself and would be believed.
        if (haveSys) {
            "| sys avail=${sys.availMem / 1024 / 1024}MiB " +
                "threshold=${sys.threshold / 1024 / 1024}MiB low=${sys.lowMemory}"
        } else {
            "| sys unavailable"
        }
}
