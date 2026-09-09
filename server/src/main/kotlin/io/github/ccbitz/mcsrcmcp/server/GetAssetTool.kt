package io.github.ccbitz.mcsrcmcp.server

import kotlinx.serialization.Serializable

@Serializable
data class AssetContentResult(
    val path: String,
    val content: String,
    val totalLines: Int,
    val truncated: Boolean,
    val startLine: Int,
)

class AssetNotFoundException(path: String) : NoSuchElementException("asset not found: $path")

class AssetNotTextException(path: String) :
    IllegalArgumentException("asset '$path' is not a recognized text asset and cannot be read as text")

fun getAssetToolLogic(
    assets: Map<String, AssetInfo>,
    // Takes a reader rather than a decoded map: asset text is now read from the client jar on
    // demand (see [AssetSource]) instead of being held for every asset in the version.
    readText: (String) -> String?,
    path: String,
    startLine: Int = 1,
    maxLines: Int = 1500,
): AssetContentResult {
    val info = assets[path] ?: throw AssetNotFoundException(path)
    if (!info.isText) {
        throw AssetNotTextException(path)
    }
    // The entry is in the jar (it was catalogued from that same jar) and it's a text extension, so
    // a null here means the jar changed underneath the cached listing.
    val text = readText(path) ?: throw AssetNotFoundException(path)

    val lines = text.lines()
    val totalLines = lines.size
    val fromIndex = (startLine - 1).coerceIn(0, lines.size)
    val toIndex = (fromIndex + maxLines).coerceAtMost(lines.size)

    return AssetContentResult(
        path = path,
        content = lines.subList(fromIndex, toIndex).joinToString("\n"),
        totalLines = totalLines,
        truncated = toIndex < totalLines,
        startLine = startLine,
    )
}
