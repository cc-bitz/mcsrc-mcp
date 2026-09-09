package io.github.ccbitz.mcsrcmcp.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The logging override every game-code subprocess of this server needs: vanilla's own log4j2
 * config (inside the client jar) appends to logs/latest.log relative to the working directory,
 * and MCP clients start this server with the project dir as cwd - so an unpinned child would
 * scatter a stray logs/ dir into every project it warms a cache from. Passing
 * `-Dlog4j.configurationFile=[configUri]` (see [configUri]) outranks the classpath config and
 * replaces the file appender with console-to-stderr, which both children already drain.
 */
internal object ChildJvmLogging {
    /**
     * Extracts the override config beside the extracted bridge jar and returns it as an absolute
     * URI. The file is named by content hash - a changed config never reuses a stale extraction,
     * the same policy as the bridge jar itself.
     */
    fun configUri(cacheRoot: Path): String {
        val bytes = ChildJvmLogging::class.java.getResourceAsStream("/log4j2.xml")?.readBytes()
            ?: throw IllegalStateException("log4j2.xml is missing from the server's resources")
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(12)
        val target = cacheRoot.resolve("bin").resolve("log4j2-$hash.xml")
        if (!Files.exists(target)) {
            Files.createDirectories(target.parent)
            val tmp = Files.createTempFile(target.parent, "log4j2-", ".tmp")
            try {
                Files.write(tmp, bytes)
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
        return target.toAbsolutePath().toUri().toString()
    }
}
