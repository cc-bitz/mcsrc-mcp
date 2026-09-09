package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import kotlinx.serialization.Serializable

@Serializable
data class HierarchyNode(val className: String, val children: List<HierarchyNode>)

@Serializable
data class HierarchyResult(val className: String, val direction: String, val root: HierarchyNode)

class InvalidHierarchyDirectionException(direction: String) :
    IllegalArgumentException("direction must be 'supertypes' or 'subtypes', got '$direction'")

fun getHierarchyToolLogic(
    indexData: IndexData,
    dottedClassName: String,
    direction: String = "supertypes",
    depth: Int = 1,
): HierarchyResult {
    val internalName = dottedClassName.replace('.', '/')
    if (internalName !in indexData.classes()) {
        throw ClassNotFoundInIndexException(dottedClassName)
    }
    if (direction != "supertypes" && direction != "subtypes") {
        throw InvalidHierarchyDirectionException(direction)
    }

    // Only built (and only walked) for "subtypes" - "supertypes" reads ClassData directly and
    // needs no reverse index.
    val childrenOf: Map<String, List<String>> = if (direction == "subtypes") buildChildrenMap(indexData) else emptyMap()

    fun buildNode(current: String, remainingDepth: Int): HierarchyNode {
        val nextNames = if (direction == "supertypes") {
            val classData = indexData.classes()[current]
            val candidates = listOfNotNull(classData?.superName()) + (classData?.interfaces() ?: emptyList())
            // Only link to a supertype that is ITSELF indexed. java.lang.Object/java.lang.Runnable/etc.
            // are never indexed, so walking "up" naturally stops there rather than producing a
            // dangling node.
            candidates.filter { it in indexData.classes() }
        } else {
            childrenOf[current] ?: emptyList()
        }

        val children = if (remainingDepth <= 0) emptyList() else nextNames.map { buildNode(it, remainingDepth - 1) }
        return HierarchyNode(current.replace('/', '.'), children)
    }

    return HierarchyResult(dottedClassName, direction, buildNode(internalName, depth))
}

private fun buildChildrenMap(indexData: IndexData): Map<String, List<String>> {
    val result = mutableMapOf<String, MutableList<String>>()
    for ((name, classData) in indexData.classes()) {
        val parents = listOfNotNull(classData.superName()) + classData.interfaces()
        for (parent in parents) {
            if (parent in indexData.classes()) {
                result.getOrPut(parent) { mutableListOf() }.add(name)
            }
        }
    }
    return result
}
