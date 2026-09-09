package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData

// Transitive superclass + interfaces walk: both parents count, breadth-first, cycle-safe via a
// visited set (real bytecode is acyclic, but this stays correct even so).
internal fun ancestorsOf(indexData: IndexData, internalName: String): List<String> {
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    val result = mutableListOf<String>()

    val classData = indexData.classes()[internalName]
    classData?.superName()?.let { queue.add(it) }
    classData?.interfaces()?.forEach { queue.add(it) }

    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()
        if (!visited.add(next)) continue
        result.add(next)
        val nextData = indexData.classes()[next] ?: continue
        nextData.superName()?.let { queue.add(it) }
        nextData.interfaces().forEach { queue.add(it) }
    }

    return result
}
