package io.github.ccbitz.mcsrcmcp.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.sun.management.OperatingSystemMXBean as SunOperatingSystemMXBean

// Below this many misses a JVM start plus JIT warm-up costs more than decompiling in-process does.
// Measured on 26.3, 8 threads: the child took 3.1s for 250 classes, 4.8s for 500, 6.1s for 1,000;
// in-process runs ~8ms a class, and a long-running server's JIT is warm, which tips it further.
private const val MIN_BULK_CLASSES = 750

// Past ~8 threads every class gets slower: measured on a 9950X3D, 8 threads on one CCD ran each class
// at 1.1x its single-thread time once warm, 16 across both CCDs at 2x - memory, not locks - and JIT
// warm-up with 16 threads in one JVM at 8.5x.
private const val MAX_DEFAULT_THREADS = 8

// The jar's bytes and the library context are the base; each thread's share of two runs' processed
// classes is the rest. 8 threads came out clean at 2.5GB on 26.3 with one run, one full GC with two.
private const val HEAP_BASE_MB = 1280L
private const val HEAP_PER_THREAD_MB = 192L

// No progress line for this long and the child is presumed wedged on a class. A whole chunk at
// one thread is seconds; even 26.3's worst class (BlockStateData) processes in under two.
private val STALL_TIMEOUT: Duration = Duration.ofMinutes(2)

// How long to let the output readers drain once the child is gone; they hit EOF as soon as it is.
private const val DRAIN_MILLIS = 5_000L

private const val STDERR_TAIL_LINES = 25

internal const val BULK_DECOMPILE_MAIN = "io.github.ccbitz.mcsrcmcp.server.BulkDecompileMainKt"

/**
 * Threads for the bulk decompile JVM, from `MCSRC_MCP_DECOMPILE_THREADS`. `0` turns the child off:
 * index builds then decompile in-process, [DecompileService.parallelism] classes at a time.
 */
fun resolveBulkDecompileThreads(
    env: Map<String, String> = System.getenv(),
    processors: Int = Runtime.getRuntime().availableProcessors(),
): Int {
    val default = (processors / 2).coerceIn(1, MAX_DEFAULT_THREADS)
    val raw = env["MCSRC_MCP_DECOMPILE_THREADS"] ?: return default
    return raw.toIntOrNull()?.takeIf { it >= 0 } ?: default
}

/**
 * Runs [ChunkedDecompile] in a child JVM ahead of an index build, so the build's reads are cache hits.
 *
 * A child rather than the server's own threads because the decompile wants ~3GB of heap and the
 * server is capped at 1.5GB on purpose: a JVM keeps the heap it grew for good, and the child hands
 * its back to the OS when it exits. It also makes a wedged class killable - one Vineflower run
 * covers hundreds of classes, with no per-class timeout like [DecompileService]'s.
 *
 * Purely an optimisation: whatever goes wrong - not enough free memory, the child crashing or
 * stalling - is logged and the build carries on, decompiling whatever is still missing in-process.
 */
internal object BulkDecompiler {

    // One child at a time: two version builds racing would each want a 3GB heap.
    private val gate = Mutex()

    /**
     * Decompiles [pending] in a child JVM when it's big enough to be worth one and the machine has
     * the memory, reporting the fraction done (0.0-1.0) to [onProgress]. Returns whether the child
     * ran to completion; when it didn't, the pool holds anything from none to most of [pending].
     */
    suspend fun warm(
        pending: PendingDecompiles,
        threads: Int = resolveBulkDecompileThreads(),
        minClasses: Int = MIN_BULK_CLASSES,
        // The rest are seams for the failure paths: tests start children that crash or hang, and
        // machines that are short of memory.
        availableMemory: () -> Long? = { availableMemoryBytes() },
        mainClass: String = BULK_DECOMPILE_MAIN,
        stallTimeout: Duration = STALL_TIMEOUT,
        onProgress: (Double) -> Unit = {},
    ): Boolean {
        if (threads == 0 || pending.outerClasses.size < minClasses)
            return false

        return gate.withLock {
            // Re-checked under the gate: builds sharing a pool - two vanilla versions, two builds of one
            // fork version - would otherwise both start a child for what the first one just wrote. And a
            // build that waited here on another child sees the memory that child gave back.
            val targets = pending.outerClasses.filter {
                !Files.exists(pending.cacheDir.resolve("${DecompileService.sourceCacheKey(pending.classes, it)}.java"))
            }
            if (targets.size < minClasses)
                return@withLock false

            try {
                val heapMb = HEAP_BASE_MB + threads * HEAP_PER_THREAD_MB
                val available = availableMemory()
                if (available != null && available < heapMb shl 20) {
                    System.err.println(
                        "mcsrc-mcp: ${available shr 20}MB free, under the ${heapMb}MB a bulk decompile wants; " +
                            "decompiling ${targets.size} classes in-process instead",
                    )
                    return@withLock false
                }
                runChild(pending.classes, targets, pending.cacheDir, threads, heapMb, mainClass, stallTimeout, onProgress)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("mcsrc-mcp: bulk decompile failed, decompiling the rest in-process: $e")
                false
            }
        }
    }

    private suspend fun runChild(
        classes: Map<String, ByteArray>,
        targets: List<String>,
        cacheDir: Path,
        threads: Int,
        heapMb: Long,
        mainClass: String,
        stallTimeout: Duration,
        onProgress: (Double) -> Unit,
    ) {
        val java = Path.of(System.getProperty("java.home"), "bin", if (File.separatorChar == '\\') "java.exe" else "java")
        val command = listOf(
            java.toString(),
            "-Xmx${heapMb}m",
            "-cp",
            System.getProperty("java.class.path"),
            mainClass,
            cacheDir.toAbsolutePath().toString(),
            threads.toString(),
        )
        // Not cancellable: a cancellation landing mid-start() would drop the Process with nobody left
        // to kill it, and the child would sit in readRequest until its pipe was collected.
        val process = withContext(NonCancellable) { ProcessBuilder(command).start() }
        try {
            supervise(process, classes, targets, threads, stallTimeout, onProgress)
        } finally {
            // Shutdown, clear_cache or a superseded build: the child would otherwise run on with nobody
            // left to read its output.
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private suspend fun supervise(
        process: Process,
        classes: Map<String, ByteArray>,
        targets: List<String>,
        threads: Int,
        stallTimeout: Duration,
        onProgress: (Double) -> Unit,
    ) {
        val lastProgress = AtomicLong(System.nanoTime())
        val highest = AtomicInteger()
        val failed = AtomicInteger()
        // Cleared on the way out, however that goes: a reader still draining after a cancellation would
        // otherwise report progress for a build that VersionPreparer.clear has already forgotten.
        val reporting = AtomicBoolean(true)
        val stderrTail = ArrayDeque<String>()
        fun daemon(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true; start() }

        // Drained from the start: a child blocked on a full pipe would look exactly like a wedged one.
        val stderr = daemon("bulk-decompile-stderr") {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(stderrTail) {
                    stderrTail.addLast(line)
                    if (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
                }
            }
        }
        val stdout = daemon("bulk-decompile-stdout") {
            process.inputStream.bufferedReader().forEachLine { line ->
                line.removePrefix(FAILED_PREFIX).takeIf { it != line }?.toIntOrNull()?.let(failed::set)
                val count = line.removePrefix(PROGRESS_PREFIX).takeIf { it != line }?.toIntOrNull() ?: return@forEachLine
                lastProgress.set(System.nanoTime())
                // Worker threads print counts as they see them, not necessarily in order.
                if (count > highest.getAndAccumulate(count, ::maxOf) && reporting.get()) {
                    onProgress((count.toDouble() / targets.size).coerceAtMost(1.0))
                }
            }
        }
        daemon("bulk-decompile-request") {
            // Fails only when the child is already gone; its exit code says why.
            runCatching { process.outputStream.use { ChunkedDecompile.writeRequest(it, classes, targets) } }
        }

        val start = System.nanoTime()
        try {
            while (process.isAlive) {
                if (System.nanoTime() - lastProgress.get() > stallTimeout.toNanos()) {
                    process.destroyForcibly()
                    throw IllegalStateException("no progress for ${stallTimeout.toSeconds()}s, killed it")
                }
                delay(250)
            }
            // The readers may still hold the last lines - the final progress count, a failure's stack trace.
            runInterruptible {
                stdout.join(DRAIN_MILLIS)
                stderr.join(DRAIN_MILLIS)
            }
        } finally {
            reporting.set(false)
        }

        if (process.exitValue() != 0) {
            val tail = synchronized(stderrTail) { stderrTail.joinToString("\n") }
            throw IllegalStateException("child exited with ${process.exitValue()}:\n$tail")
        }
        val seconds = (System.nanoTime() - start) / 1_000_000_000.0
        val failures = failed.get().takeIf { it > 0 }?.let { "; $it didn't decompile and are left to the in-process path" } ?: ""
        System.err.println("mcsrc-mcp: bulk-decompiled ${targets.size} classes in ${"%.1f".format(seconds)}s on $threads threads$failures")
    }

    /**
     * Memory a child could take without pushing anything else out, or null when there's no telling.
     *
     * Linux's MemAvailable counts reclaimable page cache, which the MXBean's free figure leaves out -
     * on a box that's been up a while, that's most of its memory. Under a cgroup limit that's not
     * enough: /proc/meminfo describes the host, and a child that outgrows the limit gets the kernel's
     * OOM killer, which may pick the server. So the cgroup's headroom caps it - limit less usage,
     * where usage counts page cache too, which errs towards in-process.
     *
     * On macOS the MXBean reports only truly free pages, leaving out inactive memory the OS would hand
     * over readily, so the child rarely runs there. I haven't found a figure there that's both cheap
     * and honest about what's reclaimable.
     */
    internal fun availableMemoryBytes(root: Path = Path.of("/")): Long? {
        fun readLong(path: String): Long? =
            runCatching { Files.readString(root.resolve(path)).trim().toLongOrNull() }.getOrNull()

        val memAvailable = runCatching {
            Files.readAllLines(root.resolve("proc/meminfo")).firstOrNull { it.startsWith("MemAvailable:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.let { it shl 10 }
        }.getOrNull()
        if (memAvailable != null) {
            // cgroup v2, then v1. v2's "max" doesn't parse and so reads as no limit; v1's unlimited is a
            // near-Long.MAX_VALUE sentinel, which the minOf below sees past on its own.
            val headroom = listOf(
                "sys/fs/cgroup/memory.max" to "sys/fs/cgroup/memory.current",
                "sys/fs/cgroup/memory/memory.limit_in_bytes" to "sys/fs/cgroup/memory/memory.usage_in_bytes",
            ).firstNotNullOfOrNull { (limitFile, usageFile) ->
                val limit = readLong(limitFile) ?: return@firstNotNullOfOrNull null
                readLong(usageFile)?.let { usage -> (limit - usage).coerceAtLeast(0) }
            }
            return if (headroom == null) memAvailable else minOf(memAvailable, headroom)
        }
        return runCatching { (ManagementFactory.getOperatingSystemMXBean() as? SunOperatingSystemMXBean)?.freeMemorySize }.getOrNull()
    }

}
