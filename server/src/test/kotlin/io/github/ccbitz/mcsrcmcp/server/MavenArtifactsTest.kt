package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class MavenArtifactsTest {

    private class FakeRepo(private val files: Map<String, ByteArray>) : BlobFetcher {

        val requested = mutableListOf<String>()

        override suspend fun fetch(url: String): ByteArray {
            requested.add(url)
            return files[url] ?: error("HTTP 404 fetching $url")
        }

    }

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun withSha1(url: String, bytes: ByteArray) = mapOf(url to bytes, "$url.sha1" to sha1(bytes).toByteArray())

    @Test
    fun `a release version is fetched from its path with plus signs encoded`(@TempDir dir: Path) = runTest {
        val bytes = "mache".toByteArray()
        val url = "https://repo.example/io/papermc/mache/26.3%2Bbuild.1/mache-26.3%2Bbuild.1.zip"
        val repo = FakeRepo(withSha1(url, bytes))

        val path = MavenArtifacts(repo, BlobStore(dir)).fetch(MavenCoordinate.parse("io.papermc:mache:26.3+build.1@zip"), listOf("https://repo.example/"))

        assertArrayEquals(bytes, Files.readAllBytes(path))
    }

    @Test
    fun `a snapshot resolves to the timestamped file its metadata names`(@TempDir dir: Path) = runTest {
        val bytes = "codebook".toByteArray()
        val base = "https://repo.example/io/papermc/codebook/codebook-cli/2.0.1-SNAPSHOT"
        val metadata = """
            <metadata><versioning><snapshotVersions>
              <snapshotVersion><extension>pom</extension><value>2.0.1-20260919.143548-5</value></snapshotVersion>
              <snapshotVersion><classifier>all</classifier><extension>jar</extension><value>2.0.1-20260919.143548-5</value></snapshotVersion>
            </snapshotVersions></versioning></metadata>
        """.trimIndent()
        val repo = FakeRepo(
            mapOf("$base/maven-metadata.xml" to metadata.toByteArray()) +
                withSha1("$base/codebook-cli-2.0.1-20260919.143548-5-all.jar", bytes),
        )

        val coordinate = MavenCoordinate("io.papermc.codebook", "codebook-cli", "2.0.1-SNAPSHOT", classifier = "all")
        val path = MavenArtifacts(repo, BlobStore(dir)).fetch(coordinate, listOf("https://repo.example"))

        assertArrayEquals(bytes, Files.readAllBytes(path))
    }

    @Test
    fun `a repository without the artifact falls through to the next`(@TempDir dir: Path) = runTest {
        val bytes = "vineflower".toByteArray()
        val url = "https://repo.maven.apache.org/maven2/org/vineflower/vineflower/1.12.0/vineflower-1.12.0.jar"
        val repo = FakeRepo(withSha1(url, bytes))

        // Not in the named repository; Maven Central is always tried last.
        val path = MavenArtifacts(repo, BlobStore(dir)).fetch(MavenCoordinate.parse("org.vineflower:vineflower:1.12.0"), listOf("https://repo.example/"))

        assertArrayEquals(bytes, Files.readAllBytes(path))
    }

    // Only the checksum is fetched the second time: the blob store already holds those bytes.
    @Test
    fun `an artifact already in the blob store is not downloaded again`(@TempDir dir: Path) = runTest {
        val bytes = "defs".toByteArray()
        val url = "https://repo.example/g/n/1/n-1.jar"
        val repo = FakeRepo(withSha1(url, bytes))
        val artifacts = MavenArtifacts(repo, BlobStore(dir))

        artifacts.fetch(MavenCoordinate.parse("g:n:1"), listOf("https://repo.example/"))
        repo.requested.clear()
        artifacts.fetch(MavenCoordinate.parse("g:n:1"), listOf("https://repo.example/"))

        assertEquals(listOf("$url.sha1"), repo.requested)
    }

    @Test
    fun `an artifact no repository has is an error naming it`(@TempDir dir: Path) = runTest {
        val error = assertThrows<MavenArtifactException> {
            MavenArtifacts(FakeRepo(emptyMap()), BlobStore(dir)).fetch(MavenCoordinate.parse("g:missing:1"), listOf("https://repo.example/"))
        }
        assertEquals(true, error.message!!.contains("g:missing:1"))
    }

    @Test
    fun `coordinates parse classifier and extension`() {
        assertEquals(MavenCoordinate("g", "n", "1", "all", "jar"), MavenCoordinate.parse("g:n:1:all"))
        assertEquals(MavenCoordinate("g", "n", "1", null, "zip"), MavenCoordinate.parse("g:n:1@zip"))
    }

}
