package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VariantsTest {
    @Test
    fun `null or blank means vanilla`() {
        assertEquals(Variants.VANILLA, Variants.parse(null).variant.id)
        assertEquals(Variants.VANILLA, Variants.parse("").variant.id)
        assertEquals(Variants.VANILLA, Variants.parse("  ").variant.id)
        assertNull(Variants.parse(null).build)
    }

    @Test
    fun `a bare id parses with no build pin`() {
        val spec = Variants.parse("paper")
        assertEquals(Variants.PAPER, spec.variant.id)
        assertNull(spec.build)
    }

    @Test
    fun `an id with a build pin splits on the first slash`() {
        val spec = Variants.parse("paper/26.3.build.49-alpha")
        assertEquals(Variants.PAPER, spec.variant.id)
        assertEquals("26.3.build.49-alpha", spec.build)
    }

    @Test
    fun `an unknown id lists what is available`() {
        val e = assertThrows<UnknownVariantException> { Variants.parse("neither") }
        assertTrue(e.available.contains(Variants.VANILLA))
        assertTrue(e.available.contains(Variants.PAPER))
    }

    @Test
    fun `vanilla takes no build pin`() {
        assertThrows<VariantSpecException> { Variants.parse("vanilla/something") }
    }

    @Test
    fun `every registered variant parses by its own id`() {
        for (variant in Variants.ALL) {
            assertEquals(variant.id, Variants.parse(variant.id).variant.id)
        }
    }

    @Test
    fun `the paperweight forks are registered with dev bundle repositories`() {
        for (id in listOf(Variants.PAPER, Variants.FOLIA, Variants.PURPUR)) {
            val variant = Variants.byId(id)
            assertNotNull(variant, "$id must be registered")
            assertNotNull(variant!!.devBundleRepository, "$id needs a dev bundle repository")
            assertEquals(id, Variants.parse("$id/build.7").variant.id, "$id takes a build pin")
        }
        // Vanilla is the odd one out.
        assertNull(Variants.byId(Variants.VANILLA)!!.devBundleRepository)
    }

    @Test
    fun `vanilla workspace id is the version id and variant ids are prefixed`() {
        assertEquals("1.21.4", WorkspaceRequest(Variants.VANILLA, null, "1.21.4").workspaceId)
        assertEquals("paper/26.3.build.49-alpha", WorkspaceRequest(Variants.PAPER, "26.3.build.49-alpha", "paper/26.3.build.49-alpha").workspaceId)
    }
}
