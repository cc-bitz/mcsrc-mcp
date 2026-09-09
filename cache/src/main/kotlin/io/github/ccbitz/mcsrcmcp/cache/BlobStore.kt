package io.github.ccbitz.mcsrcmcp.cache

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class BlobStoreException(message: String) : IOException(message)

class BlobStore(private val root: Path) {
    init {
        Files.createDirectories(root)
    }

    fun has(sha1: String): Boolean = Files.exists(pathFor(sha1))

    fun get(sha1: String): ByteArray? {
        val path = pathFor(sha1)
        return if (Files.exists(path)) Files.readAllBytes(path) else null
    }

    /**
     * The stored blob's path, or null if it isn't cached yet. For blobs a caller can read
     * incrementally - a jar it wants a few entries out of - this avoids pulling the whole thing
     * into a ByteArray just to throw most of it away.
     */
    fun pathIfPresent(sha1: String): Path? = pathFor(sha1).takeIf { Files.exists(it) }

    fun put(bytes: ByteArray, expectedSha1: String): Path {
        val actualSha1 = sha1Hex(bytes)
        if (!actualSha1.equals(expectedSha1, ignoreCase = true)) {
            throw BlobStoreException("sha1 mismatch: expected $expectedSha1 but got $actualSha1")
        }

        val target = pathFor(expectedSha1)
        if (Files.exists(target)) {
            return target
        }

        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(root, "blob-", ".tmp")
        try {
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return target
    }

    private fun pathFor(sha1: String): Path {
        val normalized = sha1.lowercase()
        return root.resolve(normalized.substring(0, 2)).resolve(normalized)
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
