package io.github.ccbitz.mcsrcmcp.server

import kotlinx.serialization.Serializable

/**
 * All hits in one class or asset. Results used to be a flat list of matches, each repeating the
 * full dotted path and the entry's nLines - at a limit of 100 that framing was most of the payload.
 * Grouping states both once and leaves line-number -> text.
 */
@Serializable
data class PathHits(
    val path: String,
    val nLines: Int,
    val lines: Map<String, String>,
)

@Serializable
data class SearchResults(
    val results: List<PathHits>,
    val shown: Int,
    // "12" when every hit was counted, "40+" when the scan stopped at the limit. Enough to tell a
    // too-broad query from a finished one without paying for the rest of the matches.
    val matches: String,
    val truncated: Boolean,
)

internal fun SearchScan.toResults(): SearchResults {
    val grouped = LinkedHashMap<String, MutableMap<String, String>>()
    val lineCounts = HashMap<String, Int>()
    for (match in matches) {
        grouped.getOrPut(match.path) { LinkedHashMap() }[match.line.toString()] = match.text
        lineCounts[match.path] = match.nLines
    }

    return SearchResults(
        results = grouped.map { (path, lines) -> PathHits(path, lineCounts.getValue(path), lines) },
        shown = matches.size,
        matches = countLabel(),
        truncated = !exhaustive,
    )
}

fun searchCodeToolLogic(
    index: SearchIndex,
    query: String,
    useRegex: Boolean = false,
    limit: Int = 100,
    classNames: List<String>? = null,
    exclude: String? = null,
    exactCount: Boolean = false,
): SearchResults =
    index.search(
        query,
        useRegex,
        limit,
        includeSource = true,
        includeAssets = false,
        paths = classNames?.toSet(),
        exclude = exclude,
        countAll = exactCount,
    ).toResults()
