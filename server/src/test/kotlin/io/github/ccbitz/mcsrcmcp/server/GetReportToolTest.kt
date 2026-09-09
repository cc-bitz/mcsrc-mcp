package io.github.ccbitz.mcsrcmcp.server

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

// A fixture in the shape the generator actually produced for 26.2: top-level report JSONs, a
// namespace directory of biome parameters, and the deep per-item components tree.
class GetReportToolTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var reportsDir: Path

    @BeforeEach
    fun setUp() {
        reportsDir = tempDir.resolve("reports")
        Files.createDirectories(reportsDir.resolve("minecraft/components/item"))
        Files.createDirectories(reportsDir.resolve("biome_parameters/minecraft"))
        Files.writeString(reportsDir.resolve("registries.json"), "{\n  \"minecraft:block\": {}\n}\n")
        Files.writeString(reportsDir.resolve("blocks.json"), "[]\n")
        Files.writeString(reportsDir.resolve("minecraft/components/item/diamond_sword.json"), "{\n  \"max_stack_size\": 1\n}\n")
        Files.writeString(reportsDir.resolve("biome_parameters/minecraft/plains.json"), "{}")
    }

    @Test
    fun `root listing shows files and directories one level deep`() {
        val listing = listReportToolLogic(reportsDir)
        assertEquals(
            listOf("biome_parameters/", "blocks.json", "minecraft/", "registries.json"),
            listing.entries.map { if (it.isDirectory) it.path + "/" else it.path },
        )
        // Files carry their size; directories' is meaningless.
        val byPath = listing.entries.associateBy { it.path }
        assertEquals(Files.size(reportsDir.resolve("registries.json")).toInt(), byPath.getValue("registries.json").sizeBytes)
        assertEquals(0, byPath.getValue("minecraft").sizeBytes)
        assertFalse(listing.truncated)
    }

    @Test
    fun `directory listing under a prefix shows full paths from the reports root`() {
        val listing = listReportToolLogic(reportsDir, "minecraft/components/item")
        assertEquals(listOf("minecraft/components/item/diamond_sword.json"), listing.entries.map { it.path })
        assertFalse(listing.entries.single().isDirectory)
    }

    @Test
    fun `listing truncates past the limit`() {
        val listing = listReportToolLogic(reportsDir, "", limit = 2)
        assertEquals(2, listing.entries.size)
        assertTrue(listing.truncated)
    }

    @Test
    fun `serves a report with or without the json suffix`() {
        val withSuffix = getReportToolLogic(reportsDir, "registries.json")
        val withoutSuffix = getReportToolLogic(reportsDir, "registries")
        assertEquals(withSuffix.content, withoutSuffix.content)
        // The trailing newline yields a final empty line, same as get_asset on a jar asset.
        assertEquals("{\n  \"minecraft:block\": {}\n}\n", withoutSuffix.content)
        assertEquals(4, withoutSuffix.totalLines)
        assertEquals("registries.json", withoutSuffix.path)
    }

    @Test
    fun `serves a deeply nested report and pages it`() {
        val page = getReportToolLogic(reportsDir, "minecraft/components/item/diamond_sword", startLine = 1, maxLines = 1)
        assertEquals("{", page.content)
        assertTrue(page.truncated)
        assertEquals(1, page.startLine)
    }

    @Test
    fun `unknown report throws with the top-level names as a hint`() {
        val e = assertThrows(ReportNotFoundException::class.java) {
            getReportToolLogic(reportsDir, "recipes")
        }
        assertEquals("no report named 'recipes' (available: biome_parameters, blocks.json, minecraft, registries.json)", e.message)
    }

    @Test
    fun `a traversal attempt resolves to nothing rather than escaping the reports root`() {
        assertThrows(ReportNotFoundException::class.java) {
            getReportToolLogic(reportsDir, "../../secret")
        }
        assertThrows(ReportNotFoundException::class.java) {
            listReportToolLogic(reportsDir, "../../../etc")
        }
    }

    @Test
    fun `dotfiles such as the completion stamp are never listed or served`() {
        Files.writeString(reportsDir.resolve(ReportGenerator.COMPLETE_STAMP), "26.2")
        val listing = listReportToolLogic(reportsDir)
        assertTrue(listing.entries.none { it.path.startsWith(".") })
        assertThrows(ReportNotFoundException::class.java) {
            getReportToolLogic(reportsDir, ".complete")
        }
    }

    @Test
    fun `a missing reports directory lists and serves nothing`() {
        val empty = tempDir.resolve("no-reports")
        assertEquals(0, listReportToolLogic(empty).entries.size)
        assertThrows(ReportNotFoundException::class.java) {
            getReportToolLogic(empty, "registries")
        }
    }

    // The pre-generation catalog: read from the class index, mapped to the file names the
    // generators actually write.
    @Test
    fun `expected reports derive from the jar's data-generator classes`() {
        val classNames = setOf(
            "net/minecraft/data/Main",
            "net/minecraft/data/info/BiomeParametersDumpReport",
            "net/minecraft/data/info/BlockListReport",
            "net/minecraft/data/info/CommandsReport",
            "net/minecraft/data/info/DatapackStructureReport",
            "net/minecraft/data/info/PacketReport",
            "net/minecraft/data/info/RegistryComponentsReport",
            "net/minecraft/data/info/RegistryDumpReport",
            "net/minecraft/data/info/package-info",
            "net/minecraft/server/jsonrpc/dataprovider/JsonRpcApiSchema",
            "net/minecraft/world/level/block/Blocks",
        )
        val listing = expectedReportEntries(classNames)
        assertEquals("expected", listing.source)
        assertEquals(
            listOf(
                FileTreeEntry("biome_parameters", isDirectory = true),
                FileTreeEntry("blocks.json", isDirectory = false),
                FileTreeEntry("commands.json", isDirectory = false),
                FileTreeEntry("datapack.json", isDirectory = false),
                FileTreeEntry("json-rpc-api-schema.json", isDirectory = false),
                FileTreeEntry("minecraft/components", isDirectory = true),
                FileTreeEntry("packets.json", isDirectory = false),
                FileTreeEntry("registries.json", isDirectory = false),
            ).sortedBy { it.path },
            listing.entries,
        )
    }

    @Test
    fun `an unrecognized report class lists under its class name rather than disappearing`() {
        val listing = expectedReportEntries(setOf("net/minecraft/data/info/SomeNewReport"))
        assertEquals(listOf("SomeNewReport"), listing.entries.map { it.path })
        assertFalse(listing.entries.single().isDirectory)
    }

    @Test
    fun `a version with no data generator expects no reports`() {
        assertTrue(expectedReportEntries(setOf("net/minecraft/world/level/block/Blocks")).entries.isEmpty())
    }
}
