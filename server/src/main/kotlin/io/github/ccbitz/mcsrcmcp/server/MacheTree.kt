package io.github.ccbitz.mcsrcmcp.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MacheTreeException(message: String) : IllegalStateException(message)

/** A dev bundle's `mache` entry: where mache is published and its coordinates. */
@Serializable
data class MacheRef(val url: String, val coordinates: List<String>)

@Serializable
private data class MacheDependency(val group: String, val name: String, val version: String, val classifier: String? = null)

@Serializable
private data class MacheRepository(val url: String)

@Serializable
private data class MacheMeta(
    val macheVersion: String,
    val dependencies: Map<String, List<MacheDependency>> = emptyMap(),
    val repositories: List<MacheRepository> = emptyList(),
    val decompilerArgs: List<String> = emptyList(),
    val remapperArgs: List<String> = emptyList(),
)

private val macheJson = Json { ignoreUnknownKeys = true }

/** Runs a child process to completion; anything but exit code 0 throws. Tests swap this for a fake. */
fun interface ChildRunner {

    suspend fun run(command: List<String>, workDir: Path, timeout: Duration)

}

/** [ChildRunner] over real processes, output drained and kept for the error. */
object ProcessChildRunner : ChildRunner {

    override suspend fun run(command: List<String>, workDir: Path, timeout: Duration) {
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start()
        }
        val output = CompletableDeferred<String>()
        // Drained eagerly: a full stdout pipe would deadlock the child while we poll.
        Thread {
            output.complete(runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault(""))
        }.apply { isDaemon = true }.start()

        // A cancelled wait - shutdown, clear_cache - kills the child too: the whole-jar decompile is a
        // 4GB JVM that would otherwise run on with nobody left to read its output.
        val exited = try {
            withTimeoutOrNull(timeout.toMillis()) {
                while (process.isAlive) delay(250)
                true
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        if (exited != true) {
            throw MacheTreeException("${command.first()} timed out after ${timeout.toMinutes()} minutes")
        }
        if (process.exitValue() != 0) {
            val tail = output.await().lines().takeLast(25).joinToString("\n")
            throw MacheTreeException("${command.take(3).joinToString(" ")} exited with ${process.exitValue()}:\n$tail")
        }
    }

}

/**
 * One Minecraft version's sources as mache produces them - unpicked, decompiled whole-jar with mache's
 * Vineflower settings, mache's own patches applied - each with the Vineflower tokens that survived
 * those patches. Every paperweight fork's source patches are written against exactly this text, so
 * one tree serves Paper, Folia and Purpur and every build of each for that version.
 *
 * Stored as one zip of `<internal/Name>.java` + `<internal/Name>.tokens` entries: a few thousand small
 * files as one, which the eviction pass can size and delete without walking them.
 */
class MacheTree(val zip: Path) {

    fun classes(): List<String> =
        ZipFile(zip.toFile()).use { file -> file.entries().asSequence().map { it.name }.filter { it.endsWith(".java") }.map { it.removeSuffix(".java") }.toList() }

    fun read(internalName: String): MacheSource? =
        ZipFile(zip.toFile()).use { file -> readFrom(file, internalName) }

    /** Runs [block] with every class, reading the zip once. */
    fun forEach(block: (internalName: String, source: MacheSource) -> Unit) {
        ZipFile(zip.toFile()).use { file ->
            for (entry in file.entries().asSequence().filter { it.name.endsWith(".java") }.toList()) {
                val name = entry.name.removeSuffix(".java")
                block(name, readFrom(file, name) ?: continue)
            }
        }
    }

    private fun readFrom(file: ZipFile, internalName: String): MacheSource? {
        fun text(name: String) = file.getEntry(name)?.let { entry -> file.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } }

        val text = text("$internalName.java") ?: return null
        val tokens = text("$internalName.tokens")?.let(::parseSidecarTokens) ?: emptyList()
        val uncovered = text("$internalName.uncovered")?.let(::parseLineSet) ?: emptySet()
        return MacheSource(text, tokens, uncovered)
    }

}

/**
 * One class of a [MacheTree]: its text, the Vineflower tokens that survived mache's patches, and the
 * lines those patches wrote, which have no tokens and need resolving from bytecode instead.
 */
data class MacheSource(val text: String, val tokens: List<SourceToken>, val uncoveredLines: Set<Int>)

internal fun formatLineSet(lines: Collection<Int>): String = lines.sorted().joinToString(",")

internal fun parseLineSet(text: String): Set<Int> =
    text.split(',').mapNotNullTo(HashSet()) { it.trim().toIntOrNull() }

/**
 * Builds [MacheTree]s under `derived/mache/<mcVersion>/<macheVersion>/tree.zip`, the way paperweight
 * userdev's setup does - codebook, then the whole-jar decompile, then mache's patches - with two
 * differences: the decompile also records tokens (the `decompiler` sidecar), and a mache patch that
 * doesn't apply drops that one class from the tree instead of failing the whole build, so the class
 * falls back to a plain decompile.
 *
 * Only unobfuscated versions are supported: their codebook step is unpick alone. Older mache versions
 * also remap with Mojang mappings, Parchment parameter names and a remapper jar; for those this
 * returns null and the fork keeps serving decompiled bytecode.
 */
class MacheTreeBuilder(
    private val cacheRoot: Path,
    private val artifacts: MavenArtifacts,
    private val children: ChildRunner = ProcessChildRunner,
) {

    suspend fun treeFor(mcVersion: String, mache: MacheRef, serverBundlerJar: Path): MacheTree? {
        val coordinate = mache.coordinates.singleOrNull()?.let { MavenCoordinate.parse(it).copy(extension = "zip") }
            ?: throw MacheTreeException("dev bundle names ${mache.coordinates.size} mache artifacts, expected one")
        val treeDir = cacheRoot.resolve("derived").resolve(MACHE_DIR).resolve(mcVersion).resolve(coordinate.version)
        val treeZip = treeDir.resolve(TREE_FILE)

        return LOCKS.computeIfAbsent(treeDir.toAbsolutePath().normalize()) { Mutex() }.withLock {
            if (Files.isRegularFile(treeZip)) return@withLock MacheTree(treeZip)

            val macheZip = Files.readAllBytes(artifacts.fetch(coordinate, listOf(mache.url)))
            val meta = macheJson.decodeFromString<MacheMeta>(
                PaperclipPatcher.readZipEntry(macheZip, "mache.json")?.toString(Charsets.UTF_8)
                    ?: throw MacheTreeException("mache ${coordinate.version} has no mache.json"),
            )
            unsupportedReason(meta)?.let { reason ->
                System.err.println("mcsrc-mcp: mache ${meta.macheVersion} is not supported for source trees ($reason); serving decompiled bytecode")
                return@withLock null
            }

            withContext(Dispatchers.IO) { build(meta, macheZip, serverBundlerJar, treeDir) }
            MacheTree(treeZip)
        }
    }

    private fun unsupportedReason(meta: MacheMeta): String? {
        val extra = meta.dependencies.keys - SUPPORTED_DEPENDENCIES
        if (extra.isNotEmpty()) return "needs ${extra.joinToString()}"
        for (key in listOf("codebook", "decompiler")) {
            if (meta.dependencies[key].isNullOrEmpty()) return "names no $key"
        }
        val placeholders = meta.remapperArgs.flatMap { arg -> PLACEHOLDER.findAll(arg).map { it.groupValues[1] }.toList() }.toSet()
        val unknown = placeholders - SUPPORTED_PLACEHOLDERS
        if (unknown.isNotEmpty()) return "codebook needs ${unknown.joinToString { "{$it}" }}"
        return null
    }

    private suspend fun build(meta: MacheMeta, macheZip: ByteArray, serverBundlerJar: Path, treeDir: Path) {
        val repositories = meta.repositories.map { it.url }
        suspend fun fetch(dependency: MacheDependency, extension: String = "jar"): Path =
            artifacts.fetch(MavenCoordinate(dependency.group, dependency.name, dependency.version, dependency.classifier, extension), repositories)

        val codebook = fetch(meta.dependencies.getValue("codebook").first())
        val vineflower = fetch(meta.dependencies.getValue("decompiler").first())
        // Unpick definitions are published as a zip (no jar), which Gradle finds from module metadata.
        val constants = meta.dependencies["constants"]?.firstOrNull()?.let { dependency ->
            runCatching { fetch(dependency) }.getOrElse { fetch(dependency, "zip") }
        }

        Files.createDirectories(treeDir)
        val work = Files.createTempDirectory(treeDir, "work-")
        try {
            val (serverJar, libraries) = extractBundler(serverBundlerJar, work.resolve("bundler"))
            val unpicked = work.resolve("unpicked.jar")
            runCodebook(meta, codebook, constants, serverJar, libraries, unpicked, work)

            val decompiled = work.resolve("decompiled")
            runDecompiler(meta, vineflower, unpicked, libraries, decompiled, work)

            writeTree(macheZip, decompiled, treeDir.resolve(TREE_FILE), work)
        } finally {
            deleteRecursively(work)
        }
    }

    // The published server jar is Mojang's bundler: the real server jar and its libraries are nested
    // inside it, and both codebook and the decompiler need them as files.
    private fun extractBundler(bundlerJar: Path, dir: Path): Pair<Path, List<Path>> {
        Files.createDirectories(dir)
        val bytes = Files.readAllBytes(bundlerJar)
        val serverJar = dir.resolve("server.jar")
        Files.write(serverJar, PaperclipPatcher.extractVanillaServerJar(bytes))

        val libraries = mutableListOf<Path>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.startsWith("META-INF/libraries/") && entry.name.endsWith(".jar")) {
                    val target = dir.resolve(entry.name.removePrefix("META-INF/"))
                    Files.createDirectories(target.parent)
                    Files.write(target, zip.readBytes())
                    libraries.add(target)
                }
                entry = zip.nextEntry
            }
        }
        return serverJar to libraries.sorted()
    }

    // Paths go in relative to the work directory: codebook splits --input-classpath on ':', which is
    // also the separator after a Windows drive letter.
    private suspend fun runCodebook(
        meta: MacheMeta,
        codebook: Path,
        constants: Path?,
        serverJar: Path,
        libraries: List<Path>,
        output: Path,
        work: Path,
    ) {
        val tempDir = Files.createDirectories(work.resolve("codebook-temp"))
        fun rel(path: Path) = work.relativize(path).toString().replace('\\', '/')

        val args = meta.remapperArgs.map { arg ->
            arg.replace(PLACEHOLDER) { match ->
                when (match.groupValues[1]) {
                    "tempDir" -> rel(tempDir)
                    "constantsFile" -> rel(copyInto(work, constants ?: throw MacheTreeException("codebook wants {constantsFile}, but mache names no constants")))
                    "output" -> rel(output)
                    "input" -> rel(serverJar)
                    "inputClasspath" -> libraries.joinToString(":") { rel(it) }
                    else -> throw MacheTreeException("unsupported codebook placeholder ${match.value}")
                }
            }
        }
        children.run(listOf(javaCommand(), "-Xmx2G", "-jar", codebook.toString()) + args, work, CODEBOOK_TIMEOUT)
        if (!Files.isRegularFile(output)) throw MacheTreeException("codebook wrote no output jar")
    }

    // A blob-store path would have to be relativized across the cache; a copy beside the work keeps
    // codebook's arguments short and colon-free.
    private fun copyInto(work: Path, file: Path): Path {
        val target = work.resolve(file.fileName.toString() + ".zip")
        if (!Files.exists(target)) Files.copy(file, target)
        return target
    }

    private suspend fun runDecompiler(
        meta: MacheMeta,
        vineflower: Path,
        input: Path,
        libraries: List<Path>,
        out: Path,
        work: Path,
    ) {
        val sidecar = work.resolve("decompiler.jar")
        val bytes = MacheTreeBuilder::class.java.getResourceAsStream("/decompiler.jar")?.use { it.readBytes() }
            ?: throw MacheTreeException("decompiler.jar is missing from the server's resources - build with the decompiler module")
        Files.write(sidecar, bytes)
        val librariesFile = work.resolve("libraries.txt")
        Files.write(librariesFile, libraries.map { it.toAbsolutePath().toString() })

        // Exactly these two jars: mache decompiles with --include-classpath=true, so anything else on
        // the classpath would become decompile context and change the text Paper's patches expect.
        val classpath = listOf(vineflower, sidecar).joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }
        val command = listOf(javaCommand(), "-Xmx4G", "-cp", classpath, DECOMPILER_MAIN, out.toString(), input.toString(), librariesFile.toString()) +
            meta.decompilerArgs
        children.run(command, work, DECOMPILE_TIMEOUT)
    }

    private fun writeTree(macheZip: ByteArray, decompiled: Path, target: Path, work: Path) {
        val patches = HashMap<String, String>()
        ZipInputStream(macheZip.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("patches/") && entry.name.endsWith(".java.patch")) {
                    patches[entry.name.removePrefix("patches/").removeSuffix(".java.patch")] = zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }

        val sources = Files.walk(decompiled).use { stream ->
            stream.filter { it.toString().endsWith(".java") }.toList()
        }
        if (sources.isEmpty()) throw MacheTreeException("the decompiler produced no sources")

        var failed = 0
        val tmp = work.resolve("tree.zip.tmp")
        ZipOutputStream(Files.newOutputStream(tmp)).use { out ->
            for (source in sources.sorted()) {
                val internalName = decompiled.relativize(source).toString().replace('\\', '/').removeSuffix(".java")
                var text = Files.readString(source)
                val tokensFile = source.resolveSibling(source.fileName.toString().removeSuffix(".java") + ".tokens")
                var tokens = if (Files.exists(tokensFile)) parseSidecarTokens(Files.readString(tokensFile)) else emptyList()
                // No tokens file at all means this Vineflower didn't take the token visitor: every line
                // is then left to bytecode resolution, rather than looking resolved and holding nothing.
                var uncovered: Set<Int> = if (Files.exists(tokensFile)) emptySet() else (1..lineCount(text)).toSet()

                val patch = patches[internalName]
                if (patch != null) {
                    // Left out of the tree entirely: the fork's patch for it was written against the
                    // mache-patched text, so it would fail too, and the class decompiles instead.
                    val patched = try {
                        SourcePatch.apply(text, patch)
                    } catch (e: PatchFailedException) {
                        failed++
                        continue
                    }
                    val carried = carryTokens(text, tokens, patched.text, patched.originFor)
                    uncovered = carried.uncoveredLines + carryLines(uncovered, patched.originFor)
                    tokens = carried.tokens
                    text = patched.text
                }

                fun entry(name: String, content: String) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                }
                entry("$internalName.java", text)
                entry("$internalName.tokens", formatSidecarTokens(tokens))
                if (uncovered.isNotEmpty()) entry("$internalName.uncovered", formatLineSet(uncovered))
            }
        }
        if (failed > 0) System.err.println("mcsrc-mcp: $failed mache patches didn't apply; those classes will be decompiled instead")
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun javaCommand(): String {
        val home = Path.of(System.getProperty("java.home"))
        val exe = if (File.separatorChar == '\\') "java.exe" else "java"
        return home.resolve("bin").resolve(exe).toString()
    }

    companion object {

        const val MACHE_DIR = "mache"
        const val TREE_FILE = "tree.zip"
        const val DECOMPILER_MAIN = "io.github.ccbitz.mcsrcmcp.decompiler.TreeDecompiler"

        private val SUPPORTED_DEPENDENCIES = setOf("codebook", "constants", "decompiler")
        private val SUPPORTED_PLACEHOLDERS = setOf("tempDir", "constantsFile", "output", "input", "inputClasspath")
        private val PLACEHOLDER = Regex("""\{(\w+)}""")
        private val CODEBOOK_TIMEOUT = Duration.ofMinutes(10)
        private val DECOMPILE_TIMEOUT = Duration.ofMinutes(30)

        // Per tree directory, process-wide: two forks preparing the same Minecraft version at once
        // must not both run a whole-jar decompile into it.
        private val LOCKS = ConcurrentHashMap<Path, Mutex>()

    }

}
