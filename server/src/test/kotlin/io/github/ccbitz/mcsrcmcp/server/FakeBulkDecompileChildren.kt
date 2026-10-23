package io.github.ccbitz.mcsrcmcp.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

// Stand-ins for BulkDecompileMain that go wrong the ways a real child can, started by BulkDecompiler
// exactly as it starts the real one: same classpath, same arguments (the pool directory first).

/** Dies at once with a non-zero exit and a last word on stderr, like a child that ran out of heap. */
internal object CrashingChildMain {

    @JvmStatic
    fun main(args: Array<String>) {
        System.err.println("java.lang.OutOfMemoryError: Java heap space (pretend)")
        exitProcess(3)
    }

}

/** Records its pid in the pool directory, then never reports progress, like a child wedged on a class. */
internal object HangingChildMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Files.createDirectories(Path.of(args[0]))
        Files.writeString(dir.resolve("pid"), ProcessHandle.current().pid().toString())
        Thread.sleep(Long.MAX_VALUE)
    }

}
