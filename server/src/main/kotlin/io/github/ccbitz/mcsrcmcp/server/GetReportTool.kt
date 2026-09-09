package io.github.ccbitz.mcsrcmcp.server

import java.nio.file.Files
import java.nio.file.Path

class ReportNotFoundException(name: String, available: List<String>) : NoSuchElementException(
    when {
        available.isEmpty() -> "no reports generated for this version"
        // 26.2's item components alone are 1500+ entries - cap the hint at the top level, which is
        // single digits, and never dump a directory listing into an error message.
        else -> "no report named '$name' (available: ${available.joinToString(", ")})"
    },
)

/**
 * Every report file under [reportsDir], as an assets map - report serving reuses getAssetToolLogic
 * wholesale, so paging and the label/range formatting come along for free. Paths use '/' separators
 * and are relative to the reports root ("minecraft/components/item/diamond_sword.json"). The
 * completion stamp and any other dotfiles are invisible.
 */
fun reportFileAssets(reportsDir: Path): Map<String, AssetInfo> {
    if (!Files.isDirectory(reportsDir)) return emptyMap()
    Files.walk(reportsDir).use { stream ->
        return stream
            .filter { Files.isRegularFile(it) && !it.fileName.toString().startsWith(".") }
            .sorted()
            .collect(
                { LinkedHashMap<String, AssetInfo>() },
                { map, path ->
                    val relative = reportsDir.relativize(path).toString().replace('\\', '/')
                    map[relative] = AssetInfo(relative, Files.size(path).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), true)
                },
                { into, from -> into.putAll(from) },
            )
    }
}

/**
 * Resolves an agent-supplied [report] against [reportsDir]: normalized and contained (a ".."-laden
 * path cannot escape the reports root), with or without the ".json" suffix. Null when nothing
 * matches - the caller turns that into a [ReportNotFoundException] listing the top level.
 */
internal fun resolveReportTarget(reportsDir: Path, report: String): Path? {
    val normalized = report.trim().replace('\\', '/').trimStart('/')
    if (normalized.isEmpty()) return null
    // Dotfile segments stay invisible to agents - that includes the completion stamp, which is
    // bookkeeping, not content.
    if (normalized.split('/').any { it.startsWith(".") }) return null
    val candidates = listOf(normalized, "${normalized.removeSuffix(".json")}.json").distinct()
    val root = reportsDir.toAbsolutePath().normalize()
    return candidates
        .map { root.resolve(it).normalize() }
        .firstOrNull { it.startsWith(root) && Files.exists(it) }
}

/**
 * Lists one level of the reports tree, [prefix]-rooted ("" for the root). Directories are listed
 * as entries so an agent can browse to a single item component without ever fetching all 1500+.
 */
fun listReportToolLogic(reportsDir: Path, prefix: String = "", limit: Int = 200): FileTreeListing {
    // No reports root at all (a generation that never ran) is an empty listing, not an error: the
    // caller only reaches here after ensureReports, so this is unreachable in production and keeps
    // the function total for tests and future callers.
    if (!Files.isDirectory(reportsDir)) return FileTreeListing(prefix, emptyList(), truncated = false)

    // "" (or blank) means the root itself; resolveReportTarget rejects empty names.
    val target = if (prefix.isBlank()) reportsDir.toAbsolutePath().normalize() else resolveReportTarget(reportsDir, prefix)
    if (target == null || !Files.isDirectory(target)) {
        throw ReportNotFoundException(prefix, topLevelReportNames(reportsDir))
    }

    val children = Files.list(target).use { stream ->
        stream.filter { !it.fileName.toString().startsWith(".") }.sorted().toList()
    }
    val entries = children
        .take(limit)
        .map { child ->
            val relative = reportsDir.relativize(child.toAbsolutePath().normalize()).toString().replace('\\', '/')
            FileTreeEntry(relative, Files.size(child).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), Files.isDirectory(child))
        }
    return FileTreeListing(prefix, entries, truncated = children.size > limit)
}

/** Serves one report file, paged exactly like a text asset. */
fun getReportToolLogic(reportsDir: Path, report: String, startLine: Int = 1, maxLines: Int = 1500): AssetContentResult {
    val assets = reportFileAssets(reportsDir)
    val target = resolveReportTarget(reportsDir, report)
        ?: throw ReportNotFoundException(report, topLevelReportNames(reportsDir))
    if (Files.isDirectory(target)) {
        throw ReportNotFoundException(report, topLevelReportNames(reportsDir))
    }

    val key = reportsDir.relativize(target).toString().replace('\\', '/')
    // The reader resolves a key that came from walking the directory itself, never the raw agent
    // argument, so there is no traversal to guard against on this path.
    return getAssetToolLogic(assets, { name -> runCatching { Files.readString(reportsDir.resolve(name)) }.getOrNull() }, key, startLine, maxLines)
}

internal fun topLevelReportNames(reportsDir: Path): List<String> =
    reportFileAssets(reportsDir).keys
        .map { it.substringBefore('/') }
        .distinct()
        .sorted()

// The output names are string literals inside the generator classes, not statically derivable in
// general - this is the mapping for the providers Mojang ships. An unrecognized Report class still
// lists, under its class name, so a new provider is visible even before this table learns its
// file name.
private val EXPECTED_REPORT_FILES = mapOf(
    "RegistryDumpReport" to "registries.json",
    "BlockListReport" to "blocks.json",
    "CommandsReport" to "commands.json",
    "PacketReport" to "packets.json",
    "DatapackStructureReport" to "datapack.json",
    "RegistryComponentsReport" to "minecraft/components/",
    "BiomeParametersDumpReport" to "biome_parameters/",
    "JsonRpcApiSchema" to "json-rpc-api-schema.json",
)

private val EXTRA_REPORT_CLASSES = listOf("net/minecraft/server/jsonrpc/dataprovider/JsonRpcApiSchema")

/**
 * What get_report can serve, read straight out of the version's class index - no generation, no
 * subprocess, instant. Every class under net.minecraft.data.info ending in Report is a report
 * provider; JsonRpcApiSchema is wired among them by Main without matching either condition.
 */
fun expectedReportEntries(classNames: Collection<String>, limit: Int = 200): FileTreeListing {
    val providers = classNames
        .filter { it.startsWith("net/minecraft/data/info/") && it.endsWith("Report") && !it.endsWith("package-info") }
        .plus(EXTRA_REPORT_CLASSES.filter { it in classNames })
    val entries = providers
        .map { internal ->
            val simple = internal.substringAfterLast('/')
            val target = EXPECTED_REPORT_FILES[simple] ?: simple
            FileTreeEntry(target.trimEnd('/'), isDirectory = target.endsWith("/"))
        }
        .sortedBy { it.path }
    return FileTreeListing(prefix = "", entries.take(limit), truncated = entries.size > limit, source = "expected")
}
