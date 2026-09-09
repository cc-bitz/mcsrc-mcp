package io.github.ccbitz.mcsrcmcp.cache

import java.time.Instant

class AmbiguousVersionException(val candidates: List<String>) :
    IllegalArgumentException("ambiguous version reference, candidates: ${candidates.joinToString()}")

class UnknownVersionException(val query: String) :
    IllegalArgumentException("no version matches '$query'")

object VersionResolver {
    fun resolve(query: String, versions: List<VersionListEntry>): VersionListEntry {
        return when (query) {
            "latest", "latest-release" ->
                versions.filter { it.type == "release" }.maxByOrNull { it.releaseTime }
                    ?: throw UnknownVersionException(query)
            "latest-snapshot" ->
                versions.filter { it.type == "release" || it.type == "snapshot" }.maxByOrNull { it.releaseTime }
                    ?: throw UnknownVersionException(query)
            else -> {
                versions.find { it.id == query }?.let { return it }

                val prefixMatches = versions.filter { it.id.startsWith(query) }
                when {
                    prefixMatches.size == 1 -> prefixMatches[0]
                    prefixMatches.size > 1 -> throw AmbiguousVersionException(prefixMatches.map { it.id })
                    else -> throw UnknownVersionException(query)
                }
            }
        }
    }
}

private val UNOBFUSCATED_SINCE: Instant = Instant.parse("2025-12-16T00:00:00Z")
private val UNOBFUSCATED_IDS = setOf("c0.0.11a", "c0.0.13a")

fun isUnobfuscatedByDefault(version: VersionListEntry): Boolean {
    if (version.id in UNOBFUSCATED_IDS || version.id.startsWith("rd-")) {
        return true
    }

    return try {
        Instant.parse(version.releaseTime) >= UNOBFUSCATED_SINCE
    } catch (e: java.time.format.DateTimeParseException) {
        false
    }
}
