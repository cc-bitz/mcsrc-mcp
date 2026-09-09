package io.github.ccbitz.mcsrcmcp.server

import kotlinx.serialization.Serializable

@Serializable
data class PackageListingResult(
    val subpackages: List<String>,
    val classes: List<String>,
    val truncated: Boolean,
)

fun listPackageToolLogic(
    classNames: Collection<String>,
    dottedPackage: String,
    recursive: Boolean = false,
    limit: Int = 200,
): PackageListingResult {
    val prefix = if (dottedPackage.isEmpty()) "" else dottedPackage.replace('.', '/') + "/"

    val subpackages = sortedSetOf<String>()
    val classes = sortedSetOf<String>()

    for (internalName in classNames) {
        if ('$' in internalName || !internalName.startsWith(prefix)) {
            continue // inner classes are reached via their outer class, not listed separately
        }

        val rest = internalName.removePrefix(prefix)
        val slashIndex = rest.indexOf('/')
        when {
            slashIndex == -1 -> classes.add(rest.replace('/', '.'))
            recursive -> classes.add(rest.replace('/', '.'))
            else -> subpackages.add(rest.substring(0, slashIndex))
        }
    }

    val limitedClasses = classes.take(limit)
    return PackageListingResult(
        subpackages = subpackages.toList(),
        classes = limitedClasses,
        truncated = classes.size > limit,
    )
}
