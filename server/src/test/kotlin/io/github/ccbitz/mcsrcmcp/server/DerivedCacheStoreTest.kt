package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.ClassData
import io.github.ccbitz.mcsrcmcp.core.Entry
import io.github.ccbitz.mcsrcmcp.core.IndexData
import io.github.ccbitz.mcsrcmcp.core.MemberData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DerivedCacheStoreTest {
    private fun sampleIndexData(): IndexData {
        val classes = mapOf(
            "net/minecraft/wolf/Beast" to ClassData("net/minecraft/wolf/Beast", "java/lang/Object", emptyList(), 0x21),
            "net/minecraft/wolf/Hound" to ClassData("net/minecraft/wolf/Hound", "net/minecraft/wolf/Beast", listOf("java/lang/Runnable"), 0x21),
        )
        val members = mapOf(
            "net/minecraft/wolf/Beast" to MemberData(
                "net/minecraft/wolf/Beast",
                setOf(Entry.Method("net/minecraft/wolf/Beast", "makeNoise", "()V")),
                emptySet(),
            ),
        )
        return IndexData(classes, members)
    }

    @Test
    fun `directoryFor is stable for the same inputs and differs when any input changes`(@TempDir tempDir: Path) {
        val a = DerivedCacheStore.directoryFor(tempDir, "1.21.4", "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222")
        val b = DerivedCacheStore.directoryFor(tempDir, "1.21.4", "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222")
        assertEquals(a, b)

        val differentMappings = DerivedCacheStore.directoryFor(tempDir, "1.21.4", "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", "cccc3333cccc3333cccc3333cccc3333cccc3333")
        assertNotEquals(a, differentMappings)

        val noMappings = DerivedCacheStore.directoryFor(tempDir, "1.21.4", "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", null)
        assertNotEquals(a, noMappings)
    }

    @Test
    fun `save then load round-trips remapped classes, index data, and references exactly`(@TempDir tempDir: Path) {
        val derivedDir = DerivedCacheStore.directoryFor(tempDir, "1.21.4", "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", null)
        val remappedClasses = mapOf(
            "net/minecraft/wolf/Beast" to byteArrayOf(1, 2, 3),
            "net/minecraft/wolf/Hound" to byteArrayOf(4, 5, 6, 7),
        )
        val indexData = sampleIndexData()
        val references = mapOf(
            "net/minecraft/wolf/Beast:makeNoise:()V" to listOf("m:net/minecraft/wolf/Hound:woof:()V"),
        )

        DerivedCacheStore.save(derivedDir, remappedClasses, indexData, references)
        val loaded = DerivedCacheStore.load(derivedDir)

        assertNotNull(loaded)
        assertArrayEquals(byteArrayOf(1, 2, 3), loaded!!.remappedClasses["net/minecraft/wolf/Beast"])
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), loaded.remappedClasses["net/minecraft/wolf/Hound"])
        assertEquals(2, loaded.remappedClasses.size)

        val loadedBeast = loaded.indexData.classes()["net/minecraft/wolf/Beast"]
        assertNotNull(loadedBeast)
        assertEquals("java/lang/Object", loadedBeast!!.superName())
        val loadedHound = loaded.indexData.classes()["net/minecraft/wolf/Hound"]
        assertNotNull(loadedHound)
        assertEquals(listOf("java/lang/Runnable"), loadedHound!!.interfaces())

        val loadedBeastMembers = loaded.indexData.members()["net/minecraft/wolf/Beast"]
        assertNotNull(loadedBeastMembers)
        assertTrue(loadedBeastMembers!!.methods().contains(Entry.Method("net/minecraft/wolf/Beast", "makeNoise", "()V")))

        assertEquals(listOf("m:net/minecraft/wolf/Hound:woof:()V"), loaded.references["net/minecraft/wolf/Beast:makeNoise:()V"])
    }

    @Test
    fun `load bumps the index file's last-modified time, marking the cache as just-used`(@TempDir tempDir: Path) {
        val derivedDir = DerivedCacheStore.directoryFor(tempDir, "1.21.4", "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", null)
        DerivedCacheStore.save(derivedDir, mapOf("a" to byteArrayOf(1)), IndexData.empty(), emptyMap())

        val indexFile = derivedDir.resolve("index.bin")
        val oldTime = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(60)))
        java.nio.file.Files.setLastModifiedTime(indexFile, oldTime)

        DerivedCacheStore.load(derivedDir)

        val newTime = java.nio.file.Files.getLastModifiedTime(indexFile)
        assertTrue(newTime.toInstant().isAfter(oldTime.toInstant().plus(java.time.Duration.ofDays(1))))
    }

    @Test
    fun `load returns null when the directory is empty or missing`(@TempDir tempDir: Path) {
        val derivedDir = tempDir.resolve("does-not-exist")
        assertNull(DerivedCacheStore.load(derivedDir))
    }

    @Test
    fun `load returns null rather than throwing when the index file is corrupt`(@TempDir tempDir: Path) {
        val derivedDir = DerivedCacheStore.directoryFor(tempDir, "1.21.4", "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", null)
        DerivedCacheStore.save(derivedDir, mapOf("a" to byteArrayOf(1)), IndexData.empty(), emptyMap())

        // Valid writeUTF of a string that isn't the magic - fails the first read rather than
        // deep in the counts, proving the corruption is caught either way.
        java.nio.file.Files.writeString(derivedDir.resolve("index.bin"), "mangled")

        assertNull(DerivedCacheStore.load(derivedDir))
    }
}
