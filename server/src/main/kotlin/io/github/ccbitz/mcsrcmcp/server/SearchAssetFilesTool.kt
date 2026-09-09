package io.github.ccbitz.mcsrcmcp.server

import kotlinx.serialization.Serializable

@Serializable
data class AssetSearchResult(val path: String)

private data class ScoredAsset(val path: String, val score: Int, val simpleName: String)

// Substring search over the asset path's final segment, with exact/prefix tiers ranked ahead of
// a plain substring hit - same shape as search_classes' scoring, minus its camelCase-acronym
// tier: Minecraft asset filenames are snake_case/kebab-case (oak_planks.json), not Java camelCase
// identifiers, so that tier would never fire here.
fun searchAssetFilesToolLogic(
    assets: Map<String, AssetInfo>,
    query: String,
    limit: Int = 100,
): List<AssetSearchResult> {
    if (query.isEmpty()) return emptyList()

    val lowerQuery = query.lowercase()

    return assets.keys
        .asSequence()
        .mapNotNull { path ->
            val simpleName = path.substringAfterLast('/')
            val lowerSimple = simpleName.lowercase()

            if (!lowerSimple.contains(lowerQuery)) {
                return@mapNotNull null
            }

            val score = when {
                lowerSimple == lowerQuery -> 0
                lowerSimple.startsWith(lowerQuery) -> 1
                else -> 2 + lowerSimple.indexOf(lowerQuery)
            }

            ScoredAsset(path, score, simpleName)
        }
        .sortedWith(compareBy({ it.score }, { it.simpleName.length }, { it.simpleName }))
        .take(limit)
        .map { AssetSearchResult(it.path) }
        .toList()
}
