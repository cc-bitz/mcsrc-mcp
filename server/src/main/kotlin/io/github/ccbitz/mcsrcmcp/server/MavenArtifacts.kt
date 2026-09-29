package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

class MavenArtifactException(message: String) : IllegalStateException(message)

private const val MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/"

/** A Maven artifact: `group:name:version[:classifier][@extension]`. */
data class MavenCoordinate(
    val group: String,
    val name: String,
    val version: String,
    val classifier: String? = null,
    val extension: String = "jar",
) {

    override fun toString(): String =
        "$group:$name:$version" + (classifier?.let { ":$it" } ?: "") + if (extension == "jar") "" else "@$extension"

    companion object {

        fun parse(text: String): MavenCoordinate {
            val extension = text.substringAfter('@', "jar")
            val parts = text.substringBefore('@').split(':')
            require(parts.size in 3..4) { "not a maven coordinate: $text" }
            return MavenCoordinate(parts[0], parts[1], parts[2], parts.getOrNull(3), extension)
        }

    }

}

/**
 * Fetches Maven artifacts into the blob store - the tools a fork source tree is built with (mache,
 * codebook, the unpick definitions, the Vineflower mache names), which are resolved from mache's own
 * metadata at run time rather than shipped. Each artifact is stored under the sha1 its repository
 * publishes beside it, so a second fetch costs only that checksum.
 */
class MavenArtifacts(private val fetcher: BlobFetcher, private val blobStore: BlobStore) {

    /**
     * The artifact from the first of [repositories] (then Maven Central) that has it.
     *
     * @throws MavenArtifactException If none does.
     */
    suspend fun fetch(coordinate: MavenCoordinate, repositories: List<String>): Path {
        val failures = mutableListOf<String>()
        for (repository in (repositories + MAVEN_CENTRAL).distinct()) {
            val base = repository.trimEnd('/') + "/" + coordinate.group.replace('.', '/') + "/" + coordinate.name + "/" + encode(coordinate.version)
            try {
                val fileVersion = if (coordinate.version.endsWith("-SNAPSHOT")) snapshotValue(base, coordinate) else coordinate.version
                val url = "$base/${coordinate.name}-${encode(fileVersion)}" + (coordinate.classifier?.let { "-$it" } ?: "") + ".${coordinate.extension}"
                val sha1 = fetcher.fetch("$url.sha1").toString(Charsets.UTF_8).trim().take(40).lowercase()
                return blobStore.pathIfPresent(sha1) ?: blobStore.put(fetcher.fetch(url), sha1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures.add("$repository: ${e.message}")
            }
        }
        throw MavenArtifactException("no repository has $coordinate (${failures.joinToString("; ")})")
    }

    // A snapshot's files are named by the timestamped value its metadata lists per classifier and
    // extension, not by "-SNAPSHOT" itself.
    private suspend fun snapshotValue(base: String, coordinate: MavenCoordinate): String {
        val metadata = fetcher.fetch("$base/maven-metadata.xml").toString(Charsets.UTF_8)
        for (block in SNAPSHOT_VERSION.findAll(metadata)) {
            val body = block.groupValues[1]
            val classifier = CLASSIFIER.find(body)?.groupValues?.get(1)
            val extension = EXTENSION.find(body)?.groupValues?.get(1)
            if (classifier == coordinate.classifier && extension == coordinate.extension) {
                VALUE.find(body)?.let { return it.groupValues[1] }
            }
        }
        throw MavenArtifactException("snapshot metadata lists no ${coordinate.extension} for $coordinate")
    }

    private fun encode(segment: String) = segment.replace("+", "%2B")

    private companion object {

        val SNAPSHOT_VERSION = Regex("""<snapshotVersion>(.*?)</snapshotVersion>""", RegexOption.DOT_MATCHES_ALL)
        val CLASSIFIER = Regex("""<classifier>([^<]*)</classifier>""")
        val EXTENSION = Regex("""<extension>([^<]*)</extension>""")
        val VALUE = Regex("""<value>([^<]*)</value>""")

    }

}
