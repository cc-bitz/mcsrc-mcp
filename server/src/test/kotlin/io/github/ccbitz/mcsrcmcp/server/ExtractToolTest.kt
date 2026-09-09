package io.github.ccbitz.mcsrcmcp.server

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExtractToolTest {
    @TempDir
    lateinit var tempDir: Path

    private val assets = mapOf(
        "assets/minecraft/textures/block/stone.png" to AssetInfo("assets/minecraft/textures/block/stone.png", 4, false),
        "assets/minecraft/lang/en_us.json" to AssetInfo("assets/minecraft/lang/en_us.json", 28, true),
    )

    // Deliberately not valid UTF-8 text: binary assets are extract's whole reason to exist, and a
    // round trip through String would corrupt them silently.
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val readBytes: (String) -> ByteArray? = { path ->
        when (path) {
            "assets/minecraft/textures/block/stone.png" -> pngBytes
            "assets/minecraft/lang/en_us.json" -> """{"item.minecraft.allium":"Allium"}""".toByteArray()
            else -> null
        }
    }

    @Test
    fun `writes a binary asset's bytes to the destination`() {
        val destination = tempDir.resolve("stone.png")
        val written = extractToolLogic(assets, readBytes, "assets/minecraft/textures/block/stone.png", destination)
        assertEquals(pngBytes.size, written)
        assertArrayEquals(pngBytes, Files.readAllBytes(destination))
    }

    @Test
    fun `creates missing parent directories`() {
        val destination = tempDir.resolve("out/nested/dir/stone.png")
        extractToolLogic(assets, readBytes, "assets/minecraft/textures/block/stone.png", destination)
        assertArrayEquals(pngBytes, Files.readAllBytes(destination))
    }

    @Test
    fun `overwrites an existing file at the destination`() {
        val destination = tempDir.resolve("stone.png")
        Files.write(destination, byteArrayOf(1, 2, 3))
        extractToolLogic(assets, readBytes, "assets/minecraft/textures/block/stone.png", destination)
        assertArrayEquals(pngBytes, Files.readAllBytes(destination))
    }

    @Test
    fun `unknown path throws AssetNotFoundException`() {
        assertThrows(AssetNotFoundException::class.java) {
            extractToolLogic(assets, readBytes, "assets/minecraft/does/not/exist.png", tempDir.resolve("out.png"))
        }
    }

    // Class entries are not catalogued as assets (the class tools address those), so a .class path
    // must fail rather than fall through to some other lookup.
    @Test
    fun `class file path throws AssetNotFoundException`() {
        assertThrows(AssetNotFoundException::class.java) {
            extractToolLogic(assets, readBytes, "net/minecraft/world/level/Level.class", tempDir.resolve("Level.class"))
        }
    }

    // The listing and the bytes come from two reads of the jar, so an entry the listing knows about
    // can come back missing - it must not write an empty file.
    @Test
    fun `asset the reader cannot find throws AssetNotFoundException and writes nothing`() {
        val destination = tempDir.resolve("out.png")
        assertThrows(AssetNotFoundException::class.java) {
            extractToolLogic(assets, { null }, "assets/minecraft/textures/block/stone.png", destination)
        }
        assertTrue(Files.notExists(destination))
    }
}
