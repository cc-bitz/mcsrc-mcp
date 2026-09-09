package io.github.ccbitz.mcsrcmcp.server

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

// The subprocess itself is exercised for real against 26.2 (datagen runs in ~15s wall clock);
// what's unit-testable here is the JDK discovery it depends on and the completion-stamp contract
// that separates a cached tree from a partial one.
class ReportGeneratorTest {
    @Test
    fun `reads a release file's major version, modern and legacy schemes`(@TempDir tempDir: Path) {
        fun home(version: String): Path = tempDir.resolve(version.replace(Regex("[^a-zA-Z0-9]"), "_")).also {
            Files.createDirectories(it)
            Files.writeString(it.resolve("release"), "JAVA_VERSION=\"$version\"\nIMPLEMENTOR=\"test\"\n")
        }

        assertEquals(25, readJavaMajorFromRelease(home("25.0.2")))
        assertEquals(21, readJavaMajorFromRelease(home("21.0.5")))
        assertEquals(8, readJavaMajorFromRelease(home("1.8.0_392")))
        assertNull(readJavaMajorFromRelease(tempDir.resolve("no-such-home")))
    }

    @Test
    fun `pickJavaCommand takes the newest JDK that satisfies the requirement and skips the rest`(@TempDir tempDir: Path) {
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java"
        fun home(name: String, version: String): Path {
            val h = tempDir.resolve(name)
            Files.createDirectories(h.resolve("bin"))
            Files.writeString(h.resolve("release"), "JAVA_VERSION=\"$version\"\n")
            Files.createFile(h.resolve(exe))
            return h
        }

        val jdk21 = home("jdk21", "21.0.5")
        val jdk25 = home("jdk25", "25.0.2")
        val jdk8 = home("jdk8", "1.8.0_392")

        assertEquals(jdk25.resolve(exe), pickJavaCommand(25, listOf(jdk8, jdk21, jdk25)))
        assertEquals(jdk25.resolve(exe), pickJavaCommand(21, listOf(jdk8, jdk21, jdk25)))
        assertNull(pickJavaCommand(26, listOf(jdk8, jdk21, jdk25)))
    }

    @Test
    fun `a home without a java executable is not picked`(@TempDir tempDir: Path) {
        val emptyHome = tempDir.resolve("jdk-empty").also { Files.createDirectories(it) }
        Files.writeString(emptyHome.resolve("release"), "JAVA_VERSION=\"25.0.2\"\n")
        assertNull(pickJavaCommand(25, listOf(emptyHome)))
    }

    @Test
    fun `the completion stamp is what makes a reports directory count as cached`(@TempDir tempDir: Path) {
        val reportsDir = tempDir.resolve("reports")
        Files.createDirectories(reportsDir)
        Files.writeString(reportsDir.resolve("registries.json"), "{}")
        // Files present but no stamp: a crashed run's partial tree, must not be served.
        assertFalse(ReportGenerator.isComplete(reportsDir))

        Files.writeString(reportsDir.resolve(ReportGenerator.COMPLETE_STAMP), "26.2")
        assertTrue(ReportGenerator.isComplete(reportsDir))
    }
}
