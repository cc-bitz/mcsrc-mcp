package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import java.io.BufferedReader
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class GameBridgeException(message: String) : RuntimeException(message)

// The bridge's first query pays JVM spawn + full game bootstrap (~5-15s); every query after that
// is milliseconds, so the process stays resident for the version rather than paying boot per
// query like datagen does. 90s covers a bootstrap on a cold machine with room to spare.
private val BRIDGE_QUERY_TIMEOUT: Duration = Duration.ofSeconds(90)
private val BRIDGE_HANDSHAKE_TIMEOUT: Duration = Duration.ofSeconds(30)

// Bump when the bridge protocol or its writer output changes shape, so old per-query cache files
// are never read back as if they were current-format results.
private const val BRIDGE_CACHE_FORMAT = "bridge-v1"

/**
 * Client for the sidecar bridge ([BridgeMain] in the `bridge` module): a resident JVM running the
 * actual game, asked to read what only a booted game knows - static state that code initializes
 * rather than ships as JSON (villager trades, composting chances, ...). Each (version, query)
 * answer is cached under the version's derived cache, so a resident process plus its cache makes
 * repeat questions free.
 *
 * Failure model: a query that dies (process crash, timeout, protocol gibberish) respawns the
 * bridge once and retries; a second death in one call surfaces as [GameBridgeException] with the
 * stderr tail. The bridge itself never gets to write anything to disk, so a crash can't corrupt
 * state - only time is lost.
 */
class GameBridge(
    private val blobStore: BlobStore,
    private val fetcher: BlobFetcher,
    private val cacheRoot: Path,
) {
    private class BridgeProcess(val process: Process) {
        val mutex = Mutex()
        lateinit var stdin: PrintStream
        lateinit var stdout: BufferedReader
        val stderrTail = ArrayDeque<String>()

        fun stderrSnapshot(): String = synchronized(stderrTail) { stderrTail.joinToString("\n") }
    }

    // Not a control-flow exception: it marks one dead bridge attempt so exchange() can respawn
    // and retry exactly once without callers needing to know respawning happens.
    private class BridgeDied(message: String) : RuntimeException(message)

    private val processes = ConcurrentHashMap<String, BridgeProcess>()
    private val spawnGates = ConcurrentHashMap<String, Mutex>()

    init {
        // The resident JVMs are this server's responsibility, not the OS's: a killed MCP server
        // must not leave booted games running headlessly in the background.
        Runtime.getRuntime().addShutdownHook(Thread {
            processes.values.forEach { runCatching { it.process.destroyForcibly() } }
        })
    }

    /**
     * Reads a static field from the booted game and returns its JSON serialization (compact, one
     * line - pretty-printing is the serving tool's business). [onHeartbeat] fires every few
     * seconds while a query is in flight, which in practice means during the first-query
     * bootstrap.
     */
    suspend fun readStaticField(
        versionId: String,
        detail: VersionDetail,
        clientJarPath: Path,
        derivedDir: Path,
        className: String,
        fieldName: String,
        onHeartbeat: suspend (elapsedSeconds: Int) -> Unit = {},
    ): String {
        val cacheFile = derivedDir.resolve("bridge").resolve(cacheKeyFor(className, fieldName))
        if (Files.exists(cacheFile)) return Files.readString(cacheFile)

        val response = exchangeWithHeartbeat(versionId, detail, clientJarPath, "static\t$className\t$fieldName", onHeartbeat)
        val value = parseResponse(response, versionId)

        Files.createDirectories(cacheFile.parent)
        writeAtomically(cacheFile, value)
        return value
    }

    fun clear(versionId: String) {
        processes.remove(versionId)?.let { runCatching { it.process.destroyForcibly() } }
    }

    fun clearAll() {
        processes.keys.toList().forEach { clear(it) }
    }

    private suspend fun exchangeWithHeartbeat(
        versionId: String,
        detail: VersionDetail,
        clientJarPath: Path,
        request: String,
        onHeartbeat: suspend (Int) -> Unit,
    ): String = coroutineScope {
        val ticker = launch {
            var elapsed = 0
            while (true) {
                delay(5_000)
                elapsed += 5
                onHeartbeat(elapsed)
            }
        }
        try {
            exchange(versionId, detail, clientJarPath, request)
        } finally {
            ticker.cancel()
        }
    }

    private suspend fun exchange(versionId: String, detail: VersionDetail, clientJarPath: Path, request: String): String {
        var attempt = 0
        while (true) {
            val bridge = processFor(versionId, detail, clientJarPath)
            try {
                return query(bridge, request)
            } catch (e: BridgeDied) {
                processes.remove(versionId, bridge)
                runCatching { bridge.process.destroyForcibly() }
                if (++attempt > 1) {
                    val tail = bridge.stderrSnapshot().ifBlank { "(no stderr)" }
                    throw GameBridgeException("the game bridge for $versionId died twice: ${e.message}\n$tail")
                }
            }
        }
    }

    private suspend fun query(bridge: BridgeProcess, request: String): String = bridge.mutex.withLock {
        if (!bridge.process.isAlive) throw BridgeDied("process not alive before write")
        bridge.stdin.println(request)
        bridge.stdin.flush()
        // The timeout wraps the read *inside* the IO dispatcher: a caller on a virtual-time
        // scheduler (kotlinx-coroutines-test) would otherwise fire the timeout on its first
        // suspension instead of after real elapsed time.
        val line = withContext(Dispatchers.IO) {
            withTimeoutOrNull(BRIDGE_QUERY_TIMEOUT.toMillis()) { bridge.stdout.readLine() }
        } ?: throw BridgeDied("no response within ${BRIDGE_QUERY_TIMEOUT.toSeconds()}s")
        if (line.isBlank()) throw BridgeDied("empty response line")
        line
    }

    private suspend fun processFor(versionId: String, detail: VersionDetail, clientJarPath: Path): BridgeProcess {
        processes[versionId]?.takeIf { it.process.isAlive }?.let { return it }

        val gate = spawnGates.computeIfAbsent(versionId) { Mutex() }
        return gate.withLock {
            processes[versionId]?.takeIf { it.process.isAlive }?.let { return it }

            val classpath = assembleGameClasspath(blobStore, fetcher, detail, clientJarPath) +
                File.pathSeparator +
                extractBridgeJar()
            val java = resolveGameJavaCommand(detail)

            // The logging override stops vanilla's config from writing logs/latest.log relative to
            // the working directory (which MCP clients set to the project dir) - see
            // [ChildJvmLogging].
            val process = ProcessBuilder(
                java,
                "-Xmx1G",
                "-Dlog4j.configurationFile=${ChildJvmLogging.configUri(cacheRoot)}",
                "-cp", classpath,
                "io.github.ccbitz.mcsrcmcp.bridge.BridgeMain",
            ).start()
            val bridge = BridgeProcess(process).apply {
                stdin = PrintStream(process.outputStream, true, Charsets.UTF_8)
                stdout = process.inputStream.bufferedReader(Charsets.UTF_8)
            }

            val stderrReader = Thread {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        synchronized(bridge.stderrTail) {
                            bridge.stderrTail.addLast(line)
                            if (bridge.stderrTail.size > 100) bridge.stderrTail.removeFirst()
                        }
                    }
                }
            }
            stderrReader.isDaemon = true
            stderrReader.start()

            // The handshake is printed before any game class loads, so it only fails if the JVM
            // itself (or the classpath) is broken - a slow bootstrap can't trip it. Timeout runs
            // inside Dispatchers.IO so caller-side virtual time can't fire it early.
            val handshake = withContext(Dispatchers.IO) {
                withTimeoutOrNull(BRIDGE_HANDSHAKE_TIMEOUT.toMillis()) { bridge.stdout.readLine() }
            }
            if (handshake == null || !handshake.contains("\"ok\":true")) {
                // Read the exit state BEFORE destroying, or the code we report is our own kill.
                val exitState = if (process.isAlive) "still running" else "exited with code ${process.exitValue()}"
                // Give the stderr drainer a beat to catch whatever killed the child, then report
                // it - "failed to start" alone would send someone debugging blind.
                kotlinx.coroutines.delay(500)
                val stderr = bridge.stderrSnapshot().ifBlank { "(no stderr)" }
                runCatching { process.destroyForcibly() }
                throw GameBridgeException(
                    "bridge for $versionId failed to start: ${handshake ?: "no handshake ($exitState)"}\n" +
                        "command: $java -cp <${classpath.split(File.pathSeparator).size} entries, last: ${classpath.substringAfterLast(File.pathSeparator)}> io.github.ccbitz.mcsrcmcp.bridge.BridgeMain\n$stderr",
                )
            }

            processes[versionId] = bridge
            bridge
        }
    }

    // The bridge jar rides inside this server's resources and lands beside the cache, named by
    // content hash - a rebuilt bridge never reuses a stale extraction.
    private fun extractBridgeJar(): Path {
        val bytes = GameBridge::class.java.getResourceAsStream("/bridge.jar")?.readBytes()
            ?: throw GameBridgeException("bridge.jar is missing from the server's resources - build with the bridge module on the classpath")
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(12)
        val target = cacheRoot.resolve("bin").resolve("bridge-$hash.jar")
        if (Files.exists(target)) return target
        writeAtomically(target, bytes)
        return target
    }

    private fun parseResponse(response: String, versionId: String): String {
        val element = try {
            Json.parseToJsonElement(response)
        } catch (e: Exception) {
            throw GameBridgeException("bridge for $versionId returned an unparseable response: ${response.take(500)}")
        }
        val obj = element as? JsonObject
            ?: throw GameBridgeException("bridge for $versionId returned a non-object response")
        if (obj["ok"]?.let { (it as? JsonPrimitive)?.content } != "true") {
            val error = obj["error"]?.let { (it as? JsonPrimitive)?.content } ?: "unknown error"
            throw GameBridgeException(error)
        }
        return obj["value"]?.toString() ?: "null"
    }

    private fun cacheKeyFor(className: String, fieldName: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$BRIDGE_CACHE_FORMAT|$className|$fieldName".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(20)
        return "$digest.json"
    }

    private fun writeAtomically(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "bridge-", ".tmp")
        try {
            Files.writeString(tmp, content)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "bridge-", ".tmp")
        try {
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
