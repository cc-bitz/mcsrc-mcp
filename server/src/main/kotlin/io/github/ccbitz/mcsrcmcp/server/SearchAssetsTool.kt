package io.github.ccbitz.mcsrcmcp.server

fun searchAssetsToolLogic(
    index: SearchIndex,
    query: String,
    useRegex: Boolean = false,
    limit: Int = 100,
    assetPaths: List<String>? = null,
    exclude: String? = null,
    exactCount: Boolean = false,
): SearchResults =
    index.search(
        query,
        useRegex,
        limit,
        includeSource = false,
        includeAssets = true,
        paths = assetPaths?.toSet(),
        exclude = exclude,
        countAll = exactCount,
    ).toResults()
