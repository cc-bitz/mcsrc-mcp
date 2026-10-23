package io.github.ccbitz.mcsrcmcp.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class BulkDecompilerTest {

    private val classes: Map<String, ByteArray> = listOf(
        "net/minecraft/Blast",
        "net/minecraft/Explosion",
        "net/minecraft/Explosion\$InteractionType",
        "net/minecraft/Item",
        "net/minecraft/BlockItem",
        "net/minecraft/Chest",
        "net/minecraft/Barrel",
        "net/minecraft/Storage",
    ).associateWith { name ->
        javaClass.classLoader.getResourceAsStream("$name.class")?.use { it.readBytes() }
            ?: error("fixture class not found on test classpath: $name")
    }

    private val outers = classes.keys.filter { '$' !in it }.sorted()

    private fun perClass(dir: Path): Map<String, String> {
        for (outer in outers) DecompileService.decompileClass(classes, outer, cacheDir = dir)
        return pool(dir)
    }

    private fun pool(dir: Path): Map<String, String> =
        Files.list(dir).use { files -> files.toList() }.associate { it.fileName.toString() to Files.readString(it) }

    @Test
    fun `bulk output is byte-identical to per-class decompiles`(@TempDir dir: Path) {
        val expected = perClass(dir.resolve("per-class"))

        val outcome = ChunkedDecompile.run(classes, outers, dir.resolve("bulk"), threads = 2)

        assertEquals(ChunkedDecompile.Outcome(outers.size, 0), outcome)
        assertEquals(expected, pool(dir.resolve("bulk")))
    }

    @Test
    fun `the per-class decompile imports another class's member class`() {
        // The premise plan() exists for: if this ever stops holding, sharing a run is harmless.
        val blast = DecompileService.decompileClass(classes, "net/minecraft/Blast")

        assertTrue(blast.contains("import net.minecraft.Explosion.InteractionType;"), blast)
    }

    @Test
    fun `plan keeps a class out of the run of the class whose member class it references`() {
        val chunks = ChunkedDecompile.plan(classes, outers, chunkSize = 100)

        val blast = chunks.indexOfFirst { "net/minecraft/Blast" in it }
        val explosion = chunks.indexOfFirst { "net/minecraft/Explosion" in it }
        assertNotEquals(blast, explosion)
        assertEquals(outers.toSet(), chunks.flatten().toSet())
        assertEquals(outers.size, chunks.sumOf { it.size })
    }

    @Test
    fun `plan respects the chunk size`() {
        val chunks = ChunkedDecompile.plan(classes, outers, chunkSize = 2)

        assertTrue(chunks.all { it.size <= 2 })
        assertEquals(outers.toSet(), chunks.flatten().toSet())
    }

    @Test
    fun `a request round-trips through the child protocol`() {
        val out = ByteArrayOutputStream()
        ChunkedDecompile.writeRequest(out, classes, outers)

        val (readClasses, readTargets) = ChunkedDecompile.readRequest(ByteArrayInputStream(out.toByteArray()))

        assertEquals(classes.keys.toList(), readClasses.keys.toList())
        assertTrue(classes.all { (name, bytes) -> bytes.contentEquals(readClasses.getValue(name)) })
        assertEquals(outers, readTargets)
    }

    @Test
    fun `warm decompiles the pending classes in a child JVM`(@TempDir dir: Path) {
        val expected = perClass(dir.resolve("per-class"))
        val progress = CopyOnWriteArrayList<Double>()

        val ran = runBlocking {
            BulkDecompiler.warm(PendingDecompiles(classes, dir.resolve("bulk"), outers), threads = 2, minClasses = 1) { progress.add(it) }
        }

        assertTrue(ran)
        assertEquals(expected, pool(dir.resolve("bulk")))
        assertEquals(1.0, progress.last())
    }

    @Test
    fun `warm leaves a set below the threshold to the in-process path`(@TempDir dir: Path) {
        val ran = runBlocking {
            BulkDecompiler.warm(PendingDecompiles(classes, dir.resolve("bulk"), outers), threads = 2, minClasses = outers.size + 1)
        }

        assertFalse(ran)
        assertFalse(Files.exists(dir.resolve("bulk")))
    }

    @Test
    fun `warm skips classes another build wrote while it waited`(@TempDir dir: Path) {
        // Computed pending, then beaten to it by a build sharing the pool.
        val pending = PendingDecompiles(classes, dir.resolve("pool"), outers)
        perClass(dir.resolve("pool"))

        val ran = runBlocking { BulkDecompiler.warm(pending, threads = 2, minClasses = 1) }

        assertFalse(ran)
    }

    @Test
    fun `warm does nothing when the child is turned off`(@TempDir dir: Path) {
        val ran = runBlocking {
            BulkDecompiler.warm(PendingDecompiles(classes, dir.resolve("bulk"), outers), threads = 0, minClasses = 1)
        }

        assertFalse(ran)
    }

    @Test
    fun `warm leaves it to the in-process path when memory is short, without starting a child`(@TempDir dir: Path) {
        val ran = runBlocking {
            BulkDecompiler.warm(PendingDecompiles(classes, dir.resolve("bulk"), outers), threads = 2, minClasses = 1, availableMemory = { 512L shl 20 })
        }

        assertFalse(ran)
        assertFalse(Files.exists(dir.resolve("bulk")), "no child should have run")
    }

    @Test
    fun `warm goes ahead when free memory can't be told`(@TempDir dir: Path) {
        val ran = runBlocking {
            BulkDecompiler.warm(PendingDecompiles(classes, dir.resolve("bulk"), outers), threads = 2, minClasses = 1, availableMemory = { null })
        }

        assertTrue(ran)
        assertEquals(perClass(dir.resolve("per-class")), pool(dir.resolve("bulk")))
    }

    @Test
    fun `a child that crashes is reported with its stderr and the build carries on`(@TempDir dir: Path) {
        val log = captureStderr {
            val ran = runBlocking {
                BulkDecompiler.warm(
                    PendingDecompiles(classes, dir.resolve("bulk"), outers), threads = 2, minClasses = 1,
                    mainClass = CrashingChildMain::class.java.name,
                )
            }
            assertFalse(ran)
        }

        assertTrue(log.contains("child exited with 3"), log)
        assertTrue(log.contains("OutOfMemoryError: Java heap space (pretend)"), log)
    }

    @Test
    fun `a child that stops reporting progress is killed`(@TempDir dir: Path) {
        val log = captureStderr {
            val ran = runBlocking {
                BulkDecompiler.warm(
                    PendingDecompiles(classes, dir.resolve("bulk"), outers), threads = 2, minClasses = 1,
                    mainClass = HangingChildMain::class.java.name, stallTimeout = Duration.ofSeconds(2),
                )
            }
            assertFalse(ran)
        }

        assertTrue(log.contains("no progress for 2s, killed it"), log)
        assertChildGone(dir.resolve("bulk"))
    }

    @Test
    fun `cancelling the build kills its child`(@TempDir dir: Path) = runBlocking {
        val pool = dir.resolve("bulk")
        val build = launch(Dispatchers.Default) {
            BulkDecompiler.warm(
                PendingDecompiles(classes, pool, outers), threads = 2, minClasses = 1,
                mainClass = HangingChildMain::class.java.name, stallTimeout = Duration.ofMinutes(5),
            )
        }
        withTimeout(30_000) { while (childPid(pool) == null) delay(50) }

        build.cancelAndJoin()

        assertChildGone(pool)
    }

    @Test
    fun `free memory is MemAvailable when there is no cgroup limit`(@TempDir root: Path) {
        meminfo(root, availableKb = 16L shl 20)

        assertEquals(16L shl 30, BulkDecompiler.availableMemoryBytes(root))
    }

    @Test
    fun `free memory is capped by a cgroup v2 limit's headroom`(@TempDir root: Path) {
        meminfo(root, availableKb = 16L shl 20)
        cgroupFile(root, "sys/fs/cgroup/memory.max", "${4L shl 30}")
        cgroupFile(root, "sys/fs/cgroup/memory.current", "${1L shl 30}")

        assertEquals(3L shl 30, BulkDecompiler.availableMemoryBytes(root))
    }

    @Test
    fun `a cgroup v2 limit of max is no limit`(@TempDir root: Path) {
        meminfo(root, availableKb = 16L shl 20)
        cgroupFile(root, "sys/fs/cgroup/memory.max", "max")
        cgroupFile(root, "sys/fs/cgroup/memory.current", "${1L shl 30}")

        assertEquals(16L shl 30, BulkDecompiler.availableMemoryBytes(root))
    }

    @Test
    fun `a cgroup v1 limit caps free memory, and its unlimited sentinel doesn't`(@TempDir root: Path) {
        meminfo(root, availableKb = 16L shl 20)
        cgroupFile(root, "sys/fs/cgroup/memory/memory.limit_in_bytes", "${2L shl 30}")
        cgroupFile(root, "sys/fs/cgroup/memory/memory.usage_in_bytes", "${3L shl 30}")
        assertEquals(0L, BulkDecompiler.availableMemoryBytes(root), "usage past the limit leaves no headroom, not negative")

        cgroupFile(root, "sys/fs/cgroup/memory/memory.limit_in_bytes", "9223372036854771712")
        assertEquals(16L shl 30, BulkDecompiler.availableMemoryBytes(root))
    }

    @Test
    fun `without proc meminfo free memory comes from the OS bean`(@TempDir root: Path) {
        assertNotNull(BulkDecompiler.availableMemoryBytes(root))
    }

    private fun meminfo(root: Path, availableKb: Long) =
        cgroupFile(root, "proc/meminfo", "MemTotal:       ${availableKb * 2} kB\nMemFree:          1024 kB\nMemAvailable:   $availableKb kB\n")

    private fun cgroupFile(root: Path, path: String, content: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun childPid(pool: Path): Long? =
        runCatching { Files.readString(pool.resolve("pid")).trim().toLongOrNull() }.getOrNull()

    // destroyForcibly only asks; give the OS a moment to finish the job.
    private fun assertChildGone(pool: Path) {
        val pid = childPid(pool) ?: error("the child never recorded its pid")
        val deadline = System.nanoTime() + 10_000_000_000L
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "child $pid is still running")
    }

    private fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return captured.toString(Charsets.UTF_8)
    }

    @Test
    fun `decompile threads default to half the processors, capped`() {
        assertEquals(1, resolveBulkDecompileThreads(emptyMap(), processors = 1))
        assertEquals(2, resolveBulkDecompileThreads(emptyMap(), processors = 4))
        assertEquals(8, resolveBulkDecompileThreads(emptyMap(), processors = 32))
    }

    @Test
    fun `decompile threads come from the environment, zero turning the child off`() {
        assertEquals(12, resolveBulkDecompileThreads(mapOf("MCSRC_MCP_DECOMPILE_THREADS" to "12"), processors = 32))
        assertEquals(0, resolveBulkDecompileThreads(mapOf("MCSRC_MCP_DECOMPILE_THREADS" to "0"), processors = 32))
        assertEquals(8, resolveBulkDecompileThreads(mapOf("MCSRC_MCP_DECOMPILE_THREADS" to "lots"), processors = 32))
        assertEquals(8, resolveBulkDecompileThreads(mapOf("MCSRC_MCP_DECOMPILE_THREADS" to "-1"), processors = 32))
    }

}
