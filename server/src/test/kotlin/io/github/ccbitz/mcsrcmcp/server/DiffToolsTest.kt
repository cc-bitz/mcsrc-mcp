package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class DiffToolsTest {
    private fun loadFixture(internalName: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("$internalName.class")
            ?: error("fixture class not found on test classpath: $internalName")
        return stream.readBytes()
    }

    private fun workspace(id: String, classes: Map<String, ByteArray>): VersionWorkspace {
        return VersionWorkspace(
            versionId = id,
            indexData = IndexData.empty(),
            remapper = null,
            remappedClasses = classes,
            referenceIndexer = io.github.ccbitz.mcsrcmcp.core.Indexer(),
            assets = emptyMap(),
            assetSource = EmptyAssetSource,
            cacheDir = Paths.get("/tmp/$id"),
        )
    }

    @Test
    fun `diffVersions reports no changes when classes are identical`() {
        val classes = mapOf(
            "net/minecraft/Item" to loadFixture("net/minecraft/Item"),
            "net/minecraft/BlockItem" to loadFixture("net/minecraft/BlockItem"),
        )
        val a = workspace("a", classes)
        val b = workspace("b", classes)

        val result = diffVersionsToolLogic(a, b)

        assertEquals(0, result.addedCount)
        assertEquals(0, result.deletedCount)
        assertEquals(0, result.modifiedCount)
    }

    @Test
    fun `diffVersions detects added deleted and modified classes`() {
        val itemBytes = loadFixture("net/minecraft/Item")
        val blockItemBytes = loadFixture("net/minecraft/BlockItem")
        val modifiedItemBytes = itemBytes.copyOf().apply { this[10] = (this[10] + 1).toByte() }

        val a = workspace("a", mapOf(
            "net/minecraft/Item" to itemBytes,
            "net/minecraft/Dog" to loadFixture("net/minecraft/Dog"),
        ))
        val b = workspace("b", mapOf(
            "net/minecraft/Item" to modifiedItemBytes,
            "net/minecraft/BlockItem" to blockItemBytes,
        ))

        val result = diffVersionsToolLogic(a, b)

        assertEquals(listOf("net.minecraft.BlockItem"), result.added)
        assertEquals(listOf("net.minecraft.Dog"), result.deleted)
        assertEquals(listOf("net.minecraft.Item"), result.modified)
        assertEquals(1, result.addedCount)
        assertEquals(1, result.deletedCount)
        assertEquals(1, result.modifiedCount)
    }

    @Test
    fun `diffVersions filters by package prefix`() {
        val a = workspace("a", mapOf(
            "net/minecraft/Item" to loadFixture("net/minecraft/Item"),
        ))
        val b = workspace("b", mapOf(
            "net/minecraft/Item" to loadFixture("net/minecraft/Item"),
            "net/minecraft/BlockItem" to loadFixture("net/minecraft/BlockItem"),
        ))

        val result = diffVersionsToolLogic(a, b, packagePrefix = "com/mojang")

        assertEquals(0, result.addedCount)
        assertEquals(0, result.deletedCount)
        assertEquals(0, result.modifiedCount)
    }

    @Test
    fun `diffClass returns empty diff when sources are identical`() {
        val classes = mapOf(
            "net/minecraft/Item" to loadFixture("net/minecraft/Item"),
        )
        val a = workspace("a", classes)
        val b = workspace("b", classes)

        val result = diffClassToolLogic(a, b, "net.minecraft.Item")

        assertEquals("net.minecraft.Item", result.className)
        assertEquals("", result.diff)
    }

    @Test
    fun `unifiedDiff formats simple line changes correctly`() {
        val diff = unifiedDiff(
            oldLines = listOf("alpha", "beta", "gamma"),
            newLines = listOf("alpha", "BETA", "gamma", "delta"),
            oldLabel = "a/Old.java",
            newLabel = "b/New.java",
            context = 1,
        )

        assertTrue(diff.startsWith("--- a/Old.java"), "got:\n$diff")
        assertTrue(diff.contains("+++ b/New.java"), "got:\n$diff")
        assertTrue(diff.contains("-beta"), "got:\n$diff")
        assertTrue(diff.contains("+BETA"), "got:\n$diff")
        assertTrue(diff.contains("+delta"), "got:\n$diff")
    }

    @Test
    fun `unifiedDiff does not throw when the last line of the file changed`() {
        val oldLines = (1..50).map { "line$it" }
        val newLines = oldLines.dropLast(1) + "line50-changed"

        val diff = unifiedDiff(
            oldLines = oldLines,
            newLines = newLines,
            oldLabel = "a/Old.java",
            newLabel = "b/New.java",
            context = 2,
        )

        assertTrue(diff.contains("-line50"), "got:\n$diff")
        assertTrue(diff.contains("+line50-changed"), "got:\n$diff")
    }

    @Test
    fun `unifiedDiff handles an insert whose snake runs to the end of both files`() {
        // Regression case for a Myers-diff bug: the search only checked the pre-snake (x, y)
        // against (n, m), so it missed the true termination point whenever the last elements of
        // old and new matched, then landed on a later, overshot state whose indices exceeded the
        // line arrays.
        val diff = unifiedDiff(
            oldLines = listOf("l1", "l2"),
            newLines = listOf("x", "l1", "l2"),
            oldLabel = "a",
            newLabel = "b",
            context = 1,
        )

        assertTrue(diff.contains("+x"), "got:\n$diff")
    }

    @Test
    fun `fuzz unifiedDiff for out-of-bounds crashes`() {
        val rng = kotlin.random.Random(42)
        repeat(2000) { trial ->
            val old = (1..rng.nextInt(1, 20)).map { "l$it" }
            val new = old.toMutableList()
            repeat(rng.nextInt(1, 6)) {
                when (rng.nextInt(3)) {
                    0 -> if (new.isNotEmpty()) new.removeAt(rng.nextInt(new.size))
                    1 -> new.add(rng.nextInt(new.size + 1), "x${rng.nextInt(1000)}")
                    else -> if (new.isNotEmpty()) new[rng.nextInt(new.size)] = "x${rng.nextInt(1000)}"
                }
            }
            try {
                unifiedDiff(old, new, "a", "b", context = rng.nextInt(0, 4))
            } catch (e: Exception) {
                fail("trial=$trial old=$old new=$new threw $e")
            }
        }
    }
}
