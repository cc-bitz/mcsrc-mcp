package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.cache.VersionListEntry
import io.github.ccbitz.mcsrcmcp.cache.isUnobfuscatedByDefault
import kotlinx.serialization.Serializable

@Serializable
data class ListVersionsResult(
    val id: String,
    val type: String,
    val releaseTime: String,
    val obfuscated: Boolean,
)

fun listVersionsToolLogic(
    versions: List<VersionListEntry>,
    typeFilter: String? = null,
    limit: Int = 50,
): List<ListVersionsResult> {
    return versions
        .asSequence()
        .filter { typeFilter == null || it.type == typeFilter }
        .sortedByDescending { it.releaseTime }
        .take(limit)
        .map { ListVersionsResult(it.id, it.type, it.releaseTime, !isUnobfuscatedByDefault(it)) }
        .toList()
}
