package io.github.ccbitz.mcsrcmcp.server

import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import javax.management.ObjectName
import kotlinx.coroutines.delay

private const val DEFAULT_IDLE_MINUTES = 30L

// Coarse on purpose: the timeout is tens of minutes, so checking every minute lands within a
// minute of it and costs nothing.
private val MAX_IDLE_CHECK_INTERVAL: Duration = Duration.ofMinutes(1)

/**
 * How long the server may sit without a tool call before it drops its warm state, from
 * `MCSRC_MCP_IDLE_MINUTES`. Null (`0`) keeps everything warm for the life of the process.
 */
fun resolveIdleTimeout(env: Map<String, String> = System.getenv()): Duration? {
    val raw = env["MCSRC_MCP_IDLE_MINUTES"] ?: return Duration.ofMinutes(DEFAULT_IDLE_MINUTES)
    val minutes = raw.toLongOrNull()?.takeIf { it >= 0 } ?: return Duration.ofMinutes(DEFAULT_IDLE_MINUTES)
    return if (minutes == 0L) null else Duration.ofMinutes(minutes)
}

/**
 * Counts tool calls in flight and when the last one finished. A server that's used for an hour and
 * then left alone for days would otherwise keep every warm workspace and resident bridge JVM the
 * whole time: the periodic GC hands back garbage, but warm state isn't garbage.
 */
class IdleTracker(private val nanoClock: () -> Long = System::nanoTime) {
    private val running = AtomicInteger()

    @Volatile
    private var lastActivity = nanoClock()

    suspend fun <T> track(block: suspend () -> T): T {
        running.incrementAndGet()
        try {
            return block()
        } finally {
            lastActivity = nanoClock()
            running.decrementAndGet()
        }
    }

    /** Time since the last call finished, or null while one is running. */
    fun idleFor(): Duration? =
        if (running.get() > 0) null else Duration.ofNanos(nanoClock() - lastActivity)

    /** Changes on every call, so a release can tell whether anything happened since the last one. */
    fun activityMark(): Long = lastActivity
}

/**
 * Hands the memory freed by a release back to the OS. Dropping the workspaces alone barely moves
 * RSS (measured, two versions warm: 1.2GB before, 1.2GB a minute after): the idle periodic GC is a
 * concurrent cycle that leaves most of the dead old generation in place, and glibc keeps freed
 * native memory in its arenas. One full GC lets G1 shrink the heap to MaxHeapFreeRatio, and the
 * trim - what `jcmd <pid> System.trim_native_heap` does - returns the arenas (measured: 750MB
 * down to 184MB, against 164MB for a fresh server). The trim is a no-op off glibc and
 * best-effort everywhere.
 */
fun compactMemory() {
    System.gc()
    runCatching {
        ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"),
            "systemTrimNativeHeap",
            null,
            null,
        )
    }
}

/**
 * Calls [release] once per idle stretch of at least [timeout]. [release] returns false when it
 * couldn't release yet (a background build still running), and is tried again on the next check.
 * Runs until its coroutine is cancelled.
 */
suspend fun releaseWhenIdle(tracker: IdleTracker, timeout: Duration, release: () -> Boolean) {
    val interval = minOf(timeout, MAX_IDLE_CHECK_INTERVAL)
    var releasedAt: Long? = null
    while (true) {
        delay(interval.toMillis())
        val idle = tracker.idleFor() ?: continue
        val mark = tracker.activityMark()
        if (idle >= timeout && releasedAt != mark && release()) {
            releasedAt = mark
        }
    }
}
