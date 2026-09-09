package io.github.ccbitz.mcsrcmcp.cache

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VersionManifestTest {
    // Trimmed fixture in the real shape of piston-meta.mojang.com's version_manifest_v2.json
    private val manifestFixture = """
        {
          "versions": [
            {
              "id": "1.21.4",
              "type": "release",
              "url": "https://example.invalid/1.21.4.json",
              "time": "2024-12-03T10:00:00+00:00",
              "releaseTime": "2024-12-03T10:00:00+00:00",
              "sha1": "aaaa000000000000000000000000000000aaaa"
            },
            {
              "id": "24w50a",
              "type": "snapshot",
              "url": "https://example.invalid/24w50a.json",
              "time": "2024-12-11T10:00:00+00:00",
              "releaseTime": "2024-12-11T10:00:00+00:00",
              "sha1": "bbbb000000000000000000000000000000bbbb"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses version list entries`() {
        val manifest = parseVersionManifest(manifestFixture)
        assertEquals(2, manifest.versions.size)
        assertEquals("1.21.4", manifest.versions[0].id)
        assertEquals("release", manifest.versions[0].type)
        assertEquals("24w50a", manifest.versions[1].id)
        assertEquals("snapshot", manifest.versions[1].type)
    }

    private val versionDetailWithMappingsFixture = """
        {
          "downloads": {
            "client": {
              "url": "https://example.invalid/client.jar",
              "sha1": "cccc000000000000000000000000000000cccc",
              "size": 12345
            },
            "client_mappings": {
              "url": "https://example.invalid/client.txt",
              "sha1": "dddd000000000000000000000000000000dddd",
              "size": 6789
            }
          }
        }
    """.trimIndent()

    private val versionDetailWithoutMappingsFixture = """
        {
          "downloads": {
            "client": {
              "url": "https://example.invalid/client.jar",
              "sha1": "cccc000000000000000000000000000000cccc",
              "size": 12345
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parses version detail with client_mappings present`() {
        val detail = parseVersionDetail(versionDetailWithMappingsFixture)
        assertEquals("https://example.invalid/client.jar", detail.downloads.client.url)
        assertNotNull(detail.downloads.clientMappings)
        assertEquals("https://example.invalid/client.txt", detail.downloads.clientMappings!!.url)
    }

    @Test
    fun `parses version detail with client_mappings absent (unobfuscated version)`() {
        val detail = parseVersionDetail(versionDetailWithoutMappingsFixture)
        assertNull(detail.downloads.clientMappings)
    }

    // Trimmed to the fields datagen cares about, in the real shape of a version.json: a plain
    // cross-platform library, an osx-only one, a natives-only entry with no main artifact, and a
    // 32-bit-windows-allowing one that must not apply on a 64-bit JVM.
    private val librariesFixture = """
        {
          "downloads": {
            "client": {
              "url": "https://example.invalid/client.jar",
              "sha1": "cccc000000000000000000000000000000cccc",
              "size": 12345
            }
          },
          "javaVersion": {
            "component": "java-runtime-delta",
            "majorVersion": 25
          },
          "libraries": [
            {
              "name": "joptsimple:jopt-simple:5.0.4",
              "downloads": {
                "artifact": {
                  "path": "joptsimple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar",
                  "sha1": "eeee000000000000000000000000000000eeee",
                  "url": "https://example.invalid/jopt-simple.jar",
                  "size": 62446
                }
              }
            },
            {
              "name": "ca.weblite:java-objc-bridge:1.1",
              "rules": [{ "action": "allow", "os": { "name": "osx" } }],
              "downloads": {
                "artifact": {
                  "path": "ca/weblite/java-objc-bridge/1.1/java-objc-bridge-1.1.jar",
                  "sha1": "ffff000000000000000000000000000000ffff",
                  "url": "https://example.invalid/java-objc-bridge.jar",
                  "size": 40503
                }
              }
            },
            {
              "name": "org.lwjgl:lwjgl:3.3.3",
              "downloads": {
                "classifiers": {
                  "natives-windows": {
                    "path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar",
                    "sha1": "aaaa111100000000000000000000000000aaaa",
                    "url": "https://example.invalid/lwjgl-natives.jar",
                    "size": 145156
                  }
                }
              }
            },
            {
              "name": "org.lwjgl:lwjgl:3.3.3:natives-windows-32bit",
              "rules": [{ "action": "allow", "os": { "name": "windows", "arch": "x86" } }],
              "downloads": {
                "artifact": {
                  "path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows-32bit.jar",
                  "sha1": "bbbb111100000000000000000000000000bbbb",
                  "url": "https://example.invalid/lwjgl-32.jar",
                  "size": 123
                }
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses libraries and javaVersion`() {
        val detail = parseVersionDetail(librariesFixture)
        assertEquals(4, detail.libraries.size)
        assertEquals(25, detail.javaVersion?.majorVersion)
        assertEquals("joptsimple:jopt-simple:5.0.4", detail.libraries[0].name)
        assertEquals("eeee000000000000000000000000000000eeee", detail.libraries[0].downloads?.artifact?.sha1)
    }

    @Test
    fun `classpath libraries filter by os rules and skip natives-only entries`() {
        val detail = parseVersionDetail(librariesFixture)
        val onWindows64 = detail.classpathLibraryArtifacts("Windows 11", "amd64")
        // jopt-simple yes; objc-bridge is osx-only; lwjgl's main entry ships no artifact (only
        // natives classifiers); the 32-bit-natives entry's x86 arch rule fails on amd64.
        assertEquals(listOf("eeee000000000000000000000000000000eeee"), onWindows64.map { it.sha1 })

        val onOsx = detail.classpathLibraryArtifacts("Mac OS X", "x86_64")
        assertEquals(
            listOf("eeee000000000000000000000000000000eeee", "ffff000000000000000000000000000000ffff"),
            onOsx.map { it.sha1 },
        )
    }

    @Test
    fun `last matching rule wins and rules default to disallow`() {
        val detail = parseVersionDetail(
            """
            {
              "downloads": { "client": { "url": "u", "sha1": "s", "size": 1 } },
              "libraries": [
                {
                  "name": "a",
                  "rules": [
                    { "action": "allow" },
                    { "action": "disallow", "os": { "name": "osx" } }
                  ],
                  "downloads": { "artifact": { "path": "a.jar", "sha1": "aaaa", "url": "u" } }
                }
              ]
            }
            """.trimIndent(),
        )
        val library = detail.libraries.single()
        assertTrue(library.appliesTo("Windows 11", "amd64"))
        assertFalse(library.appliesTo("Mac OS X", "x86_64"))
    }

    @Test
    fun `a version detail with no libraries section parses to an empty classpath`() {
        val detail = parseVersionDetail(versionDetailWithoutMappingsFixture)
        assertTrue(detail.libraries.isEmpty())
        assertTrue(detail.classpathLibraryArtifacts("Windows 11", "amd64").isEmpty())
    }
}
