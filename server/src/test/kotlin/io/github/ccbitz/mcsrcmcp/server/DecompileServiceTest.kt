package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DecompileServiceTest {
    private fun loadFixture(internalName: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("$internalName.class")
            ?: error("fixture class not found on test classpath: $internalName")
        return stream.readBytes()
    }

    private val itemAndBlockItem = mapOf(
        "net/minecraft/Item" to loadFixture("net/minecraft/Item"),
        "net/minecraft/BlockItem" to loadFixture("net/minecraft/BlockItem"),
    )

    @Test
    fun `decompiles a class to readable Java source`() {
        val source = DecompileService.decompileClass(itemAndBlockItem, "net/minecraft/Item")

        assertTrue(source.contains("class Item"), "expected 'class Item' in output, got:\n$source")
        assertTrue(source.contains("getMaxStackSize"), "expected the getMaxStackSize method in output, got:\n$source")
    }

    @Test
    fun `resolves supertype context from other classes in the same jar`() {
        val source = DecompileService.decompileClass(itemAndBlockItem, "net/minecraft/BlockItem")

        assertTrue(source.contains("class BlockItem"), "expected 'class BlockItem' in output, got:\n$source")
        assertTrue(source.contains("extends Item"), "expected 'extends Item' in output (proves Item was available as classpath context), got:\n$source")
        assertTrue(source.contains("placeBlock"), "expected the placeBlock method in output, got:\n$source")
    }

    @Test
    fun `requesting an inner class decompiles its outer class, inlined`() {
        val classes = mapOf(
            "net/minecraft/Explosion" to loadFixture("net/minecraft/Explosion"),
            "net/minecraft/Explosion\$InteractionType" to loadFixture("net/minecraft/Explosion\$InteractionType"),
        )

        val source = DecompileService.decompileClass(classes, "net/minecraft/Explosion\$InteractionType")

        assertTrue(source.contains("class Explosion"), "expected outer class Explosion in output, got:\n$source")
        assertTrue(source.contains("InteractionType"), "expected inner enum InteractionType inlined in output, got:\n$source")
    }

    @Test
    fun `unknown class throws`() {
        assertThrows(ClassNotFoundInIndexException::class.java) {
            DecompileService.decompileClass(emptyMap(), "net/minecraft/DoesNotExist")
        }
    }

    @Test
    fun `writes decompiled source to the cache directory when one is provided`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val source = DecompileService.decompileClass(itemAndBlockItem, "net/minecraft/Item", cacheDir = tempDir)

        val cacheFile = tempDir.resolve("net.minecraft.Item.java")
        assertTrue(Files.exists(cacheFile), "expected a cache file to be written")
        assertEquals(source, Files.readString(cacheFile))
    }

    @Test
    fun `a second call with the same cacheDir returns the cached content without re-decompiling`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        DecompileService.decompileClass(itemAndBlockItem, "net/minecraft/Item", cacheDir = tempDir)

        // Overwrite the cache file with a sentinel that could never be real Vineflower output -
        // if the second call actually re-decompiled instead of reading the cache, we'd get real
        // Java source back, not this sentinel.
        val cacheFile = tempDir.resolve("net.minecraft.Item.java")
        Files.writeString(cacheFile, "SENTINEL_CACHED_CONTENT")

        val result = DecompileService.decompileClass(itemAndBlockItem, "net/minecraft/Item", cacheDir = tempDir)

        assertEquals("SENTINEL_CACHED_CONTENT", result)
    }

    @Test
    fun `no cacheDir means no caching, same as before`() {
        val source = DecompileService.decompileClass(itemAndBlockItem, "net/minecraft/Item")
        assertTrue(source.contains("class Item"))
    }

    @Test
    fun `collects source tokens carrying the resolved owner and descriptor of each symbol`() {
        val result = DecompileService.decompileWithTokens(itemAndBlockItem, "net/minecraft/Item")

        val declaration = result.tokens.single { it.kind == TokenKind.METHOD && it.member?.name == "getMaxStackSize" }
        assertTrue(declaration.declaration, "the only getMaxStackSize token in Item is its own declaration")
        assertEquals("net/minecraft/Item", declaration.className)
        assertEquals("()I", declaration.member?.descriptor)
        assertEquals(
            "getMaxStackSize",
            result.source.substring(declaration.start, declaration.start + declaration.length),
            "the token range must slice the symbol out of the source it was collected with",
        )
    }

    @Test
    fun `caches tokens beside the source and serves both from the cache on the next call`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        DecompileService.decompileWithTokens(itemAndBlockItem, "net/minecraft/Item", cacheDir = tempDir)

        assertTrue(Files.exists(tempDir.resolve("net.minecraft.Item.tokens.json")), "expected a token cache file beside the source")

        // Same sentinel trick as the source-only cache test: real Vineflower output could never
        // be this, so getting it back proves the second call never re-decompiled.
        Files.writeString(tempDir.resolve("net.minecraft.Item.java"), "SENTINEL_CACHED_CONTENT")
        val result = DecompileService.decompileWithTokens(itemAndBlockItem, "net/minecraft/Item", cacheDir = tempDir)

        assertEquals("SENTINEL_CACHED_CONTENT", result.source)
        assertTrue(result.tokens.isNotEmpty(), "tokens must come back from the cache too, not just the source")
    }

    @Test
    fun `a source cached without tokens is decompiled again rather than returned tokenless`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        DecompileService.decompileClass(itemAndBlockItem, "net/minecraft/Item", cacheDir = tempDir)

        val result = DecompileService.decompileWithTokens(itemAndBlockItem, "net/minecraft/Item", cacheDir = tempDir)

        assertTrue(result.tokens.isNotEmpty(), "a .java left by decompileClass says nothing about tokens, so this must re-decompile")
    }
}
