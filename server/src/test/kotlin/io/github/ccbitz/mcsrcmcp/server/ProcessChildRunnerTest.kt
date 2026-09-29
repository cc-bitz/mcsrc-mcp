package io.github.ccbitz.mcsrcmcp.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * A child that records its pid in the file named by its argument, then does nothing for a minute -
 * long enough to still be running when it's cancelled.
 */
object Sleeper {

    @JvmStatic
    fun main(args: Array<String>) {
        Files.writeString(Path.of(args[0]), ProcessHandle.current().pid().toString())
        Thread.sleep(60_000)
    }

}

class ProcessChildRunnerTest {

    // The whole-jar decompile is a 4GB JVM: a cancelled build (shutdown, clear_cache) must not leave it
    // running with nobody waiting for it.
    @Test
    fun `cancelling the wait kills the child`(@TempDir dir: Path) = runBlocking<Unit> {
        val pidFile = dir.resolve("pid")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(java, "-cp", System.getProperty("java.class.path"), Sleeper::class.java.name, pidFile.toString())

        val job = launch(Dispatchers.IO) { ProcessChildRunner.run(command, dir, Duration.ofMinutes(5)) }
        repeat(100) { if (!Files.exists(pidFile) || Files.size(pidFile) == 0L) delay(100) }
        val child = ProcessHandle.of(Files.readString(pidFile).toLong()).orElseThrow()
        assertTrue(child.isAlive)

        job.cancelAndJoin()
        repeat(50) { if (child.isAlive) delay(100) }
        assertFalse(child.isAlive, "the child outlived the cancelled wait")
    }

}
