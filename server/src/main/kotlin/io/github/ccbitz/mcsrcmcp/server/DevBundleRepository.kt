package io.github.ccbitz.mcsrcmcp.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// Same reasoning as the Mojang manifest TTL: forks publish new builds often enough that a day
// would be wrong, rarely enough that per-call fetching is absurd.
private val DEV_BUNDLE_METADATA_TTL: Duration = Duration.ofMinutes(15)

// How many alternatives a not-found error lists - enough to show the pattern, short enough that
// an agent reads it.
private const val SUGGESTION_LIMIT = 8

class DevBundleNotFoundException(val variant: String, val mcVersion: String, val spec: String?, message: String) :
    IllegalArgumentException(message)

class DevBundleAmbiguousException(val variant: String, val mcVersion: String, val candidates: List<String>) :
    IllegalArgumentException("build spec matches several $variant builds for minecraft $mcVersion: ${candidates.joinToString()}")

class DevBundleException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * A resolved dev bundle: its version string and the direct URL of its zip. The URL identifies the
 * build's bytes (snapshot URLs carry their publish timestamp), so it doubles as a cache key.
 */
data class DevBundle(val version: String, val zipUrl: String)

@Serializable
data class DevBundleConfig(
    val minecraftVersion: String,
    val mojangMappedPaperclipFile: String? = null,
    // Bundles since the fork moved onto mache (1.21.4) name it and ship their source patches; see
    // MacheTreeBuilder and ForkSourceTree.
    val mache: MacheRef? = null,
    val patchDir: String? = null,
)

private val metadataJson = Json { ignoreUnknownKeys = true }

fun parseDevBundleConfig(text: String): DevBundleConfig = metadataJson.decodeFromString(text)

/**
 * The dev bundle's maven-metadata.xml only exists per version for -SNAPSHOT versions; release-style
 * versions (the 26.x "build.N" scheme) resolve their zip by direct URL. This picks the timestamped
 * zip filename out of snapshot metadata - the "zip" extension row is the whole-bundle artifact.
 */
fun parseSnapshotZipValue(metadataText: String): String? =
    Regex("""<extension>zip</extension>\s*<value>([^<]+)</value>""")
        .find(metadataText)?.groupValues?.get(1)

private val VERSIONS_BLOCK = Regex("""<versions>(.*?)</versions>""", RegexOption.DOT_MATCHES_ALL)
private val VERSION_ENTRY = Regex("""<version>([^<]+)</version>""")

// Scoped to the <versions> block when there is one: artifact metadata never has a top-level
// <version>, but reading only the list means a repo that adds one can't inject a phantom build.
fun parseDevBundleVersions(metadataText: String): List<String> {
    val scope = VERSIONS_BLOCK.find(metadataText)?.groupValues?.get(1) ?: metadataText
    return VERSION_ENTRY.findAll(scope).map { it.groupValues[1] }.toList()
}

fun devBundleZipUrl(base: String, version: String, snapshotValue: String?): String =
    "$base/$version/dev-bundle-${snapshotValue ?: version}.zip"

// Old scheme: "<mcVersion>-R0.1-SNAPSHOT" (exact - so "1.21.1" never matches "1.21.11-...").
// New scheme: "<mcVersion>.build.N-<channel>"; the trailing '.' in the prefix keeps
// "26.3.build.N" from matching the mc version "26.3" of a different release train... and keeps
// "1.21.1.build.N" from matching "1.21.11" for the same reason.
fun devBundleVersionMatches(bundleVersion: String, mcVersion: String): Boolean =
    bundleVersion == "$mcVersion-R0.1-SNAPSHOT" || bundleVersion.startsWith("$mcVersion.build.")

/**
 * The Minecraft version a dev bundle version targets, by the same two schemes
 * [devBundleVersionMatches] accepts; null for one-off bundles outside both ("1.21.5-no-moonrise-SNAPSHOT",
 * a stray "26.1.2.local-SNAPSHOT"), which no Minecraft version resolves to anyway.
 */
fun devBundleMinecraftVersion(bundleVersion: String): String? = when {
    ".build." in bundleVersion -> bundleVersion.substringBefore(".build.")
    bundleVersion.endsWith("-R0.1-SNAPSHOT") -> bundleVersion.removeSuffix("-R0.1-SNAPSHOT")
    else -> null
}

private val BUILD_NUMBER = Regex("""\.build\.(\d+)""")

private fun buildNumber(bundleVersion: String): Int =
    BUILD_NUMBER.find(bundleVersion)?.groupValues?.get(1)?.toIntOrNull() ?: 0

/**
 * Picks the dev bundle version for a Minecraft version out of the published list. No spec means
 * the newest build (highest .build.N; old-scheme versions carry no build number and are unique per
 * Minecraft version). A spec pins one: exact match wins; a bare number is a build number, matched
 * exactly - as a substring "4" would also hit builds 14, 24 and 40-49, so it could never pin build
 * 4; anything else must identify exactly one candidate by substring - "build.49-alpha" or just
 * "49-alpha" both do.
 */
fun selectDevBundleBuild(variant: String, available: List<String>, mcVersion: String, spec: String?): String {
    val candidates = available.filter { devBundleVersionMatches(it, mcVersion) }
    if (candidates.isEmpty()) {
        // Newest first: metadata lists versions in publish order.
        val published = available.asReversed().mapNotNull { devBundleMinecraftVersion(it) }.distinct().take(SUGGESTION_LIMIT)
        throw DevBundleNotFoundException(
            variant,
            mcVersion,
            spec,
            "$variant publishes no build for minecraft $mcVersion" +
                if (published.isEmpty()) "" else "; newest versions it has builds for: ${published.joinToString()}",
        )
    }

    if (spec == null) {
        return candidates.maxWith(compareBy({ buildNumber(it) }, { it }))
    }

    candidates.singleOrNull { it == spec }?.let { return it }
    val matches = spec.toIntOrNull()
        ?.let { number -> candidates.filter { BUILD_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() == number } }
        ?: candidates.filter { it.contains(spec) }
    return when (matches.size) {
        0 -> throw DevBundleNotFoundException(
            variant,
            mcVersion,
            spec,
            "no $variant build for minecraft $mcVersion matches '$spec'; newest builds: " +
                candidates.sortedByDescending { buildNumber(it) }.take(SUGGESTION_LIMIT).joinToString(),
        )

        1 -> matches.single()
        else -> throw DevBundleAmbiguousException(variant, mcVersion, matches)
    }
}

/**
 * One fork's published dev-bundle metadata: the version list a build is resolved from, cached in
 * memory and on disk with a TTL - [io.github.ccbitz.mcsrcmcp.cache.VersionMetadataCache] exists
 * because of this exact problem at larger scale (per-call metadata fetches on the latency floor of
 * every tool). Resolution itself ([resolve]) is two cheap lookups on a warm cache; the network
 * only sees each metadata file at most once per TTL.
 */
class DevBundleRepository(
    val variant: String,
    private val baseUrl: String,
    private val fetcher: BlobFetcher,
    private val cacheDir: Path? = null,
    private val ttl: Duration = DEV_BUNDLE_METADATA_TTL,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lock = Mutex()
    private var cached: CachedValue<List<String>>? = null

    // Per-snapshot zip names, memory only: old-scheme versions are the rarely-used tail, and a
    // restart costing one small fetch per snapshot touched is cheaper than another disk format.
    private val snapshotValues = ConcurrentHashMap<String, CachedValue<String>>()

    private class CachedValue<T>(val value: T, val fetchedAt: Instant)

    private fun versionsFile(): Path? = cacheDir?.resolve("$variant-dev-bundle-versions.xml")

    private fun isFresh(entry: CachedValue<*>, now: Instant): Boolean = Duration.between(entry.fetchedAt, now) < ttl

    suspend fun versions(): List<String> = lock.withLock {
        val now = clock.instant()
        val current = cached ?: readFromDisk()
        if (current != null && isFresh(current, now)) {
            cached = current
            return current.value
        }

        val refreshed = try {
            val bytes = fetcher.fetch("$baseUrl/maven-metadata.xml")
            val versions = parseDevBundleVersions(bytes.toString(Charsets.UTF_8))
            writeVersionsToDisk(bytes)
            CachedValue(versions, now)
        } catch (e: Exception) {
            // Offline, or the repo is down - a stale list still resolves every build already on
            // disk, which matters more than freshness while the network is gone.
            current ?: throw e
        }

        cached = refreshed
        refreshed.value
    }

    /**
     * Resolves the dev bundle for [mcVersion]: [spec] null means latest build, otherwise it pins
     * one (see [selectDevBundleBuild]). Snapshots need their timestamped zip name from one more
     * metadata file; release-style versions resolve by direct URL.
     */
    suspend fun resolve(mcVersion: String, spec: String?): DevBundle {
        val version = selectDevBundleBuild(variant, versions(), mcVersion, spec)
        val snapshotValue = if (version.endsWith("-SNAPSHOT")) snapshotZipValue(version) else null
        return DevBundle(version, devBundleZipUrl(baseUrl, version, snapshotValue))
    }

    private suspend fun snapshotZipValue(version: String): String {
        val now = clock.instant()
        val current = snapshotValues[version]
        if (current != null && isFresh(current, now)) return current.value

        val value = try {
            val metadata = fetcher.fetch("$baseUrl/$version/maven-metadata.xml").toString(Charsets.UTF_8)
            parseSnapshotZipValue(metadata)
                ?: throw DevBundleException("$variant dev bundle $version has no zip snapshot version in its metadata")
        } catch (e: DevBundleException) {
            throw e
        } catch (e: Exception) {
            current?.value ?: throw e
        }
        snapshotValues[version] = CachedValue(value, now)
        return value
    }

    private fun readFromDisk(): CachedValue<List<String>>? {
        val file = versionsFile() ?: return null
        if (!Files.exists(file)) return null
        return runCatching {
            CachedValue(parseDevBundleVersions(Files.readString(file)), Files.getLastModifiedTime(file).toInstant())
        }.getOrNull()
    }

    private fun writeVersionsToDisk(bytes: ByteArray) {
        val file = versionsFile() ?: return
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = Files.createTempFile(file.parent, "$variant-meta-", ".tmp")
            try {
                Files.write(tmp, bytes)
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }
}
