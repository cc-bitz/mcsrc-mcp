package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DevBundleResolutionTest {
    private val paperUrl = Variants.byId(Variants.PAPER)!!.devBundleRepository!!

    private val oldScheme = listOf(
        "1.21.5-no-moonrise-SNAPSHOT",
        "1.21.9-rc1-R0.1-SNAPSHOT",
        "1.21.11-R0.1-SNAPSHOT",
    )
    private val newScheme = listOf(
        "26.1.2.build.53-stable",
        "26.3.build.3-alpha",
        "26.3.build.44-alpha",
        "26.3.build.49-alpha",
        "26.3-rc-3.build.1-alpha",
    )
    private val available = oldScheme + newScheme

    @Test
    fun `old scheme matches exactly, so a prefix of another version never leaks in`() {
        assertTrue(devBundleVersionMatches("1.21.11-R0.1-SNAPSHOT", "1.21.11"))
        // "1.21.1" must not match "1.21.11-..." - the exact-equality form guards this.
        assertFalse(devBundleVersionMatches("1.21.11-R0.1-SNAPSHOT", "1.21.1"))
        // The special no-moonrise bundle is not the release bundle for 1.21.5.
        assertFalse(devBundleVersionMatches("1.21.5-no-moonrise-SNAPSHOT", "1.21.5"))
    }

    @Test
    fun `new scheme matches on version prefix followed by a build boundary`() {
        assertTrue(devBundleVersionMatches("26.3.build.49-alpha", "26.3"))
        assertTrue(devBundleVersionMatches("26.3-rc-3.build.1-alpha", "26.3-rc-3"))
        // "26.3" must not claim "26.3-something-else.build.N" that isn't its own release train.
        assertFalse(devBundleVersionMatches("26.3.build.49-alpha", "26.4"))
        assertFalse(devBundleVersionMatches("26.3.build.49-alpha", "2.63"))
    }

    @Test
    fun `the minecraft version of a bundle comes out by both schemes, null for one-offs`() {
        assertEquals("26.3", devBundleMinecraftVersion("26.3.build.49-alpha"))
        assertEquals("26.3-rc-3", devBundleMinecraftVersion("26.3-rc-3.build.1-alpha"))
        assertEquals("1.21.11", devBundleMinecraftVersion("1.21.11-R0.1-SNAPSHOT"))
        assertNull(devBundleMinecraftVersion("1.21.5-no-moonrise-SNAPSHOT"))
        assertNull(devBundleMinecraftVersion("26.1.2.local-SNAPSHOT"))
    }

    @Test
    fun `no spec picks the highest build number`() {
        assertEquals("26.3.build.49-alpha", selectDevBundleBuild(Variants.PAPER, available, "26.3", null))
        assertEquals("1.21.11-R0.1-SNAPSHOT", selectDevBundleBuild(Variants.PAPER, available, "1.21.11", null))
    }

    @Test
    fun `an unpublished version has no builds, and the error lists what does exist`() {
        val e = assertThrows<DevBundleNotFoundException> { selectDevBundleBuild(Variants.PAPER, available, "1.20.4", null) }
        assertTrue(e.message!!.contains("newest versions it has builds for"), "got: ${e.message}")
        assertTrue(e.message!!.contains("26.3"), "the newest version should be listed, got: ${e.message}")
        // Purpur's junk local build names no Minecraft version, so purpur's list still resolves.
        val e2 = assertThrows<DevBundleNotFoundException> {
            selectDevBundleBuild(Variants.PURPUR, available + "26.1.2.local-SNAPSHOT", "1.20.4", null)
        }
        assertFalse(e2.message!!.contains("26.1.2.local"))
    }

    @Test
    fun `an exact spec wins even when it also substring-matches others`() {
        assertEquals("26.3.build.44-alpha", selectDevBundleBuild(Variants.PAPER, available, "26.3", "26.3.build.44-alpha"))
    }

    @Test
    fun `a numeric spec is a build number, matched exactly`() {
        // As a substring "4" also hits builds 14, 40 through 49 - it must pin build 4 alone.
        val builds = (40..49).map { "26.3.build.$it-alpha" } + "26.3.build.14-beta" + "26.3.build.4-stable"
        assertEquals("26.3.build.4-stable", selectDevBundleBuild(Variants.PAPER, builds, "26.3", "4"))
        assertEquals("26.3.build.14-beta", selectDevBundleBuild(Variants.PAPER, builds, "26.3", "14"))
        // A number nobody has is not-found, not ambiguous.
        assertThrows<DevBundleNotFoundException> { selectDevBundleBuild(Variants.PAPER, builds, "26.3", "7") }
    }

    @Test
    fun `a substring spec pins one build`() {
        assertEquals("26.3.build.49-alpha", selectDevBundleBuild(Variants.PAPER, available, "26.3", "build.49-alpha"))
        assertEquals("26.3.build.49-alpha", selectDevBundleBuild(Variants.PAPER, available, "26.3", "49-alpha"))
    }

    @Test
    fun `an ambiguous spec names its candidates`() {
        val e = assertThrows<DevBundleAmbiguousException> { selectDevBundleBuild(Variants.PAPER, available, "26.3", "alpha") }
        assertTrue(e.candidates.size > 1)
        assertTrue(e.candidates.all { it.startsWith("26.3") })
    }

    @Test
    fun `metadata parsing pulls versions and the snapshot zip name`() {
        val metadata = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>io.papermc.paper</groupId>
              <artifactId>dev-bundle</artifactId>
              <version>1.21.11-SNAPSHOT</version>
              <versioning>
                <snapshotVersions>
                  <snapshotVersion>
                    <extension>pom</extension>
                    <value>1.21.11-R0.1-20260511.115010-91</value>
                  </snapshotVersion>
                  <snapshotVersion>
                    <extension>zip</extension>
                    <value>1.21.11-R0.1-20260511.115010-91</value>
                  </snapshotVersion>
                </snapshotVersions>
              </versioning>
            </metadata>
        """.trimIndent()

        assertTrue(parseDevBundleVersions(metadata).contains("1.21.11-SNAPSHOT"))
        assertEquals("1.21.11-R0.1-20260511.115010-91", parseSnapshotZipValue(metadata))
    }

    @Test
    fun `snapshot versions take the timestamped zip, releases take the direct one`() {
        assertEquals(
            "$paperUrl/1.21.11-R0.1-SNAPSHOT/dev-bundle-1.21.11-R0.1-20260511.115010-91.zip",
            devBundleZipUrl(paperUrl, "1.21.11-R0.1-SNAPSHOT", "1.21.11-R0.1-20260511.115010-91"),
        )
        assertEquals(
            "$paperUrl/26.3.build.49-alpha/dev-bundle-26.3.build.49-alpha.zip",
            devBundleZipUrl(paperUrl, "26.3.build.49-alpha", null),
        )
    }
}
