package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.AmbiguousVersionException
import io.github.ccbitz.mcsrcmcp.cache.BlobStore
import io.github.ccbitz.mcsrcmcp.cache.CacheRoot
import io.github.ccbitz.mcsrcmcp.cache.UnknownVersionException
import io.github.ccbitz.mcsrcmcp.cache.VersionResolver
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.regex.PatternSyntaxException

const val SERVER_NAME = "mcsrc-mcp"
const val SERVER_VERSION = "0.1.0"

// Game-data values arrive from the bridge as compact one-line JSON; agents page them by line, so
// they're re-encoded indented before serving.
private val prettyPrinter = Json { prettyPrint = true }

// The server instructions stay this short on purpose: they ride along in every session's
// context whether these tools get used or not, so they carry only what the server is for plus
// the pointer to get_instructions. The heavy guidance a model needs to drive the tools well
// lives behind that tool call (AGENT_GUIDE below) and costs nothing until asked for.
const val AGENT_INSTRUCTIONS = """
mcsrc-mcp browses decompiled Minecraft Java source; Mojang mappings where available.

Call get_instructions before using any other tool from this server - it takes no arguments and
returns the guidance those tools assume: version aliases and defaults, which search tool to
reach for when, and the citation link formats for classes and assets.
"""

// What get_instructions returns. Anything true of every tool (version aliases, dotted class
// names, cold-version auto-prepare) belongs here rather than in each tool's own description: the
// guide is fetched once, tool schemas ride along on every tools/list.
const val AGENT_GUIDE = """
Version: every tool takes one - an id, or an alias (latest, latest-release, latest-snapshot, or an
exact/prefix id). If the user didn't say, infer from project files (build.gradle(.kts),
fabric.mod.json, mods.toml, pom.xml); else "latest-release"; ask at most once, only if ambiguous.
Any tool prepares a cold version itself, so the first call against one can take a while;
prepare_version only front-loads that wait and returns as soon as the version is queryable.
Classes are dotted: net.minecraft.world.level.Level.

Searching: name search first - search_classes (code), search_asset_files (assets). Use full-text
search_code/search_assets only when that fails or you lack a name (e.g. a constant or string
literal); search_code is heavy - preparing a version starts decompiling+indexing the jar in the
background, and the first search_code call waits out whatever of that remains (minutes on a fresh
version) and uses more tokens. When possible, scope it: feed find_references' callerClass values into search_code's
classes param. search_code is literal text/regex on decompiled source; find_references reads
bytecode (CHECKCAST, INVOKE*, field refs), so it catches references that never spell the simple
name - super(...), inherited/overridden methods, lambda/method-ref bodies. Empty search_code
output in a class find_references named proves nothing - get_class_source it and read around the
reported callerMember.

Navigating known code: get_class_outline for a class's members (no decompile), find_references
for "where is X used/called" - even within its own declaring class. get_class_source only to
read actual logic, paged with start_line/max_lines, and find_declaration to jump from a symbol
in it to where that symbol is declared - don't decompile another class just to follow one call.
If you already know the class, locating a member via search_code's classes param or by
decompiling it whole is the wrong call.

find_declaration is go-to-declaration: give it a get_class_source line (or a get_bytecode line as
bytecode_line) and it says what the symbols there point at and where each is declared. Owners come
from the decompiler's own resolution, so two same-named calls to different classes on one line stay
apart and an inherited member resolves to its real declaring class. Omit symbol/column to list
every symbol on the line and pick from that.

Mixins: no tool models them; inspect with get_bytecode.

get_report serves JSON reports computed by Mojang's own data generator: registries (every
registered block/item/entity/sound, with ids), blocks (every blockstate), commands (the full command
tree), packets, datapack layout, default item components (minecraft/components/item/<id>), biome
parameters. First call per version runs the generator (~a minute) and caches. These are the ground
truth behind code-only registries - prefer them over reading Blocks.java/Items.java registration
lists. They're generated locally, so cite them as report name + line; there is no public URL.

Vanilla worldgen JSON (biomes, configured/placed features, structures, noise settings, dimensions)
ships in the jar under data/minecraft/worldgen/ - browse it with list_paths (one level at a time,
from the jar root), search_asset_files, or list_assets, and read it with get_asset, not get_report.
Other data files (recipes, loot tables, tags, advancements) live under data/ the same way.
list_reports reads what get_report can serve straight from the version's data-generator classes -
instant, no generation - and switches to the real generated tree once one exists.

get_game_data reads a static field straight out of the booted game (sidecar JVM; first query per
version ~10s, then resident, results cached): composting chances
(net.minecraft.world.level.block.ComposterBlock.COMPOSTABLES), villager trades, entity attributes,
anything code computes at boot. Registry objects render as their ids ('minecraft:bone_meal');
output is depth-capped descriptive JSON - read it for values, not for exact types.

clear_cache: only when the user explicitly asks to clear/reset/invalidate the cache - never
speculatively or to work around odd output.

get_class_source, get_bytecode and get_asset prefix every line with its absolute line number and a
tab. Cite those numbers directly - never re-count - and strip the prefix when quoting the code.

Cite decompiled classes - and only classes - as https://mcsrc.dev/2/<version>/<slash/class/path>
(no extension) + #L<line> or #L<start>-<end>, e.g.
https://mcsrc.dev/2/1.21.4/net/minecraft/nbt/Tag#L23-45 . One "L", before the start line. Always
/2/, never /1/.

mcsrc.dev does NOT serve assets - it hosts decompiled source only. Never point an asset path at it,
with or without a line anchor, and don't assume it will resolve one anyway. Cite every asset
(textures, models, lang, shaders, .mcmeta, data files like recipes/loot tables/tags/worldgen -
anything get_asset or list_assets returns) as
https://github.com/InventivetalentDev/minecraft-assets/blob/<version>/<path>, where ranges are
double-L: #L40-L53. The two syntaxes are not interchangeable - mcsrc.dev's single-L range 404s on
GitHub and GitHub's double-L range 404s on mcsrc.dev.

You should always link rather than paste a bare URL -
[Tag](https://mcsrc.dev/2/1.21.4/net/minecraft/nbt/Tag#L23-45) - with the class or file name as the
link text; plain URLs only where markdown isn't rendered.
"""

// prepare_version blocks on the version becoming ready rather than returning a single snapshot,
// so a caller doesn't have to re-invoke the tool on every tick. Bounded rather than unbounded:
// a cold, large version can take a long time to decompile+index, and we'd rather fall back to
// the old poll-by-calling-again contract than risk sitting past a client/transport's own timeout.
private val PREPARE_POLL_INTERVAL: Duration = Duration.ofSeconds(2)
private val PREPARE_POLL_TIMEOUT: Duration = Duration.ofMinutes(25)

// Bump if Vineflower's version or decompile options ever become configurable/change, so old
// cached source under a stale config version is never read back as if it were current.
internal const val SOURCE_CACHE_CONFIG_VERSION = "v1"

// A model guessing at the schema sometimes reaches for "className" or "targetClass" instead of
// the tool's documented "class" property - "targetClass" in particular echoes find_references'
// own result field of that name. Tolerate those guesses at the parsing layer rather than
// bouncing the call over a naming preference - cheaper than getting every model to read the
// schema. JSON-encoding a whole result struct as TextContent buries real newlines as literal
// "\n" inside one JSON string value; clients that render MCP tool output raw rather than
// reformatting it then show an illegible escaped blob instead of readable text. Plain text -
// a short label/range line, then the real content - reads correctly everywhere and is shorter
// besides, since JSON-escaping a multi-line string is never smaller than the string.
//
// Each line is prefixed with its absolute line number and a tab, cat -n style, matching what a
// harness's own file-read tool gives a model. The instructions ask for #L<line> citations, so
// numbering the lines is what makes those anchors trustworthy - a reader that has to count
// lines itself either miscounts or loses confidence and drops the anchor.
internal fun formatContentResult(label: String, content: String, startLine: Int, totalLines: Int, truncated: Boolean): String {
    if (content.isEmpty()) {
        return label
    }

    val lines = content.lines()
    val endLine = startLine + lines.size - 1
    val rangeNote = when {
        truncated -> " (lines $startLine-$endLine of $totalLines, truncated - call again with start_line=${endLine + 1} to continue)"
        totalLines > lines.size -> " (lines $startLine-$endLine of $totalLines)"
        else -> ""
    }

    val numbered = lines.withIndex().joinToString("\n") { (offset, line) -> "${startLine + offset}\t$line" }
    return "$label$rangeNote\n\n$numbered"
}

// 'exclude' is always a regex and 'query' is one on demand, so a caller can hand either an
// unescaped Java fragment like "get(" - which is a syntax error, not a no-match. Say which way out
// applies rather than letting the exception escape as a generic tool failure.
internal fun invalidRegexMessage(e: PatternSyntaxException): String =
    "Invalid regex: ${e.message?.lineSequence()?.firstOrNull() ?: e.description}. " +
        "Escape regex metacharacters, or set regex=false to match the query literally " +
        "(note: 'exclude' is always a regex)."

internal fun CallToolRequest.classArg(): String? =
    arguments?.get("class")?.jsonPrimitive?.content
        ?: arguments?.get("className")?.jsonPrimitive?.content
        ?: arguments?.get("targetClass")?.jsonPrimitive?.content

// search_code's "classes" and search_assets' "paths" are documented as arrays, but a model that
// has just read a single bare path/class name off another tool's result (e.g. find_references'
// own callerClass field) sometimes passes that one string straight through, and sometimes
// reaches for the singular form of the key ("class"/"path") to match. kotlinx.serialization's
// .jsonArray throws ClassCastException on a JsonLiteral rather than returning null, so left
// unhandled either guess crashed the whole call instead of degrading to a one-element list.
// Tries each name in order, first hit wins - matches classArg's precedence, not a merge.
internal fun CallToolRequest.stringListArg(vararg names: String): List<String>? {
    for (name in names) {
        val value = arguments?.get(name) ?: continue
        return (value as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: value.jsonPrimitive.contentOrNull?.let { listOf(it) }
    }
    return null
}

// Shared by prepare_version's own handler and every other tool's inline auto-prepare (via
// resolveWorkspace/resolveWorkspaces) so both report progress identically.
private suspend fun ClientConnection.sendPrepareProgress(progressToken: RequestId?, versionLabel: String, percent: Int) {
    if (progressToken == null) return
    notification(
        ProgressNotification(
            ProgressNotificationParams(
                progressToken = progressToken,
                progress = percent.toDouble(),
                total = 100.0,
                message = "Preparing $versionLabel...",
            ),
        ),
    )
}

// Datagen and the bridge have no percent to stream - the child JVM is opaque - so this is a
// heartbeat with elapsed time in the message. The percent is elapsed-derived and capped, purely so
// clients that render a progress bar don't sit at 0% for a minute; the message carries the real
// information.
private suspend fun ClientConnection.sendElapsedProgress(progressToken: RequestId?, message: String, elapsedSeconds: Int) {
    if (progressToken == null) return
    notification(
        ProgressNotification(
            ProgressNotificationParams(
                progressToken = progressToken,
                progress = (elapsedSeconds * 2).coerceAtMost(99).toDouble(),
                total = 100.0,
                message = "$message (${elapsedSeconds}s)...",
            ),
        ),
    )
}

internal sealed interface ResolvedWorkspace {
    data class Ok(val workspace: VersionWorkspace, val versionId: String) : ResolvedWorkspace
    data class Failed(val result: CallToolResult) : ResolvedWorkspace
}

// Shared by get_report: everything between "a warm workspace" and "a completed reports
// directory" - cache-dir check, data-generator presence check, version detail + client jar
// resolution, and the lazy single-flight datagen run itself.
private sealed interface ReportsAccess {
    data class Ready(val reportsDir: Path) : ReportsAccess
    data class Failed(val result: CallToolResult) : ReportsAccess
}

// The version detail + client jar path a subprocess against the game needs, resolved once for
// both datagen (via prepareReports) and the bridge (get_game_data).
private sealed interface GameArtifacts {
    data class Ready(val detail: io.github.ccbitz.mcsrcmcp.cache.VersionDetail, val clientJarPath: Path) : GameArtifacts
    data class Failed(val result: CallToolResult) : GameArtifacts
}

private suspend fun resolveGameArtifacts(
    metadata: VersionMetadataCache,
    versionQuery: String,
    resolved: ResolvedWorkspace.Ok,
    blobStore: BlobStore,
): GameArtifacts = try {
    val manifest = metadata.manifest()
    val version = VersionResolver.resolve(versionQuery, manifest.versions)
    val detail = metadata.detail(version)
    val clientJarPath = blobStore.pathIfPresent(detail.downloads.client.sha1)
        ?: return GameArtifacts.Failed(
            CallToolResult(
                content = listOf(TextContent("Version ${resolved.versionId} is warm but its client jar is missing from the blob store - try clear_cache and again.")),
                isError = true,
            ),
        )
    GameArtifacts.Ready(detail, clientJarPath)
} catch (e: Exception) {
    GameArtifacts.Failed(CallToolResult(content = listOf(TextContent("Failed to resolve version metadata: ${e.message}")), isError = true))
}

private suspend fun prepareReports(
    metadata: VersionMetadataCache,
    versionQuery: String,
    resolved: ResolvedWorkspace.Ok,
    reportGenerator: ReportGenerator,
    blobStore: BlobStore,
    onProgress: suspend (elapsedSeconds: Int) -> Unit,
): ReportsAccess {
    val cacheDir = resolved.workspace.cacheDir
        ?: return ReportsAccess.Failed(
            CallToolResult(
                content = listOf(TextContent("Version ${resolved.versionId} has no on-disk cache configured for this server, so reports cannot be generated or stored.")),
                isError = true,
            ),
        )
    // The generator itself is the gate here, same as search_code's index check: a version whose
    // jar has no net.minecraft.data.Main (everything before 1.13, and some time after) fails fast
    // instead of spawning a doomed JVM.
    if (!resolved.workspace.indexData.classes().containsKey("net/minecraft/data/Main")) {
        return ReportsAccess.Failed(
            CallToolResult(
                content = listOf(TextContent("Version ${resolved.versionId} has no data generator in its jar, so no reports are available.")),
                isError = true,
            ),
        )
    }

    return when (val artifacts = resolveGameArtifacts(metadata, versionQuery, resolved, blobStore)) {
        is GameArtifacts.Failed -> ReportsAccess.Failed(artifacts.result)
        is GameArtifacts.Ready -> try {
            val reportsDir = reportGenerator.ensureReports(resolved.versionId, artifacts.detail, artifacts.clientJarPath, cacheDir, onProgress)
            ReportsAccess.Ready(reportsDir)
        } catch (e: DatagenFailedException) {
            ReportsAccess.Failed(CallToolResult(content = listOf(TextContent(e.message ?: "datagen failed")), isError = true))
        }
    }
}

private sealed interface ResolvedWorkspaces {
    data class Ok(
        val workspaceA: VersionWorkspace,
        val versionAId: String,
        val workspaceB: VersionWorkspace,
        val versionBId: String,
    ) : ResolvedWorkspaces

    data class Failed(val result: CallToolResult) : ResolvedWorkspaces
}

private suspend fun resolveWorkspaces(
    metadata: VersionMetadataCache,
    eulaGate: EulaGate,
    workspaceCache: WorkspaceCache,
    versionPreparer: VersionPreparer,
    versionAQuery: String,
    versionBQuery: String,
    onProgress: suspend (versionLabel: String, percent: Int) -> Unit = { _, _ -> },
): ResolvedWorkspaces {
    when (val a = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionAQuery) { onProgress(versionAQuery, it) }) {
        is ResolvedWorkspace.Failed -> return ResolvedWorkspaces.Failed(a.result)
        is ResolvedWorkspace.Ok -> {
            when (val b = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionBQuery) { onProgress(versionBQuery, it) }) {
                is ResolvedWorkspace.Failed -> return ResolvedWorkspaces.Failed(b.result)
                is ResolvedWorkspace.Ok -> return ResolvedWorkspaces.Ok(
                    a.workspace,
                    a.versionId,
                    b.workspace,
                    b.versionId,
                )
            }
        }
    }
}

/**
 * Resolves [versionQuery] to a warm, ready-to-read [VersionWorkspace]. If it isn't warm yet,
 * prepares it inline (via [pollUntilReady]) instead of bouncing the caller back to
 * prepare_version - most tools funnel through here, so this is the one place that needs to
 * know how to warm a version, and every read tool gets "just works on a cold version" for free.
 * [onProgress] is called with each distinct percent while preparing; a caller with no client to
 * stream progress to (e.g. a test) can leave it as a no-op.
 */
internal suspend fun resolveWorkspace(
    metadata: VersionMetadataCache,
    eulaGate: EulaGate,
    workspaceCache: WorkspaceCache,
    versionPreparer: VersionPreparer,
    versionQuery: String,
    onProgress: suspend (percent: Int) -> Unit = {},
): ResolvedWorkspace {
    try {
        eulaGate.requireAccepted()
    } catch (e: EulaNotAcceptedException) {
        return ResolvedWorkspace.Failed(
            CallToolResult(content = listOf(TextContent(e.message ?: "EULA not accepted")), isError = true),
        )
    }

    return try {
        val manifest = metadata.manifest()
        val version = VersionResolver.resolve(versionQuery, manifest.versions)

        workspaceCache.get(version.id)?.let { return ResolvedWorkspace.Ok(it, version.id) }

        val detail = metadata.detail(version)
        val result = pollUntilReady(
            pollInterval = PREPARE_POLL_INTERVAL,
            pollTimeout = PREPARE_POLL_TIMEOUT,
            onProgress = onProgress,
        ) { versionPreparer.prepare(version, detail) }

        when (result) {
            is PrepareVersionResult.Ready -> {
                val workspace = workspaceCache.get(version.id)
                    // prepare() just reported Ready for this exact version.id, so this can only
                    // happen if something evicted it in the instant between that and this read.
                    ?: return ResolvedWorkspace.Failed(
                        CallToolResult(
                            content = listOf(TextContent("Version ${version.id} was prepared but is no longer warm - try again.")),
                            isError = true,
                        ),
                    )
                ResolvedWorkspace.Ok(workspace, version.id)
            }
            is PrepareVersionResult.Preparing -> ResolvedWorkspace.Failed(
                CallToolResult(
                    content = listOf(TextContent(
                        "Version ${result.versionId} is still being prepared (${result.percent}%) after " +
                            "${PREPARE_POLL_TIMEOUT.toMinutes()} minutes. Call prepare_version (or retry this tool) " +
                            "to keep waiting.",
                    )),
                    isError = true,
                ),
            )
        }
    } catch (e: AmbiguousVersionException) {
        ResolvedWorkspace.Failed(
            CallToolResult(
                content = listOf(TextContent("Ambiguous version '$versionQuery'. Candidates: ${e.candidates.joinToString()}")),
                isError = true,
            ),
        )
    } catch (e: UnknownVersionException) {
        ResolvedWorkspace.Failed(
            CallToolResult(content = listOf(TextContent("Unknown version '$versionQuery'.")), isError = true),
        )
    }
}

fun buildServer(
    cacheRoot: Path = CacheRoot.resolve(),
    fetcher: BlobFetcher = HttpBlobFetcher(),
    eulaGate: EulaGate = EulaGate(cacheRoot.resolve("eula-accepted.txt")),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): Server {
    CacheEviction.evict(cacheRoot, resolveCacheTtl(), resolveCacheMaxSizeBytes())

    val blobStore = BlobStore(cacheRoot.resolve("blobs"))
    val workspaceCache = WorkspaceCache()
    val versionPreparer = VersionPreparer(workspaceCache, VersionWorkspaceBuilder(blobStore, fetcher, cacheRoot), scope)
    val reportGenerator = ReportGenerator(blobStore, fetcher, cacheRoot, scope)
    val gameBridge = GameBridge(blobStore, fetcher, cacheRoot)
    val metadata = VersionMetadataCache(fetcher, cacheRoot)

    val server = Server(
        serverInfo = Implementation(
            name = SERVER_NAME,
            version = SERVER_VERSION,
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructions = AGENT_INSTRUCTIONS.trim(),
    )

    server.registerTools(metadata, eulaGate, workspaceCache, versionPreparer, reportGenerator, gameBridge, cacheRoot, blobStore)
    return server
}

// Every tool draws from the same handful of properties, and every word of every description is
// re-sent on each tools/list - so the wording lives here once instead of being pasted per tool and
// drifting. Keep these terse; the formats they'd otherwise have to spell out are in AGENT_GUIDE.
private fun JsonObjectBuilder.stringProp(name: String, description: String) = putJsonObject(name) {
    put("type", "string")
    put("description", description)
}

private fun JsonObjectBuilder.intProp(name: String, description: String) = putJsonObject(name) {
    put("type", "integer")
    put("description", description)
}

private fun JsonObjectBuilder.boolProp(name: String, description: String) = putJsonObject(name) {
    put("type", "boolean")
    put("description", description)
}

private fun JsonObjectBuilder.versionProp() = stringProp("version", "Version id or alias")
private fun JsonObjectBuilder.classProp() = stringProp("class", "Dotted class name")
private fun JsonObjectBuilder.limitProp(default: Int) = intProp("limit", "Max results (default $default)")

private fun JsonObjectBuilder.lineRangeProps() {
    intProp("start_line", "First line, 1-indexed (default 1)")
    intProp("max_lines", "Max lines (default 1500)")
}

// Shared by search_code and search_assets. Both exist to spare a round trip: exclude is the
// in-pass `| rg -v`, and exact_count answers "is my query too broad" without returning the hits.
private fun JsonObjectBuilder.searchFilterProps() {
    stringProp("exclude", "Regex (always, even if regex=false): drop hits whose path OR line matches it, like piping through 'rg -v'. e.g. 'Gl|Vk' to shed backend noise")
    boolProp("exact_count", "Count every hit instead of stopping at limit - slower, use to gauge a broad query (default false)")
}

// The oneOf mirrors stringListArg's tolerance: a model that has just read one bare class/path off
// another tool's result tends to pass that string straight through instead of wrapping it.
private fun JsonObjectBuilder.stringOrArrayProp(name: String, description: String) = putJsonObject(name) {
    putJsonArray("oneOf") {
        addJsonObject { put("type", "string") }
        addJsonObject {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
        }
    }
    put("description", description)
}

private fun Server.registerTools(
    metadata: VersionMetadataCache,
    eulaGate: EulaGate,
    workspaceCache: WorkspaceCache,
    versionPreparer: VersionPreparer,
    reportGenerator: ReportGenerator,
    gameBridge: GameBridge,
    cacheRoot: Path,
    blobStore: BlobStore,
) {
    // Like list_versions below, deliberately NOT EULA-gated: it serves no Minecraft content, only
    // this server's own guidance text, so gating it would only manage to hide the EULA path
    // behind instructions the model can no longer read.
    addTool(
        name = "get_instructions",
        description = "Read this before using any other tool from this server. Returns the " +
            "guidance those tools assume: version aliases and defaults, which search tool to " +
            "reach for when, and the citation link formats for classes and assets. No arguments.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { _ ->
        CallToolResult(content = listOf(TextContent(AGENT_GUIDE.trim())))
    }

    // list_versions is metadata-only (which versions exist) - it never touches Minecraft's own
    // content, so it is deliberately NOT EULA-gated.
    addTool(
        name = "list_versions",
        description = "List available Minecraft versions, newest first.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringProp("type", "Filter by version type, e.g. release or snapshot")
                limitProp(50)
            },
        ),
    ) { request ->
        try {
            val typeFilter = request.arguments?.get("type")?.jsonPrimitive?.content
            val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 50
            val manifest = metadata.manifest()
            val result = listVersionsToolLogic(manifest.versions, typeFilter, limit)
            CallToolResult(content = listOf(TextContent(Json.encodeToString(result))))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Failed to fetch version manifest: ${e.message}")), isError = true)
        }
    }

    addTool(
        name = "prepare_version",
        description = "Warm up a version (download, remap, index) up front. Optional - every other " +
            "tool does this on first use - so call it only to front-load that wait. Blocks until " +
            "the version is queryable (up to ${PREPARE_POLL_TIMEOUT.toMinutes()} minutes) rather than " +
            "making you poll; the full-text search index keeps building in the background and is " +
            "waited on by the first search_code/search_assets call. If this times out, call again.",
        inputSchema = ToolSchema(
            properties = buildJsonObject { versionProp() },
            required = listOf("version"),
        ),
    ) { request ->
        try {
            eulaGate.requireAccepted()
        } catch (e: EulaNotAcceptedException) {
            return@addTool CallToolResult(content = listOf(TextContent(e.message ?: "EULA not accepted")), isError = true)
        }

        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)

        try {
            val manifest = metadata.manifest()
            val version = VersionResolver.resolve(versionQuery, manifest.versions)
            val detail = metadata.detail(version)

            val result = pollUntilReady(
                pollInterval = PREPARE_POLL_INTERVAL,
                pollTimeout = PREPARE_POLL_TIMEOUT,
                onProgress = { percent -> sendPrepareProgress(request.meta?.progressToken, version.id, percent) },
            ) { versionPreparer.prepare(version, detail) }

            val text = when (result) {
                is PrepareVersionResult.Ready -> "Version ${result.versionId} is ready."
                is PrepareVersionResult.Preparing ->
                    "Version ${result.versionId} is still being prepared (${result.percent}%) after " +
                        "${PREPARE_POLL_TIMEOUT.toMinutes()} minutes. Call prepare_version again to keep waiting."
            }
            CallToolResult(content = listOf(TextContent(text)))
        } catch (e: AmbiguousVersionException) {
            CallToolResult(
                content = listOf(TextContent("Ambiguous version '$versionQuery'. Candidates: ${e.candidates.joinToString()}")),
                isError = true,
            )
        } catch (e: UnknownVersionException) {
            CallToolResult(content = listOf(TextContent("Unknown version '$versionQuery'.")), isError = true)
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Failed to prepare version: ${e.message}")), isError = true)
        }
    }

    // Deliberately NOT EULA-gated: like list_versions, this never fetches or serves Minecraft
    // content - it only deletes this server's own local cache.
    addTool(
        name = "clear_cache",
        description = "Delete the derived cache (remapped jar, index, decompiled source) for one " +
            "version, or all versions if 'version' is omitted, so it rebuilds on next use. Raw " +
            "downloads are kept. Only call this when the user has explicitly asked to clear, " +
            "reset, or invalidate the cache - never speculatively, as maintenance, or to work " +
            "around unexpected output. If you merely suspect stale data, ask first.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringProp("version", "Version id or alias; omit to clear every version")
            },
        ),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: run {
                versionPreparer.clearAll()
                reportGenerator.clearAll()
                gameBridge.clearAll()
                CacheEviction.evictAllDerived(cacheRoot)
                return@addTool CallToolResult(content = listOf(TextContent("Cleared cached data for all versions.")))
            }

        try {
            val manifest = metadata.manifest()
            val version = VersionResolver.resolve(versionQuery, manifest.versions)
            versionPreparer.clear(version.id)
            reportGenerator.clear(version.id)
            gameBridge.clear(version.id)
            CacheEviction.evictVersion(cacheRoot, version.id)
            CallToolResult(content = listOf(TextContent("Cleared cached data for version ${version.id}.")))
        } catch (e: AmbiguousVersionException) {
            CallToolResult(
                content = listOf(TextContent("Ambiguous version '$versionQuery'. Candidates: ${e.candidates.joinToString()}")),
                isError = true,
            )
        } catch (e: UnknownVersionException) {
            CallToolResult(content = listOf(TextContent("Unknown version '$versionQuery'.")), isError = true)
        }
    }

    addTool(
        name = "get_class_outline",
        description = "Superclass, interfaces, and method/field signatures for a class, without " +
            "decompiling it.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                classProp()
            },
            required = listOf("version", "class"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val className = request.classArg()
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'class' parameter is required.")), isError = true)

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> try {
                val outline = getClassOutlineToolLogic(resolved.workspace.indexData, className)
                CallToolResult(content = listOf(TextContent(Json.encodeToString(outline))))
            } catch (e: ClassNotFoundInIndexException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "class not found")), isError = true)
            }
        }
    }

    addTool(
        name = "get_class_source",
        description = "Decompiled Java source for a class. Asking for an inner class decompiles " +
            "its outer class - inner classes are inlined into it.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                classProp()
                lineRangeProps()
            },
            required = listOf("version", "class"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val className = request.classArg()
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'class' parameter is required.")), isError = true)
        val startLine = request.arguments?.get("start_line")?.jsonPrimitive?.intOrNull ?: 1
        val maxLines = request.arguments?.get("max_lines")?.jsonPrimitive?.intOrNull ?: 1500

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> try {
                val sourceCacheDir = resolved.workspace.cacheDir?.resolve("source/$SOURCE_CACHE_CONFIG_VERSION")
                val source = getClassSourceToolLogic(resolved.workspace.remappedClasses, className, startLine, maxLines, sourceCacheDir)
                val text = formatContentResult(source.className, source.source, source.startLine, source.totalLines, source.truncated)
                CallToolResult(content = listOf(TextContent(text)))
            } catch (e: ClassNotFoundInIndexException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "class not found")), isError = true)
            } catch (e: DecompileTimeoutException) {
                CallToolResult(
                    content = listOf(TextContent("${e.message} - try get_bytecode instead for this class.")),
                    isError = true,
                )
            } catch (e: DecompileFailedException) {
                CallToolResult(
                    content = listOf(TextContent("${e.message} - try get_bytecode instead for this class.")),
                    isError = true,
                )
            }
        }
    }

    addTool(
        name = "get_bytecode",
        description = "Raw JVM bytecode (ASM Textifier format) for a class. Fallback when " +
            "get_class_source fails or times out, and the way to inspect mixin annotations.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                classProp()
                lineRangeProps()
            },
            required = listOf("version", "class"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val className = request.classArg()
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'class' parameter is required.")), isError = true)
        val startLine = request.arguments?.get("start_line")?.jsonPrimitive?.intOrNull ?: 1
        val maxLines = request.arguments?.get("max_lines")?.jsonPrimitive?.intOrNull ?: 1500

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> try {
                val bytecode = getBytecodeToolLogic(resolved.workspace.remappedClasses, className, startLine, maxLines)
                val text = formatContentResult(bytecode.className, bytecode.bytecode, bytecode.startLine, bytecode.totalLines, bytecode.truncated)
                CallToolResult(content = listOf(TextContent(text)))
            } catch (e: ClassNotFoundInIndexException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "class not found")), isError = true)
            }
        }
    }

    addTool(
        name = "list_package",
        description = "Browse the package/class tree. An empty package lists top-level packages.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                stringProp("package", "Dotted package name (empty for root)")
                boolProp("recursive", "Recurse into subpackages (default false)")
            },
            required = listOf("version"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val dottedPackage = request.arguments?.get("package")?.jsonPrimitive?.content ?: ""
        val recursive = request.arguments?.get("recursive")?.jsonPrimitive?.content?.toBoolean() ?: false

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> {
                val listing = listPackageToolLogic(resolved.workspace.indexData.classes().keys, dottedPackage, recursive)
                CallToolResult(content = listOf(TextContent(Json.encodeToString(listing))))
            }
        }
    }

    addTool(
        name = "search_classes",
        description = "Find classes by name (substring or camelCase-acronym match). Results carry " +
            "size/nMethods/nFields, so you can weigh get_class_outline against decompiling a big " +
            "class outright.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                stringProp("query", "Class name substring, or an acronym like 'LC' for LevelChunk")
                limitProp(100)
            },
            required = listOf("version", "query"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val query = request.arguments?.get("query")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'query' parameter is required.")), isError = true)
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 100

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> {
                val results = searchClassesToolLogic(
                    resolved.workspace.indexData.classes().keys,
                    query,
                    limit,
                    resolved.workspace.remappedClasses,
                    resolved.workspace.indexData.members(),
                )
                CallToolResult(content = listOf(TextContent(Json.encodeToString(results))))
            }
        }
    }

    addTool(
        name = "find_references",
        description = "Find who calls, reads, or writes a class or member. Omitting member finds " +
            "references to the class itself. A member not declared directly on the class is " +
            "resolved up the inheritance chain (unless resolve_declaration=false). An ambiguous " +
            "member (several overloads/kinds) returns a candidate list rather than an error - " +
            "call again with kind. Declaring and caller classes carry size/nMethods/nFields, so " +
            "you can judge what's worth reading.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                classProp()
                stringProp("member", "Bare member name, e.g. getBlockState; omit for the class itself")
                stringProp("kind", "'method' or 'field', if a name is both")
                boolProp("resolve_declaration", "Resolve up the inheritance chain (default true)")
            },
            required = listOf("version", "class"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val className = request.classArg()
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'class' parameter is required.")), isError = true)
        val member = request.arguments?.get("member")?.jsonPrimitive?.content
        val kind = request.arguments?.get("kind")?.jsonPrimitive?.content
        val resolveDeclaration = request.arguments?.get("resolve_declaration")?.jsonPrimitive?.content?.toBoolean() ?: true

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> try {
                val outcome = findReferencesToolLogic(
                    resolved.workspace.indexData,
                    resolved.workspace.referenceIndexer,
                    className,
                    member,
                    kind,
                    resolveDeclaration,
                    resolved.workspace.remappedClasses,
                )
                when (outcome) {
                    is FindReferencesOutcome.Found -> CallToolResult(content = listOf(TextContent(Json.encodeToString(outcome.result))))
                    is FindReferencesOutcome.AmbiguousMember -> CallToolResult(
                        content = listOf(TextContent(Json.encodeToString(mapOf("ambiguousCandidates" to outcome.candidates)))),
                    )
                }
            } catch (e: ClassNotFoundInIndexException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "class not found")), isError = true)
            } catch (e: MemberNotFoundException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "member not found")), isError = true)
            }
        }
    }

    addTool(
        name = "find_declaration",
        description = "Go to declaration for a symbol at a spot in decompiled source - the ctrl+click " +
            "an IDE gives you. Takes a line from get_class_source; with no symbol or column it " +
            "returns every symbol on that line, in column order, so you can pick one without " +
            "knowing in advance what is there. Owners come from the decompiler's own resolution, " +
            "so two same-named calls to different classes on one line stay distinct, and an " +
            "inherited member resolves to the class that really declares it, with the line its " +
            "declaration sits on.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                classProp()
                intProp("line", "Line to resolve, as numbered by get_class_source")
                intProp("bytecode_line", "Instead of 'line': a line as numbered by get_bytecode, to resolve one instruction's target")
                stringProp("symbol", "Only this symbol on the line, e.g. getBlockState")
                intProp("column", "Only the symbol under this column, 1-indexed")
            },
            required = listOf("version", "class"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val className = request.classArg()
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'class' parameter is required.")), isError = true)
        val line = request.arguments?.get("line")?.jsonPrimitive?.intOrNull
        val bytecodeLine = request.arguments?.get("bytecode_line")?.jsonPrimitive?.intOrNull
        val symbol = request.arguments?.get("symbol")?.jsonPrimitive?.content
        val column = request.arguments?.get("column")?.jsonPrimitive?.intOrNull
        val useSite = when {
            line != null -> UseSite.SourceLine(line, symbol, column)
            bytecodeLine != null -> UseSite.BytecodeLine(bytecodeLine)
            else -> return@addTool CallToolResult(
                content = listOf(TextContent("One of 'line' (a get_class_source line) or 'bytecode_line' (a get_bytecode line) is required.")),
                isError = true,
            )
        }

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> try {
                val sourceCacheDir = resolved.workspace.cacheDir?.resolve("source/$SOURCE_CACHE_CONFIG_VERSION")
                val result = findDeclarationToolLogic(
                    resolved.workspace.indexData,
                    resolved.workspace.remappedClasses,
                    className,
                    useSite,
                    sourceCacheDir,
                )
                CallToolResult(content = listOf(TextContent(Json.encodeToString(result))))
            } catch (e: ClassNotFoundInIndexException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "class not found")), isError = true)
            } catch (e: LineOutOfRangeException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "line out of range")), isError = true)
            } catch (e: DecompileTimeoutException) {
                CallToolResult(content = listOf(TextContent("${e.message} - try get_bytecode instead for this class.")), isError = true)
            } catch (e: DecompileFailedException) {
                CallToolResult(content = listOf(TextContent("${e.message} - try get_bytecode instead for this class.")), isError = true)
            }
        }
    }

    addTool(
        name = "get_hierarchy",
        description = "Supertype or subtype tree for a class.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                classProp()
                stringProp("direction", "'supertypes' (default) or 'subtypes'")
                intProp("depth", "Levels to expand (default 1)")
            },
            required = listOf("version", "class"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val className = request.classArg()
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'class' parameter is required.")), isError = true)
        val direction = request.arguments?.get("direction")?.jsonPrimitive?.content ?: "supertypes"
        val depth = request.arguments?.get("depth")?.jsonPrimitive?.intOrNull ?: 1

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> try {
                val hierarchy = getHierarchyToolLogic(resolved.workspace.indexData, className, direction, depth)
                CallToolResult(content = listOf(TextContent(Json.encodeToString(hierarchy))))
            } catch (e: ClassNotFoundInIndexException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "class not found")), isError = true)
            } catch (e: InvalidHierarchyDirectionException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "invalid direction")), isError = true)
            }
        }
    }

    addTool(
        name = "list_assets",
        description = "List files in a version's client jar - assets (textures, models, lang, " +
            "sounds, .mcmeta) and data files (recipes, loot tables, tags, worldgen). Only entries " +
            "flagged isText=true can be read by get_asset.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                stringProp("path_prefix", "Asset path prefix, e.g. assets/minecraft/lang (empty for all)")
                limitProp(200)
            },
            required = listOf("version"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val pathPrefix = request.arguments?.get("path_prefix")?.jsonPrimitive?.content ?: ""
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 200

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> {
                val listing = listAssetsToolLogic(resolved.workspace.assets, pathPrefix, limit)
                CallToolResult(content = listOf(TextContent(Json.encodeToString(listing))))
            }
        }
    }

    addTool(
        name = "get_asset",
        description = "Read a text client asset (JSON, .mcmeta, .lang, .txt, .properties, shaders " +
            "as .vsh/.fsh/.glsl) by its exact list_assets path. Binary assets (textures, sounds) " +
            "are listed there but can't be read here.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                stringProp("path", "Exact asset path, e.g. assets/minecraft/items/allium.json")
                lineRangeProps()
            },
            required = listOf("version", "path"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val path = request.arguments?.get("path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'path' parameter is required.")), isError = true)
        val startLine = request.arguments?.get("start_line")?.jsonPrimitive?.intOrNull ?: 1
        val maxLines = request.arguments?.get("max_lines")?.jsonPrimitive?.intOrNull ?: 1500

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> try {
                val asset = getAssetToolLogic(resolved.workspace.assets, resolved.workspace.assetSource::readText, path, startLine, maxLines)
                val text = formatContentResult(asset.path, asset.content, asset.startLine, asset.totalLines, asset.truncated)
                CallToolResult(content = listOf(TextContent(text)))
            } catch (e: AssetNotFoundException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "asset not found")), isError = true)
            } catch (e: AssetNotTextException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "asset is not text")), isError = true)
            }
        }
    }

    addTool(
        name = "extract",
        description = "Copy one asset (binary included - textures, sounds, models) from a " +
            "version's client jar to a local filesystem path.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                stringProp("path", "Exact jar entry path, e.g. assets/minecraft/textures/block/stone.png")
                stringProp("destination", "Local filesystem path to write the file to")
            },
            required = listOf("version", "path", "destination"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val path = request.arguments?.get("path")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'path' parameter is required.")), isError = true)
        val destinationString = request.arguments?.get("destination")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'destination' parameter is required.")), isError = true)
        // Resolved to absolute so the result says where the file actually landed: a relative
        // destination would otherwise be written against this server's own cwd, which the calling
        // agent knows nothing about.
        val destination = Path.of(destinationString).toAbsolutePath().normalize()

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> try {
                val bytesWritten = extractToolLogic(resolved.workspace.assets, resolved.workspace.assetSource::readBytes, path, destination)
                CallToolResult(content = listOf(TextContent("Wrote $bytesWritten bytes to $destination (from the ${resolved.versionId} client jar).")))
            } catch (e: AssetNotFoundException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "asset not found")), isError = true)
            } catch (e: IOException) {
                CallToolResult(content = listOf(TextContent("Failed to write to '$destination': ${e.message}")), isError = true)
            }
        }
    }

    addTool(
        name = "get_report",
        description = "JSON reports computed by Mojang's own data generator: registries (every " +
            "registered block/item/entity/sound, with ids), blocks (every blockstate), commands " +
            "(the full command tree), packets, datapack layout, default item components " +
            "(minecraft/components/item/<id>), biome parameters. First call per version runs the " +
            "generator (~a minute) and caches. 'report' is a file to read or a directory to " +
            "list; omit it to list the root.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                stringProp("report", "Report file or directory under reports/, e.g. 'registries' or 'minecraft/components/item'; omit to list the root")
                lineRangeProps()
                limitProp(200)
            },
            required = listOf("version"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val report = request.arguments?.get("report")?.jsonPrimitive?.content?.trim().orEmpty()
        val startLine = request.arguments?.get("start_line")?.jsonPrimitive?.intOrNull ?: 1
        val maxLines = request.arguments?.get("max_lines")?.jsonPrimitive?.intOrNull ?: 1500
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 200

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> when (
                val access = prepareReports(metadata, versionQuery, resolved, reportGenerator, blobStore) { elapsed ->
                    sendElapsedProgress(request.meta?.progressToken, "Generating reports for ${resolved.versionId}", elapsed)
                }
            ) {
                is ReportsAccess.Failed -> access.result
                is ReportsAccess.Ready -> {
                    val reportsDir = access.reportsDir
                    val target = resolveReportTarget(reportsDir, report)
                    when {
                        report.isEmpty() || (target != null && Files.isDirectory(target)) -> try {
                            CallToolResult(content = listOf(TextContent(Json.encodeToString(listReportToolLogic(reportsDir, report, limit)))))
                        } catch (e: ReportNotFoundException) {
                            CallToolResult(content = listOf(TextContent(e.message ?: "no such report")), isError = true)
                        }
                        else -> try {
                            val result = getReportToolLogic(reportsDir, report, startLine, maxLines)
                            CallToolResult(content = listOf(TextContent(formatContentResult(result.path, result.content, result.startLine, result.totalLines, result.truncated))))
                        } catch (e: ReportNotFoundException) {
                            CallToolResult(content = listOf(TextContent(e.message ?: "no such report")), isError = true)
                        }
                    }
                }
            }
        }
    }

    addTool(
        name = "list_reports",
        description = "List what get_report can serve for a version, read from the data-generator " +
            "classes in the jar itself - instant, never generates. Entries marked as directories " +
            "are per-object trees (minecraft/components holds one json per item). Once reports " +
            "have been generated, lists the actual generated tree instead.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                stringProp("prefix", "Directory under reports/ to list (only meaningful once generated), e.g. 'minecraft/components'")
                limitProp(200)
            },
            required = listOf("version"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val prefix = request.arguments?.get("prefix")?.jsonPrimitive?.content?.trim().orEmpty()
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 200

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> {
                val reportsDir = resolved.workspace.cacheDir?.resolve("reports")?.takeIf { ReportGenerator.isComplete(it) }
                if (reportsDir == null) {
                    CallToolResult(content = listOf(TextContent(Json.encodeToString(expectedReportEntries(resolved.workspace.indexData.classes().keys, limit)))))
                } else {
                    try {
                        CallToolResult(content = listOf(TextContent(Json.encodeToString(listReportToolLogic(reportsDir, prefix, limit)))))
                    } catch (e: ReportNotFoundException) {
                        CallToolResult(content = listOf(TextContent(e.message ?: "no such report")), isError = true)
                    }
                }
            }
        }
    }

    addTool(
        name = "list_paths",
        description = "Browse a version's client jar file tree one level at a time - the root " +
            "lists the assets/ and data/ trees; 'data/minecraft/worldgen' lists worldgen " +
            "categories, and so on. File entries are directly addressable by get_asset/extract.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                stringProp("prefix", "Path prefix to list one level under (empty for the jar root)")
                limitProp(200)
            },
            required = listOf("version"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val prefix = request.arguments?.get("prefix")?.jsonPrimitive?.content?.trim().orEmpty()
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 200

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> CallToolResult(
                content = listOf(TextContent(Json.encodeToString(listPathsToolLogic(resolved.workspace.assets, prefix, limit)))),
            )
        }
    }

    addTool(
        name = "get_game_data",
        description = "Read a static field straight out of the booted game - composting chances " +
            "(net.minecraft.world.level.block.ComposterBlock.COMPOSTABLES), villager trades, " +
            "attributes, anything code computes at boot instead of shipping as JSON. First query " +
            "per version boots the game in a sidecar JVM (~10s, then resident); results are " +
            "cached. Output is bounded descriptive JSON: registry objects appear as their ids, " +
            "deep structures are depth-capped - read it for values, not types.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                classProp()
                stringProp("field", "Static field name, e.g. COMPOSTABLES")
                lineRangeProps()
            },
            required = listOf("version", "class", "field"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val className = request.classArg()
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'class' parameter is required.")), isError = true)
        val fieldName = request.arguments?.get("field")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'field' parameter is required.")), isError = true)
        val startLine = request.arguments?.get("start_line")?.jsonPrimitive?.intOrNull ?: 1
        val maxLines = request.arguments?.get("max_lines")?.jsonPrimitive?.intOrNull ?: 1500

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> {
                val cacheDir = resolved.workspace.cacheDir
                    ?: return@addTool CallToolResult(
                        content = listOf(TextContent("Version ${resolved.versionId} has no on-disk cache configured for this server, so game data cannot be cached.")),
                        isError = true,
                    )
                when (val artifacts = resolveGameArtifacts(metadata, versionQuery, resolved, blobStore)) {
                    is GameArtifacts.Failed -> artifacts.result
                    is GameArtifacts.Ready -> {
                        val value = try {
                            gameBridge.readStaticField(resolved.versionId, artifacts.detail, artifacts.clientJarPath, cacheDir, className, fieldName) { elapsed ->
                                sendElapsedProgress(request.meta?.progressToken, "Booting the game for ${resolved.versionId}", elapsed)
                            }
                        } catch (e: GameBridgeException) {
                            return@addTool CallToolResult(content = listOf(TextContent(e.message ?: "game bridge failed")), isError = true)
                        }

                        // Pretty-printed so line paging (and #L anchors) mean something; reuses the
                        // asset paging logic by treating the value as a one-entry pseudo asset.
                        val pretty = prettyPrinter.encodeToString(Json.parseToJsonElement(value))
                        val label = "$className.$fieldName"
                        val result = getAssetToolLogic(
                            mapOf(label to AssetInfo(label, pretty.length, true)),
                            { pretty },
                            label,
                            startLine,
                            maxLines,
                        )
                        CallToolResult(content = listOf(TextContent(formatContentResult(result.path, result.content, result.startLine, result.totalLines, result.truncated))))
                    }
                }
            }
        }
    }

    addTool(
        name = "diff_versions",
        description = "List classes added/removed/modified between two versions. CRC32 over class " +
            "bytes (inner classes grouped with their outer), so it's cheap: no decompilation.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringProp("version_a", "First version id or alias")
                stringProp("version_b", "Second version id or alias")
                stringProp("package", "Package prefix filter, dotted or slashed (default: all)")
            },
            required = listOf("version_a", "version_b"),
        ),
    ) { request ->
        val versionAQuery = request.arguments?.get("version_a")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version_a' parameter is required.")), isError = true)
        val versionBQuery = request.arguments?.get("version_b")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version_b' parameter is required.")), isError = true)
        val packagePrefix = request.arguments?.get("package")?.jsonPrimitive?.content
            ?.replace('.', '/')
            ?: ""

        when (val resolved = resolveWorkspaces(metadata, eulaGate, workspaceCache, versionPreparer, versionAQuery, versionBQuery) { versionLabel, percent ->
            sendPrepareProgress(request.meta?.progressToken, versionLabel, percent)
        }) {
            is ResolvedWorkspaces.Failed -> resolved.result
            is ResolvedWorkspaces.Ok -> {
                val result = diffVersionsToolLogic(resolved.workspaceA, resolved.workspaceB, packagePrefix)
                CallToolResult(content = listOf(TextContent(Json.encodeToString(result))))
            }
        }
    }

    addTool(
        name = "diff_class",
        description = "Unified diff of one class's decompiled source between two versions.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringProp("version_a", "First version id or alias")
                stringProp("version_b", "Second version id or alias")
                classProp()
                intProp("context", "Lines of context around each change (default 3)")
            },
            required = listOf("version_a", "version_b", "class"),
        ),
    ) { request ->
        val versionAQuery = request.arguments?.get("version_a")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version_a' parameter is required.")), isError = true)
        val versionBQuery = request.arguments?.get("version_b")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version_b' parameter is required.")), isError = true)
        val className = request.classArg()
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'class' parameter is required.")), isError = true)
        val context = request.arguments?.get("context")?.jsonPrimitive?.intOrNull ?: 3

        when (val resolved = resolveWorkspaces(metadata, eulaGate, workspaceCache, versionPreparer, versionAQuery, versionBQuery) { versionLabel, percent ->
            sendPrepareProgress(request.meta?.progressToken, versionLabel, percent)
        }) {
            is ResolvedWorkspaces.Failed -> resolved.result
            is ResolvedWorkspaces.Ok -> try {
                val result = diffClassToolLogic(resolved.workspaceA, resolved.workspaceB, className, context)
                val body = result.diff.ifEmpty { "No differences." }
                val text = "${result.className}: ${result.versionA} -> ${result.versionB}\n\n$body"
                CallToolResult(content = listOf(TextContent(text)))
            } catch (e: ClassNotFoundInIndexException) {
                CallToolResult(content = listOf(TextContent(e.message ?: "class not found")), isError = true)
            } catch (e: DecompileTimeoutException) {
                CallToolResult(
                    content = listOf(TextContent("${e.message} - try get_bytecode instead for this class.")),
                    isError = true,
                )
            } catch (e: DecompileFailedException) {
                CallToolResult(
                    content = listOf(TextContent("${e.message} - try get_bytecode instead for this class.")),
                    isError = true,
                )
            }
        }
    }

    addTool(
        name = "search_asset_files",
        description = "Find client assets by filename substring - 'stone' matches " +
            "assets/minecraft/textures/block/stone.png and assets/minecraft/items/stone.json. " +
            "For code, use search_classes.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                stringProp("query", "Filename substring, e.g. 'stone' or 'oak_planks'")
                limitProp(100)
            },
            required = listOf("version", "query"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val query = request.arguments?.get("query")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'query' parameter is required.")), isError = true)
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 100

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> {
                val results = searchAssetFilesToolLogic(resolved.workspace.assets, query, limit)
                CallToolResult(content = listOf(TextContent(Json.encodeToString(results))))
            }
        }
    }

    addTool(
        name = "search_code",
        description = "Heavy full-text search over decompiled source, substring or regex. Prefer " +
            "the much faster search_classes; reach for this only when you have no name to search " +
            "by (e.g. 'where is FOO defined?'). Narrow an over-broad query in one call with " +
            "'exclude' and 'classes' rather than re-running it. Results report 'matches' as an " +
            "exact count, or as '40+' when the scan stopped at the limit. The first call per " +
            "version decompiles and indexes the jar.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                stringProp("query", "Substring, or a regex pattern if regex=true")
                boolProp("regex", "Treat query as a regex (default false)")
                limitProp(100)
                stringOrArrayProp("classes", "Dotted class name(s) to search within, e.g. find_references' callerClass; omit for everything")
                searchFilterProps()
            },
            required = listOf("version", "query"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val query = request.arguments?.get("query")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'query' parameter is required.")), isError = true)
        val useRegex = request.arguments?.get("regex")?.jsonPrimitive?.content?.toBoolean() ?: false
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 100
        val classNames = request.stringListArg("classes", "class")
        val exclude = request.arguments?.get("exclude")?.jsonPrimitive?.content
        val exactCount = request.arguments?.get("exact_count")?.jsonPrimitive?.content?.toBoolean() ?: false

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> {
                // The workspace is Ready before its search index finishes building, so this is
                // the one place the wait lands - with the same progress stream prepare uses.
                val index = versionPreparer.awaitSearchIndex(resolved.workspace) { percent ->
                    sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
                }
                if (index == null) {
                    CallToolResult(content = listOf(TextContent(
                        "Version ${resolved.versionId} has no full-text index available (no on-disk cache is " +
                            "configured, or its background build failed) - retry to rebuild it.",
                    )), isError = true)
                } else {
                    try {
                        val result = searchCodeToolLogic(index, query, useRegex, limit, classNames, exclude, exactCount)
                        CallToolResult(content = listOf(TextContent(Json.encodeToString(result))))
                    } catch (e: PatternSyntaxException) {
                        CallToolResult(content = listOf(TextContent(invalidRegexMessage(e))), isError = true)
                    }
                }
            }
        }
    }

    addTool(
        name = "search_assets",
        description = "Full-text search inside text assets (JSON, .mcmeta, .lang, .txt, " +
            ".properties, shaders as .vsh/.fsh/.glsl), substring or regex. Prefer the faster " +
            "search_asset_files unless you must match contents rather than paths. Pass known " +
            "path(s) as 'paths' to skip every other asset.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                versionProp()
                stringProp("query", "Substring, or a regex pattern if regex=true")
                boolProp("regex", "Treat query as a regex (default false)")
                limitProp(100)
                stringOrArrayProp("paths", "Asset path(s) to search within, e.g. from search_asset_files; omit for everything")
                searchFilterProps()
            },
            required = listOf("version", "query"),
        ),
    ) { request ->
        val versionQuery = request.arguments?.get("version")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'version' parameter is required.")), isError = true)
        val query = request.arguments?.get("query")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("The 'query' parameter is required.")), isError = true)
        val useRegex = request.arguments?.get("regex")?.jsonPrimitive?.content?.toBoolean() ?: false
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 100
        val assetPaths = request.stringListArg("paths", "path")
        val exclude = request.arguments?.get("exclude")?.jsonPrimitive?.content
        val exactCount = request.arguments?.get("exact_count")?.jsonPrimitive?.content?.toBoolean() ?: false

        when (val resolved = resolveWorkspace(metadata, eulaGate, workspaceCache, versionPreparer, versionQuery) { percent ->
            sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
        }) {
            is ResolvedWorkspace.Failed -> resolved.result
            is ResolvedWorkspace.Ok -> {
                val index = versionPreparer.awaitSearchIndex(resolved.workspace) { percent ->
                    sendPrepareProgress(request.meta?.progressToken, versionQuery, percent)
                }
                if (index == null) {
                    CallToolResult(content = listOf(TextContent(
                        "Version ${resolved.versionId} has no full-text index available (no on-disk cache is " +
                            "configured, or its background build failed) - retry to rebuild it.",
                    )), isError = true)
                } else {
                    try {
                        val result = searchAssetsToolLogic(index, query, useRegex, limit, assetPaths, exclude, exactCount)
                        CallToolResult(content = listOf(TextContent(Json.encodeToString(result))))
                    } catch (e: PatternSyntaxException) {
                        CallToolResult(content = listOf(TextContent(invalidRegexMessage(e))), isError = true)
                    }
                }
            }
        }
    }
}
