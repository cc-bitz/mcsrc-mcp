package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SearchIndexTest {
    @TempDir
    lateinit var tempDir: Path

    // The index is a file now, not an object graph, so every fixture is written through the real
    // writer and read back through the real reader - the round trip is exercised by every test
    // here rather than by one dedicated round-trip case.
    private fun fixtureIndex(): SearchIndex =
        SearchIndexStore.writer(tempDir, "1.99-test").use { writer ->
            writer.add(
                SearchEntryKind.SOURCE,
                "net.minecraft.Foo",
                "public class Foo {\n    public static final String TAG = \"minecraft:foo\";\n}",
            )
            writer.add(SearchEntryKind.SOURCE, "net.minecraft.Bar", "public class Bar {\n    public void run() {}\n}")
            writer.add(SearchEntryKind.ASSET, "assets/minecraft/items/foo.json", "{\n  \"foo\": \"bar\"\n}")
            writer.finish()
        }

    @Test
    fun `substring search returns matching source lines`() {
        val matches = fixtureIndex().search("minecraft:foo", useRegex = false, limit = 100, includeSource = true, includeAssets = false).matches

        assertEquals(1, matches.size)
        assertEquals("net.minecraft.Foo", matches[0].path)
        assertEquals(2, matches[0].line)
        assertTrue(matches[0].text.contains("TAG"))
    }

    @Test
    fun `each match reports the total decompiled line count of its class`() {
        val matches = fixtureIndex().search("minecraft:foo", useRegex = false, limit = 100, includeSource = true, includeAssets = false).matches

        assertEquals(3, matches[0].nLines, "net.minecraft.Foo's fixture entry has 3 lines")
    }

    @Test
    fun `regex search matches case insensitive`() {
        val matches = fixtureIndex().search("RUN\\(\\)", useRegex = true, limit = 100, includeSource = true, includeAssets = false).matches

        assertEquals(1, matches.size)
        assertEquals("net.minecraft.Bar", matches[0].path)
    }

    @Test
    fun `substring search matches case insensitive`() {
        val matches = fixtureIndex().search("PUBLIC CLASS BAR", useRegex = false, limit = 100, includeSource = true, includeAssets = false).matches

        assertEquals(1, matches.size)
        assertEquals("net.minecraft.Bar", matches[0].path)
    }

    @Test
    fun `search can target assets only`() {
        val matches = fixtureIndex().search("\"foo\"", useRegex = false, limit = 100, includeSource = false, includeAssets = true).matches

        assertEquals(1, matches.size)
        assertEquals("assets/minecraft/items/foo.json", matches[0].path)
    }

    @Test
    fun `search respects limit and reports truncation`() {
        val matches = fixtureIndex().search("public", useRegex = false, limit = 1, includeSource = true, includeAssets = false).matches

        assertEquals(1, matches.size)
    }

    @Test
    fun `search scoped to a class only matches within that class`() {
        val matches = fixtureIndex().search(
            "public", useRegex = false, limit = 100, includeSource = true, includeAssets = false,
            paths = setOf("net.minecraft.Bar"),
        ).matches

        assertTrue(matches.isNotEmpty())
        assertTrue(matches.all { it.path == "net.minecraft.Bar" })
    }

    @Test
    fun `search scoped to a list of classes matches within any of them`() {
        val matches = fixtureIndex().search(
            "public", useRegex = false, limit = 100, includeSource = true, includeAssets = false,
            paths = setOf("net.minecraft.Foo", "net.minecraft.Bar"),
        ).matches

        assertEquals(2, matches.map { it.path }.toSet().size)
    }

    @Test
    fun `search scoped to a class no results exist in returns nothing`() {
        val matches = fixtureIndex().search(
            "public", useRegex = false, limit = 100, includeSource = true, includeAssets = false,
            paths = setOf("net.minecraft.DoesNotExist"),
        ).matches

        assertTrue(matches.isEmpty())
    }

    // A scoped search seeks to each entry via the toc instead of scanning, so line numbers and
    // lengths have to come out identical to the unscoped path.
    @Test
    fun `scoped and unscoped searches agree on line numbers and lengths`() {
        val index = fixtureIndex()
        val unscoped = index.search("TAG", useRegex = false, limit = 100, includeSource = true, includeAssets = false).matches
        val scoped = index.search(
            "TAG", useRegex = false, limit = 100, includeSource = true, includeAssets = false,
            paths = setOf("net.minecraft.Foo"),
        ).matches

        assertEquals(unscoped, scoped)
    }

    @Test
    fun `scoped search can target an asset`() {
        val matches = fixtureIndex().search(
            "bar", useRegex = false, limit = 100, includeSource = false, includeAssets = true,
            paths = setOf("assets/minecraft/items/foo.json"),
        ).matches

        assertEquals(1, matches.size)
        assertEquals(2, matches[0].line)
    }

    @Test
    fun `searchCodeToolLogic scopes to the given classes`() {
        val result = searchCodeToolLogic(fixtureIndex(), "public", classNames = listOf("net.minecraft.Foo"))

        assertTrue(result.results.isNotEmpty())
        assertTrue(result.results.all { it.path == "net.minecraft.Foo" })
    }

    @Test
    fun `searchCodeToolLogic reports truncation when limit is reached`() {
        val result = searchCodeToolLogic(fixtureIndex(), "public", limit = 1)

        assertEquals(1, result.shown)
        assertTrue(result.truncated)
    }

    @Test
    fun `searchAssetsToolLogic returns expected asset match`() {
        val result = searchAssetsToolLogic(fixtureIndex(), "foo", limit = 100)

        assertEquals(1, result.shown)
        assertFalse(result.truncated)
    }

    // Hits used to be a flat list repeating the path and nLines on every match.
    @Test
    fun `results group every hit in a class under one path stated once`() {
        val result = searchCodeToolLogic(fixtureIndex(), "public")

        val foo = result.results.single { it.path == "net.minecraft.Foo" }
        assertEquals(3, foo.nLines)
        assertEquals(setOf("1", "2"), foo.lines.keys)
        assertTrue(foo.lines.getValue("2").contains("TAG"))
    }

    // A scan that finishes has already counted everything, so the exact count is free; one that
    // stops at the limit must not claim to be exact.
    @Test
    fun `a completed search reports an exact count without being asked`() {
        val result = searchCodeToolLogic(fixtureIndex(), "public")

        assertEquals("4", result.matches)
        assertFalse(result.truncated)
    }

    @Test
    fun `a search that stops at the limit reports an open-ended count`() {
        val result = searchCodeToolLogic(fixtureIndex(), "public", limit = 2)

        assertEquals("2+", result.matches)
        assertTrue(result.truncated)
    }

    @Test
    fun `exact_count counts every hit past the limit while still returning only limit of them`() {
        val result = searchCodeToolLogic(fixtureIndex(), "public", limit = 2, exactCount = true)

        assertEquals(2, result.shown)
        assertEquals("4", result.matches)
    }

    @Test
    fun `exclude drops matching lines in the same pass`() {
        val result = searchCodeToolLogic(fixtureIndex(), "public", exclude = "class")

        assertEquals("2", result.matches)
        assertTrue(result.results.flatMap { it.lines.values }.none { it.contains("class") })
    }

    // exclude is a regex even when the query is literal. Tying the two made an alternation - the
    // form a caller actually reaches for - silently match nothing.
    @Test
    fun `exclude is a regex even when the query is literal`() {
        val result = searchCodeToolLogic(fixtureIndex(), "public", useRegex = false, exclude = "Bar|TAG")

        assertEquals("1", result.matches)
        assertEquals(listOf("net.minecraft.Foo"), result.results.map { it.path })
    }

    @Test
    fun `an unparseable exclude is reported, not silently ignored`() {
        assertThrows(java.util.regex.PatternSyntaxException::class.java) {
            searchCodeToolLogic(fixtureIndex(), "public", exclude = "get(")
        }
    }

    // The noise a caller wants gone is usually a whole class, not a line - excluding only line
    // text would leave every hit inside net.minecraft.Bar in place.
    @Test
    fun `exclude also drops whole entries by path`() {
        val result = searchCodeToolLogic(fixtureIndex(), "public", exclude = "Bar")

        assertEquals("2", result.matches)
        assertTrue(result.results.none { it.path == "net.minecraft.Bar" })
    }

    @Test
    fun `an empty exclude is ignored rather than dropping everything`() {
        val result = searchCodeToolLogic(fixtureIndex(), "public", exclude = "")

        assertEquals("4", result.matches)
    }

    @Test
    fun `searchAssetsToolLogic supports exclude and exact_count too`() {
        val result = searchAssetsToolLogic(fixtureIndex(), "\"", limit = 1, exclude = "bar", exactCount = true)

        assertEquals("0", result.matches)
        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `SearchIndexStore reloads a written index`() {
        fixtureIndex()

        val loaded = SearchIndexStore.load(tempDir)

        assertNotNull(loaded)
        assertEquals("1.99-test", loaded!!.versionId)
        val matches = loaded.search("minecraft:foo", useRegex = false, limit = 10, includeSource = true, includeAssets = false).matches
        assertEquals(1, matches.size)
    }

    @Test
    fun `SearchIndexStore load returns null when no index exists`() {
        assertNull(SearchIndexStore.load(tempDir))
    }

    // finish() publishes by atomic move, so a writer abandoned mid-build must leave nothing that
    // load() would accept as a complete index.
    @Test
    fun `an abandoned writer publishes nothing`() {
        SearchIndexStore.writer(tempDir, "1.99-test").use { writer ->
            writer.add(SearchEntryKind.SOURCE, "net.minecraft.Foo", "public class Foo {}")
        }

        assertNull(SearchIndexStore.load(tempDir))
    }

    @Test
    fun `lines containing the entry header marker are not mistaken for headers`() {
        val index = SearchIndexStore.writer(tempDir, "1.99-test").use { writer ->
            writer.add(SearchEntryKind.SOURCE, "net.minecraft.Tricky", "S\t2\tnet.minecraft.Fake\nreal line\n")
            writer.add(SearchEntryKind.SOURCE, "net.minecraft.After", "found me")
            writer.finish()
        }

        val matches = index.search("found me", useRegex = false, limit = 10, includeSource = true, includeAssets = false).matches

        assertEquals(1, matches.size)
        assertEquals("net.minecraft.After", matches[0].path)
    }

    @Test
    fun `SearchIndexBuilder decompiles classes and indexes asset text`() = runTest {
        val classes = mapOf(
            "net/minecraft/Item" to loadClassBytes("net/minecraft/Item"),
            "net/minecraft/BlockItem" to loadClassBytes("net/minecraft/BlockItem"),
        )
        val assetPath = "assets/minecraft/items/foo.json"
        val workspace = VersionWorkspace(
            versionId = "1.99-test",
            indexData = IndexData.empty(),
            remapper = null,
            remappedClasses = classes,
            referenceIndexer = io.github.ccbitz.mcsrcmcp.core.Indexer(),
            assets = mapOf(assetPath to AssetInfo(assetPath, 13, isText = true)),
            assetSource = mapAssetSource(assetPath to "{\"foo\":\"bar\"}"),
            cacheDir = tempDir,
        )

        val progress = mutableListOf<Int>()
        val builder = SearchIndexBuilder(workspace, tempDir)
        val index = builder.build { progress.add(it) }

        assertEquals("1.99-test", index.versionId)
        val itemMatches = index.search("class Item", useRegex = false, limit = 10, includeSource = true, includeAssets = false).matches
        assertTrue(itemMatches.any { it.path == "net.minecraft.Item" }, "expected Item source entry")
        val blockItemMatches = index.search("BlockItem", useRegex = false, limit = 10, includeSource = true, includeAssets = false).matches
        assertTrue(blockItemMatches.any { it.path == "net.minecraft.BlockItem" }, "expected BlockItem source entry")
        val assetMatches = index.search("bar", useRegex = false, limit = 10, includeSource = false, includeAssets = true).matches
        assertTrue(assetMatches.any { it.path == assetPath })

        assertTrue(progress.isNotEmpty())
        assertEquals(100, progress.last())

        assertTrue(Files.exists(tempDir.resolve("search-index-v2.txt")), "expected search index to be persisted")
        assertTrue(Files.exists(tempDir.resolve("search-index-v2.toc")), "expected search index toc to be persisted")
    }

    private fun loadClassBytes(internalName: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("$internalName.class")
            ?: error("fixture class not found on test classpath: $internalName")
        return stream.readBytes()
    }
}

internal fun mapAssetSource(vararg texts: Pair<String, String>): AssetSource {
    val byPath = texts.toMap()
    return object : AssetSource {
        override fun readText(path: String): String? = byPath[path]

        override fun readBytes(path: String): ByteArray? = byPath[path]?.toByteArray()
    }
}
