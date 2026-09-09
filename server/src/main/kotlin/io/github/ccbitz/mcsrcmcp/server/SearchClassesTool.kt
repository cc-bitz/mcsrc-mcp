package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.MemberData
import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(
    val className: String,
    val size: Int,
    val nMethods: Int,
    val nFields: Int,
)

private fun camelCaseAcronym(name: String): String = name.filter { it.isUpperCase() }

private fun matchesCamelCase(simpleName: String, query: String): Boolean =
    camelCaseAcronym(simpleName).lowercase().startsWith(query.lowercase())

private data class Scored(val internalName: String, val score: Int, val simpleName: String)

// Filters, scores and sorts class names for a query; inner classes are excluded by design.
// remappedClasses/members are optional and default empty so a caller with no workspace data
// (e.g. a test exercising only the ranking logic) doesn't have to supply them - results just
// carry zeros for size/counts in that case.
fun searchClassesToolLogic(
    classNames: Collection<String>,
    query: String,
    limit: Int = 100,
    remappedClasses: Map<String, ByteArray> = emptyMap(),
    members: Map<String, MemberData> = emptyMap(),
): List<SearchResult> {
    if (query.isEmpty()) return emptyList()

    val lowerQuery = query.lowercase()

    return classNames
        .asSequence()
        .filter { '$' !in it }
        .mapNotNull { internalName ->
            val simpleName = internalName.substringAfterLast('/')
            val lowerSimple = simpleName.lowercase()

            if (!(lowerSimple.contains(lowerQuery) || matchesCamelCase(simpleName, query))) {
                return@mapNotNull null
            }

            val score = when {
                lowerSimple == lowerQuery -> 0
                lowerSimple.startsWith(lowerQuery) -> 1
                camelCaseAcronym(simpleName).lowercase() == lowerQuery -> 2
                matchesCamelCase(simpleName, query) -> 3
                else -> 4 + lowerSimple.indexOf(lowerQuery)
            }

            Scored(internalName, score, simpleName)
        }
        .sortedWith(compareBy({ it.score }, { it.simpleName.length }, { it.simpleName }))
        .take(limit)
        .map {
            SearchResult(
                className = it.internalName.replace('/', '.'),
                size = remappedClasses[it.internalName]?.size ?: 0,
                nMethods = members[it.internalName]?.methods()?.size ?: 0,
                nFields = members[it.internalName]?.fields()?.size ?: 0,
            )
        }
        .toList()
}
