package io.github.ccbitz.mcsrcmcp.server

import org.jetbrains.java.decompiler.api.Decompiler
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import org.objectweb.asm.ClassReader
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.Manifest

// Classes per Vineflower run. ContextUnit.save holds every class of a run processed at once before
// writing any, so this bounds the heap; 250 measured no full GCs at 2.5GB on 26.3, and the per-run
// setup (the whole-jar library context, ~60ms) is noise against a run's worth of decompiling.
private const val CHUNK_SIZE = 250

internal const val PROGRESS_PREFIX = "processed "
internal const val FAILED_PREFIX = "failed "
private const val PROGRESS_INTERVAL_NANOS = 250_000_000L

/**
 * Decompiles a whole set of classes in one Vineflower run per [CHUNK_SIZE] classes, instead of one
 * run per class. A per-class run spends ~70% of its time building and tearing down Vineflower's
 * context for the entire jar; here that happens once per chunk, and a cold 26.3 index build drops
 * from 88s to ~18s.
 *
 * Output is byte-identical to [DecompileService.decompileClass], which the pools rely on: an entry
 * may come from either path and is read by both. That takes [plan]: Vineflower names another class's
 * member class `Outer.Inner` when Outer is an input of the same run, and imports `Inner` directly when
 * Outer is only a library - which it always is for a per-class run. So two classes share a run only
 * when neither references a member class of the other.
 *
 * That is the one difference measurements turned up - every class of vanilla 26.3, 1.21.1 and 1.12.2
 * and of Paper 1.21.11's whole jar came out identical - but it isn't the only place Vineflower
 * consults which classes are inputs: a call whose receiver it inferred as Object gets a cast to the
 * declaring class only when that class is an input. Nothing in any of those hit it. If one ever does, the cost is a class whose index entry shows the
 * cast until a find_declaration re-decompiles it with tokens and writes the per-class text over it.
 */
internal object ChunkedDecompile {

    /** Classes written to the pool, and classes whose output was a [failed decompile][isFailedDecompile] and wasn't. */
    data class Outcome(val written: Int, val failed: Int)

    /**
     * Decompiles [targets] (outer classes of [classes]) into [cacheDir] under their
     * [DecompileService.sourceCacheKey], reporting the running count of processed classes to
     * [onProcessed] from Vineflower's worker threads.
     *
     * [threads] are split across two runs going at once: every run ends waiting on its slowest class
     * and then writes alone, and a second run fills the cores meanwhile. 26.3 measured 18s that way
     * against 20s for one run on all eight.
     */
    fun run(
        classes: Map<String, ByteArray>,
        targets: List<String>,
        cacheDir: Path,
        threads: Int,
        chunkSize: Int = CHUNK_SIZE,
        onProcessed: (Int) -> Unit = {},
    ): Outcome {
        Files.createDirectories(cacheDir)
        val runners = if (threads >= 2) 2 else 1
        val threadsPerRun = (threads + runners - 1) / runners
        val nests = classes.keys.groupBy { it.substringBefore('$') }
        val library = InMemoryContextSource("classpath", classes)
        val processed = AtomicInteger()
        val written = AtomicInteger()
        val failed = AtomicInteger()
        // Vineflower hands a run's sources to the saver one by one on the thread that called decompile(),
        // only after every class of the run is written; hashing and writing them there kept the cores
        // idle for ~2.5s of a 26.3 build.
        val writes = Executors.newSingleThreadExecutor()

        val logger = object : IFernflowerLogger() {

            override fun endProcessingClass() = onProcessed(processed.incrementAndGet())

            override fun writeMessage(message: String, severity: Severity) {}

            override fun writeMessage(message: String, severity: Severity, t: Throwable?) {
                if (severity == Severity.ERROR) System.err.println("vineflower: $message ${t ?: ""}")
            }

        }
        val saver = object : IResultSaver {

            override fun saveClassFile(path: String, qualifiedName: String, entryName: String, content: String, mapping: IntArray?) {
                if (isFailedDecompile(content)) {
                    failed.incrementAndGet()
                    return
                }
                writes.execute {
                    // A write that fails leaves a miss, which the in-process path fills like any other.
                    try {
                        DecompileService.writeAtomic(cacheDir.resolve("${DecompileService.sourceCacheKey(classes, qualifiedName)}.java"), content)
                        written.incrementAndGet()
                    } catch (e: Exception) {
                        System.err.println("couldn't write $qualifiedName: $e")
                    }
                }
            }

            override fun saveFolder(path: String) {}
            override fun copyFile(source: String, path: String, entryName: String) {}
            override fun createArchive(path: String, archiveName: String, manifest: Manifest?) {}
            override fun saveDirEntry(path: String, archiveName: String, entryName: String) {}
            override fun copyEntry(source: String, path: String, archiveName: String, entry: String) {}
            override fun saveClassEntry(path: String, archiveName: String, qualifiedName: String, entryName: String, content: String) {}
            override fun closeArchive(path: String, archiveName: String) {}

        }

        // Builder options must stay exactly runDecompile's - thread-count only sizes Vineflower's pool,
        // which was verified not to change a byte of output.
        fun decompile(chunk: List<String>) {
            val inputs = chunk.flatMap { nests.getValue(it) }.associateWith { classes.getValue(it) }
            Decompiler.builder()
                .inputs(InMemoryContextSource("target", inputs))
                .libraries(library)
                .output(saver)
                .option(IFernflowerPreferences.THREADS, threadsPerRun.toString())
                .logger(logger)
                .build()
                .decompile()
        }

        val runPool = Executors.newFixedThreadPool(runners)
        try {
            plan(classes, targets, chunkSize).map { chunk -> runPool.submit { decompile(chunk) } }.forEach { it.get() }
        } finally {
            // Runs first: one still going after a sibling failed would otherwise hand its sources to a
            // writer that's already shut.
            runPool.shutdownNow()
            runPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            writes.shutdown()
            writes.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        }
        return Outcome(written.get(), failed.get())
    }

    /**
     * Packs [targets] into runs of at most [chunkSize] where no two classes reference each other's
     * member classes (see [ChunkedDecompile]), first fit in the order given.
     *
     * The references are over-approximated from every type name in the bytecode, the InnerClasses
     * attribute included: a spurious edge only costs a slightly fuller plan. 26.3's 7,301 classes
     * pack into 31 runs of 250 or fewer in ~0.6s.
     */
    fun plan(classes: Map<String, ByteArray>, targets: List<String>, chunkSize: Int): List<List<String>> {
        val nests = classes.keys.groupBy { it.substringBefore('$') }
        val targetSet = targets.toHashSet()
        val conflicts = HashMap<String, MutableSet<String>>()
        for (outer in targets) {
            val remapper = object : Remapper() {

                override fun map(internalName: String): String {
                    val owner = internalName.substringBefore('$', "")
                    if (owner.isNotEmpty() && owner != outer && owner in targetSet) {
                        conflicts.getOrPut(outer) { HashSet() }.add(owner)
                        conflicts.getOrPut(owner) { HashSet() }.add(outer)
                    }
                    return internalName
                }

            }
            // ClassNode rather than an empty ClassVisitor underneath: ClassRemapper only walks a method's
            // body when the visitor below hands it a MethodVisitor, and an empty one hands back null.
            for (name in nests.getValue(outer)) {
                ClassReader(classes.getValue(name)).accept(ClassRemapper(ClassNode(), remapper), ClassReader.SKIP_FRAMES)
            }
        }

        val chunks = ArrayList<MutableSet<String>>()
        for (outer in targets) {
            val mine = conflicts[outer].orEmpty()
            val chunk = chunks.firstOrNull { it.size < chunkSize && mine.none(it::contains) }
                ?: LinkedHashSet<String>().also(chunks::add)
            chunk.add(outer)
        }
        return chunks.map { it.toList() }
    }

    /** The parent's half of the child protocol: the jar, then the targets. */
    fun writeRequest(out: OutputStream, classes: Map<String, ByteArray>, targets: List<String>) {
        val data = DataOutputStream(BufferedOutputStream(out, 1 shl 16))
        data.writeInt(classes.size)
        for ((name, bytes) in classes) {
            data.writeUTF(name)
            data.writeInt(bytes.size)
            data.write(bytes)
        }
        data.writeInt(targets.size)
        for (target in targets) data.writeUTF(target)
        data.flush()
    }

    fun readRequest(input: InputStream): Pair<Map<String, ByteArray>, List<String>> {
        val data = DataInputStream(input.buffered(1 shl 16))
        val classes = LinkedHashMap<String, ByteArray>()
        repeat(data.readInt()) {
            val name = data.readUTF()
            classes[name] = ByteArray(data.readInt()).also(data::readFully)
        }
        val targets = List(data.readInt()) { data.readUTF() }
        return classes to targets
    }

    /**
     * Prints `processed <n>` to [out] at most every [PROGRESS_INTERVAL_NANOS], for the parent's
     * watchdog. Counts can arrive out of order across threads; the parent keeps the highest.
     */
    fun progressPrinter(out: PrintStream): (Int) -> Unit {
        val last = AtomicLong(System.nanoTime() - PROGRESS_INTERVAL_NANOS)
        return { count ->
            val now = System.nanoTime()
            val previous = last.get()
            if (now - previous >= PROGRESS_INTERVAL_NANOS && last.compareAndSet(previous, now)) {
                out.println("$PROGRESS_PREFIX$count")
                // A parent that died leaves nobody to read this; stop rather than decompile into the void.
                if (out.checkError()) Runtime.getRuntime().halt(2)
            }
        }
    }

}
