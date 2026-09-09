package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GetAssetToolTest {
    private val assets = mapOf(
        "assets/minecraft/lang/en_us.json" to AssetInfo("assets/minecraft/lang/en_us.json", 42, true),
        "assets/minecraft/textures/block/stone.png" to AssetInfo("assets/minecraft/textures/block/stone.png", 2048, false),
    )
    private val assetText = mapOf(
        "assets/minecraft/lang/en_us.json" to """{"item.minecraft.allium":"Allium"}""",
    )
    private val readText: (String) -> String? = { assetText[it] }

    @Test
    fun `returns text content for a text asset`() {
        val result = getAssetToolLogic(assets, readText, "assets/minecraft/lang/en_us.json")
        assertEquals("assets/minecraft/lang/en_us.json", result.path)
        assertEquals("""{"item.minecraft.allium":"Allium"}""", result.content)
        assertEquals(1, result.totalLines)
        assertFalse(result.truncated)
    }

    @Test
    fun `unknown path throws AssetNotFoundException`() {
        assertThrows(AssetNotFoundException::class.java) {
            getAssetToolLogic(assets, readText, "assets/minecraft/does/not/exist.json")
        }
    }

    @Test
    fun `known but binary asset throws AssetNotTextException`() {
        assertThrows(AssetNotTextException::class.java) {
            getAssetToolLogic(assets, readText, "assets/minecraft/textures/block/stone.png")
        }
    }

    // The listing and the text now come from two reads of the jar rather than one eager decode, so
    // a text entry that the listing knows about can come back missing. It must not surface as an
    // empty asset.
    @Test
    fun `text asset the reader cannot find throws AssetNotFoundException`() {
        assertThrows(AssetNotFoundException::class.java) {
            getAssetToolLogic(assets, { null }, "assets/minecraft/lang/en_us.json")
        }
    }
}
