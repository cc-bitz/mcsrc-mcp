package io.github.ccbitz.mcsrcmcp.server

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * The child JVM [BulkDecompiler] starts: reads the jar and its targets from stdin
 * ([ChunkedDecompile.writeRequest]), decompiles them into the pool named by the first argument on as
 * many threads as the second, and reports progress on stdout.
 */
fun main(args: Array<String>) {
    // stdout is the progress channel; anything else printing to it - Vineflower, a library - would
    // garble it, so they all get stderr, which the parent keeps a tail of.
    val progress = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)
    System.setOut(System.err)

    // Exits explicitly either way: a failure on the main thread would otherwise leave the JVM up for as
    // long as a still-running Vineflower run's non-daemon threads, with only the parent's watchdog to
    // end it.
    try {
        val cacheDir = Path.of(args[0])
        val threads = args[1].toInt()
        val (classes, targets) = ChunkedDecompile.readRequest(System.`in`)
        val outcome = ChunkedDecompile.run(classes, targets, cacheDir, threads, onProcessed = ChunkedDecompile.progressPrinter(progress))
        progress.println("$FAILED_PREFIX${outcome.failed}")
        progress.println("$PROGRESS_PREFIX${targets.size}")
        progress.flush()
    } catch (t: Throwable) {
        t.printStackTrace()
        exitProcess(1)
    }
    exitProcess(0)
}
