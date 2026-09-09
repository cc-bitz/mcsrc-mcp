package io.github.ccbitz.mcsrcmcp.server

import kotlinx.serialization.Serializable

@Serializable
data class AssetListEntry(val path: String, val sizeBytes: Int, val isText: Boolean)

@Serializable
data class AssetListingResult(val assets: List<AssetListEntry>, val truncated: Boolean)

// Shared by list_paths (the jar's file tree) and list_reports (the generated reports tree): both
// browse a file hierarchy one level at a time, and directories carry no meaningful size.
@Serializable
data class FileTreeEntry(val path: String, val sizeBytes: Int = 0, val isDirectory: Boolean)

// "generated" = the real on-disk tree datagen wrote; "expected" = derived from the jar's own
// data-generator classes via the class index, available before anything has run.
@Serializable
data class FileTreeListing(
    val prefix: String,
    val entries: List<FileTreeEntry>,
    val truncated: Boolean,
    val source: String = "generated",
)

/**
 * One level of the jar's non-class file tree, [prefix]-rooted ("" for the root, which lists the
 * `assets/` and `data/` trees). The assets map holds files only, so directories are synthesized
 * from the paths that pass under them. File entries keep their full path from the jar root, so
 * what a listing shows is directly addressable by get_asset/extract.
 */
fun listPathsToolLogic(assets: Map<String, AssetInfo>, prefix: String = "", limit: Int = 200): FileTreeListing {
    val normalized = prefix.trim().replace('\\', '/').trimStart('/')
    val base = if (normalized.isEmpty()) "" else "$normalized/"
    val directories = sortedSetOf<String>()
    val files = sortedSetOf<String>()
    for (path in assets.keys) {
        if (!path.startsWith(base)) continue
        val rest = path.removePrefix(base)
        if (rest.isEmpty()) continue
        val slash = rest.indexOf('/')
        if (slash == -1) files.add(path) else directories.add(base + rest.substring(0, slash))
    }

    val entries = directories.map { FileTreeEntry(it, isDirectory = true) } +
        files.map { FileTreeEntry(it, assets.getValue(it).sizeBytes, isDirectory = false) }
    return FileTreeListing(normalized, entries.take(limit), truncated = entries.size > limit)
}

fun listAssetsToolLogic(
    assets: Map<String, AssetInfo>,
    pathPrefix: String = "",
    limit: Int = 200,
): AssetListingResult {
    val matches = assets.values.filter { it.path.startsWith(pathPrefix) }.sortedBy { it.path }
    val limited = matches.take(limit)
    return AssetListingResult(
        assets = limited.map { AssetListEntry(it.path, it.sizeBytes, it.isText) },
        truncated = matches.size > limit,
    )
}
