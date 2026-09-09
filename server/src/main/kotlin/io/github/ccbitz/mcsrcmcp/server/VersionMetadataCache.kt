package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import io.github.ccbitz.mcsrcmcp.cache.VersionManifest
import io.github.ccbitz.mcsrcmcp.cache.parseVersionDetail
import io.github.ccbitz.mcsrcmcp.cache.parseVersionManifest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal const val VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

// Mojang publishes snapshots often enough that a day-long cache would be wrong, and rarely enough
// that a per-call fetch is absurd. After this elapses we revalidate with If-None-Match, so the
// usual cost of being out of date is a 304 with no body rather than another 274KB.
private val MANIFEST_TTL: Duration = Duration.ofMinutes(15)

/**
 * Caches Mojang's version metadata.
 *
 * The manifest used to be re-fetched on *every tool call*: [resolveWorkspace] asks for it before it
 * even checks whether the workspace is already warm, so 274KB crossed the network to answer a
 * question about a version sitting decompiled on disk. That put a network round trip on the latency
 * floor of all 17 tools, and meant no tool worked offline no matter how much was cached.
 *
 * Version details are cached without expiry: their URLs are content-addressed by Mojang (the hash
 * is in the path), so a given URL's body never changes.
 */
class VersionMetadataCache(
    private val fetcher: BlobFetcher,
    // null keeps everything in memory only - a test, or a server with no cache root configured.
    private val cacheDir: Path? = null,
    private val ttl: Duration = MANIFEST_TTL,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lock = Mutex()
    private var cached: CachedManifest? = null

    private class CachedManifest(val manifest: VersionManifest, val etag: String?, val fetchedAt: Instant)

    suspend fun manifest(): VersionManifest = lock.withLock {
        val now = clock.instant()
        val current = cached ?: readManifestFromDisk()
        if (current != null && Duration.between(current.fetchedAt, now) < ttl) {
            cached = current
            return current.manifest
        }

        val refreshed = try {
            when (val response = fetcher.fetchIfNoneMatch(VERSION_MANIFEST_URL, current?.etag)) {
                // Only reachable when we sent an etag, which means current is non-null.
                is ConditionalFetch.NotModified -> {
                    touchManifestOnDisk(now)
                    CachedManifest(current!!.manifest, current.etag, now)
                }
                is ConditionalFetch.Body -> {
                    val body = response.bytes.toString(Charsets.UTF_8)
                    CachedManifest(parseVersionManifest(body), response.etag, now)
                        .also { writeManifestToDisk(response.bytes, response.etag) }
                }
            }
        } catch (e: Exception) {
            // Offline, or Mojang is down. A stale manifest still resolves every version already on
            // disk, which is the entire point of holding one - so degrade to it and only fail when
            // there is nothing cached at all.
            current ?: throw e
        }

        cached = refreshed
        refreshed.manifest
    }

    suspend fun detail(version: VersionListEntry): VersionDetail {
        val file = cacheDir?.resolve("meta")?.resolve("detail-${sha1Hex(version.url)}.json")
        if (file != null && Files.exists(file)) {
            runCatching { parseVersionDetail(Files.readString(file)) }.getOrNull()?.let { return it }
        }

        val bytes = fetcher.fetch(version.url)
        if (file != null) {
            runCatching { writeAtomic(file, bytes) }
        }
        return parseVersionDetail(bytes.toString(Charsets.UTF_8))
    }

    private fun manifestFile(): Path? = cacheDir?.resolve("meta")?.resolve("version_manifest_v2.json")

    private fun etagFile(): Path? = cacheDir?.resolve("meta")?.resolve("version_manifest_v2.etag")

    // The body's own last-modified time is the fetch time - no third file just to hold a timestamp.
    private fun readManifestFromDisk(): CachedManifest? {
        val file = manifestFile() ?: return null
        if (!Files.exists(file)) return null
        return runCatching {
            val manifest = parseVersionManifest(Files.readString(file))
            val etag = etagFile()?.takeIf { Files.exists(it) }?.let { Files.readString(it) }
            CachedManifest(manifest, etag, Files.getLastModifiedTime(file).toInstant())
        }.getOrNull()
    }

    private fun writeManifestToDisk(bytes: ByteArray, etag: String?) {
        val file = manifestFile() ?: return
        runCatching {
            writeAtomic(file, bytes)
            // The body's mtime IS the fetch time, so it has to come from the same clock the TTL is
            // measured against - not from whatever the filesystem happened to stamp on it.
            Files.setLastModifiedTime(file, FileTime.from(clock.instant()))
            val etagPath = etagFile()!!
            if (etag != null) writeAtomic(etagPath, etag.toByteArray(Charsets.UTF_8)) else Files.deleteIfExists(etagPath)
        }
    }

    private fun touchManifestOnDisk(now: Instant) {
        val file = manifestFile() ?: return
        runCatching { Files.setLastModifiedTime(file, FileTime.from(now)) }
    }

    private fun writeAtomic(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "meta-", ".tmp")
        try {
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun sha1Hex(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
