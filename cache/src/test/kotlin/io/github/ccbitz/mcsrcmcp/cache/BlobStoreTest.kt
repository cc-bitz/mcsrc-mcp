package io.github.ccbitz.mcsrcmcp.cache

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BlobStoreTest {
    @Test
    fun `put then get round-trips bytes`(@TempDir tempDir: Path) {
        val store = BlobStore(tempDir.resolve("blobs"))
        val bytes = "hello mcsrc".toByteArray()
        val realSha1 = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        store.put(bytes, realSha1)

        assertTrue(store.has(realSha1))
        assertArrayEquals(bytes, store.get(realSha1))
    }

    @Test
    fun `put rejects mismatched sha1`(@TempDir tempDir: Path) {
        val store = BlobStore(tempDir.resolve("blobs"))
        val bytes = "hello mcsrc".toByteArray()

        assertThrows(BlobStoreException::class.java) {
            store.put(bytes, "0000000000000000000000000000000000000000")
        }
    }

    @Test
    fun `get returns null for unknown blob`(@TempDir tempDir: Path) {
        val store = BlobStore(tempDir.resolve("blobs"))
        assertNull(store.get("0000000000000000000000000000000000000000"))
        assertFalse(store.has("0000000000000000000000000000000000000000"))
    }

    @Test
    fun `put is idempotent for the same blob`(@TempDir tempDir: Path) {
        val store = BlobStore(tempDir.resolve("blobs"))
        val bytes = "hello mcsrc".toByteArray()
        val realSha1 = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        val first = store.put(bytes, realSha1)
        val second = store.put(bytes, realSha1)
        assertEquals(first, second)
    }
}
