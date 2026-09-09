package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.VersionDetail
import io.github.ccbitz.mcsrcmcp.cache.classpathLibraryArtifacts
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

class DatagenFailedException(message: String) : RuntimeException(message)

// Providers take ~1.5s; the JVM boot + full registry bootstrap around them is the real cost, and a
// cold machine with slow disk can stretch both. Minutes of slack, not seconds - but bounded, so a
// wedged run can't hold a tool call open indefinitely.
private val DATAGEN_TIMEOUT: Duration = Duration.ofMinutes(10)
private const val DATAGEN_HEARTBEAT_SECONDS: Int = 5

// Set MCSRC_DATAGEN_JAVA=/path/to/java to bypass JDK discovery entirely - the escape hatch when a
// machine has the right JDK somewhere the candidate scan doesn't look.
private const val DATAGEN_JAVA_ENV: String = "MCSRC_DATAGEN_JAVA"

/**
 * Runs Mojang's own data generator (`net.minecraft.data.Main --reports`) against the client jar
 * the preparer already cached, and stores its JSON reports under the version's derived cache
 * directory. Output is a recursive tree - top-level `registries.json`/`blocks.json`/
 * `commands.json`/`packets.json`/`datapack.json`, plus `minecraft/components/item/<id>.json` per
 * item and `biome_parameters/<namespace>/...`.
 *
 * The generator needs the Java the *game* was compiled for, which is not necessarily the Java this
 * server runs on, so the executable is discovered per requested major version from the running
 * JVM, JAVA_HOME, and the usual install roots - falling back to [DATAGEN_JAVA_ENV].
 */
class ReportGenerator(
    private val blobStore: BlobStore,
    private val fetcher: BlobFetcher,
    private val cacheRoot: Path,
    private val scope: CoroutineScope,
) {
    private val runs = ConcurrentHashMap<String, Deferred<Path>>()

    /**
     * Returns the completed reports directory for [versionId], generating it first if needed.
     * Single-flight per version: concurrent callers wait on the same run instead of spawning
     * competing JVMs. [onHeartbeat] fires every few seconds while a generation runs, so a tool
     * handler can stream progress for a call that is otherwise silent for the better part of a
     * minute. Throws [DatagenFailedException] on any failure; the failed run is removed from the
     * in-flight map so the next call retries rather than caching the failure forever.
     */
    suspend fun ensureReports(
        versionId: String,
        detail: VersionDetail,
        clientJarPath: Path,
        derivedDir: Path,
        onHeartbeat: suspend (elapsedSeconds: Int) -> Unit = {},
    ): Path {
        val reportsDir = derivedDir.resolve("reports")
        if (isComplete(reportsDir)) return reportsDir

        val run = runs.getOrPut(versionId) {
            scope.async(Dispatchers.IO) { generate(versionId, detail, clientJarPath, derivedDir, reportsDir, onHeartbeat) }
        }
        return try {
            run.await()
        } catch (e: Throwable) {
            runs.remove(versionId)
            throw e
        }
    }

    fun reportsIfComplete(versionId: String, derivedDir: Path): Path? {
        val reportsDir = derivedDir.resolve("reports")
        return reportsDir.takeIf { isComplete(it) }
    }

    /** [clear]-style drop of in-memory state, for the clear_cache tool. */
    fun clear(versionId: String) {
        runs.remove(versionId)
    }

    fun clearAll() {
        runs.keys.toList().forEach { runs.remove(it) }
    }

    private suspend fun generate(
        versionId: String,
        detail: VersionDetail,
        clientJarPath: Path,
        derivedDir: Path,
        reportsDir: Path,
        onHeartbeat: suspend (elapsedSeconds: Int) -> Unit,
    ): Path = coroutineScope {
        val ticker = launch {
            var elapsed = 0
            while (isActive) {
                delay(DATAGEN_HEARTBEAT_SECONDS * 1000L)
                elapsed += DATAGEN_HEARTBEAT_SECONDS
                onHeartbeat(elapsed)
            }
        }
        try {
            // A previous crashed run can leave a partial tree behind; start clean so a regenerated
            // directory is never a mix of two runs.
            deleteRecursively(reportsDir)
            Files.createDirectories(derivedDir)
            val outputDir = Files.createTempDirectory(derivedDir, "datagen-")

            try {
                val classpath = buildClasspath(detail, clientJarPath)
                runDatagen(classpath, outputDir, versionId, detail)

                val generated = outputDir.resolve("reports")
                if (!Files.isDirectory(generated) || Files.list(generated).use { it.findFirst().isEmpty }) {
                    throw DatagenFailedException("datagen for $versionId exited cleanly but wrote no reports")
                }
                Files.move(generated, reportsDir, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                deleteRecursively(outputDir)
            }

            // The completion stamp is what separates "cached and complete" from "partial tree left
            // by a crashed run" - generation writes reports files directly, so their presence alone
            // would be ambiguous.
            Files.createDirectories(reportsDir)
            Files.writeString(reportsDir.resolve(COMPLETE_STAMP), versionId)
            reportsDir
        } finally {
            ticker.cancel()
        }
    }

    /**
     * Fetches every rule-applicable library into the blob store and joins them into a -cp string.
     * Delegates to [assembleGameClasspath], shared with the sidecar bridge - both run game code
     * from this server's own subprocesses and need the identical classpath.
     */
    private suspend fun buildClasspath(detail: VersionDetail, clientJarPath: Path): String =
        assembleGameClasspath(blobStore, fetcher, detail, clientJarPath)

    private suspend fun runDatagen(classpath: String, outputDir: Path, versionId: String, detail: VersionDetail) {
        val java = resolveJavaCommand(detail)
        val command = listOf(
            java,
            "-Xmx2G",
            // Same logging override as the bridge: keep vanilla's logs/latest.log file appender
            // out of the project dir MCP clients set as the server's cwd - see [ChildJvmLogging].
            "-Dlog4j.configurationFile=${ChildJvmLogging.configUri(cacheRoot)}",
            "-cp",
            classpath,
            "net.minecraft.data.Main",
            "--reports",
            "--output",
            outputDir.toString(),
        )

        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = CompletableDeferred<String>()
        val reader = Thread {
            // Drained eagerly: a full stdout pipe would deadlock the child while we poll.
            output.complete(runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault(""))
        }
        reader.isDaemon = true
        reader.start()

        val exited = withTimeoutOrNull(DATAGEN_TIMEOUT.toMillis()) {
            while (process.isAlive) delay(500)
            true
        }
        if (exited != true) {
            process.destroyForcibly()
            throw DatagenFailedException("datagen for $versionId timed out after ${DATAGEN_TIMEOUT.toMinutes()} minutes")
        }

        val text = output.await()
        if (process.exitValue() != 0) {
            val tail = text.lines().takeLast(25).joinToString("\n").ifBlank { "(no output)" }
            throw DatagenFailedException("datagen for $versionId exited with code ${process.exitValue()}:\n$tail")
        }
    }

    private fun resolveJavaCommand(detail: VersionDetail): String = resolveGameJavaCommand(detail)

    companion object {
        internal const val COMPLETE_STAMP = ".complete"

        internal fun isComplete(reportsDir: Path): Boolean = Files.exists(reportsDir.resolve(COMPLETE_STAMP))
    }
}

/**
 * Fetches every rule-applicable library into the blob store and joins them into a classpath string.
 * Libraries are new load on the store - only the client jar, its mappings, and metadata crossed it
 * before - but they're content-addressed by sha1, so a second version sharing a library version
 * finds it waiting. Bounded concurrency: ~130 parallel fetches would be its own DoS.
 */
internal suspend fun assembleGameClasspath(blobStore: BlobStore, fetcher: BlobFetcher, detail: VersionDetail, clientJarPath: Path): String = coroutineScope {
    val gate = Semaphore(8)
    val artifacts = detail.classpathLibraryArtifacts(System.getProperty("os.name") ?: "", System.getProperty("os.arch") ?: "")
    artifacts.map { artifact ->
        launch {
            gate.withPermit {
                if (blobStore.pathIfPresent(artifact.sha1) == null) {
                    blobStore.put(fetcher.fetch(artifact.url), artifact.sha1)
                }
            }
        }
    }.joinAll()

    val paths = listOf(clientJarPath) + artifacts.mapNotNull { blobStore.pathIfPresent(it.sha1) }
    if (paths.size != artifacts.size + 1) {
        throw DatagenFailedException("a library jar went missing from the blob store between fetch and classpath assembly")
    }
    paths.joinToString(File.pathSeparator)
}

// The Java the game's classes were compiled for, not the Java this server runs on (26.2 is class
// file 69 = JDK 25 while the server builds on 21). Shared by datagen and the bridge.
internal fun resolveGameJavaCommand(detail: VersionDetail): String {
    System.getenv(DATAGEN_JAVA_ENV)?.let { return it }

    val required = detail.javaVersion?.majorVersion ?: 21
    val picked = pickJavaCommand(required, candidateJavaHomes())
        ?: throw DatagenFailedException(
            "no JDK >= $required found (the game's classes need Java $required+); looked at the running JVM, " +
                "JAVA_HOME, and the usual install roots - or set $DATAGEN_JAVA_ENV to a java executable",
        )
    return picked.toString()
}

/** Reads `<home>/release`'s JAVA_VERSION line ("25.0.2" or legacy "1.8.0_392") into its major. */
internal fun readJavaMajorFromRelease(home: Path): Int? {
    val release = home.resolve("release")
    if (!Files.isRegularFile(release)) return null
    val line = runCatching { Files.readAllLines(release) }.getOrNull()
        ?.firstOrNull { it.startsWith("JAVA_VERSION=") }
        ?: return null
    val version = line.substringAfter('=').trim('"')
    val first = version.substringBefore('.').toIntOrNull() ?: return null
    // Legacy 1.x scheme: the major is the second component ("1.8.0" -> 8).
    return if (first == 1) version.split('.').getOrNull(1)?.toIntOrNull() else first
}

internal fun candidateJavaHomes(): List<Path> {
    val homes = mutableListOf<Path>()
    System.getProperty("java.home")?.let { homes.add(Path.of(it)) }
    System.getenv("JAVA_HOME")?.let { homes.add(Path.of(it)) }
    for (root in listOf(
        Path.of("C:/Program Files/Java"),
        Path.of("C:/Program Files/Eclipse Adoptium"),
        Path.of("C:/Program Files/Microsoft"),
        Path.of("C:/Program Files/Amazon Corretto"),
        Path.of("C:/Program Files/Zulu"),
        Path.of("/usr/lib/jvm"),
        Path.of("/Library/Java/JavaVirtualMachines"),
    )) {
        if (!Files.isDirectory(root)) continue
        Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { home ->
                // macOS installs nest the actual home one level down.
                homes.add(if (Files.isDirectory(home.resolve("Contents/Home"))) home.resolve("Contents/Home") else home)
            }
        }
    }
    return homes.distinct()
}

/** Newest discovered JDK that satisfies [required]; discovery is a cheap file read per candidate. */
internal fun pickJavaCommand(required: Int, homes: List<Path>): Path? =
    homes.mapNotNull { home -> readJavaMajorFromRelease(home)?.let { it to home } }
        .filter { (major, _) -> major >= required }
        .maxByOrNull { (major, _) -> major }
        ?.second
        ?.resolve(if (System.getProperty("os.name")?.startsWith("Windows") == true) "bin/java.exe" else "bin/java")
        ?.takeIf { Files.isRegularFile(it) }

internal fun deleteRecursively(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
    }
}
