package io.github.ccbitz.mcsrcmcp.server

import kotlinx.serialization.Serializable
import java.util.zip.CRC32

@Serializable
data class DiffVersionsResult(
    val versionA: String,
    val versionB: String,
    val added: List<String>,
    val deleted: List<String>,
    val modified: List<String>,
    val addedCount: Int,
    val deletedCount: Int,
    val modifiedCount: Int,
)

@Serializable
data class DiffClassResult(
    val versionA: String,
    val versionB: String,
    val className: String,
    val diff: String,
)

private data class ClassEntryGroup(
    val baseName: String,
    val crcs: Map<String, Long>,
)

// CRC32 per class entry, grouped by base class name (outer class + all its inner classes),
// so a class and its inner classes diff as one unit.
private fun classEntryGroups(classes: Map<String, ByteArray>): Map<String, ClassEntryGroup> {
    val groups = mutableMapOf<String, MutableMap<String, Long>>()
    for ((internalName, bytes) in classes.entries) {
        val baseName = baseClassName(internalName)
        groups.computeIfAbsent(baseName) { mutableMapOf() }[internalName] = crc32(bytes)
    }
    return groups.mapValues { (baseName, crcs) -> ClassEntryGroup(baseName, crcs) }
}

private fun baseClassName(internalName: String): String {
    val dollar = internalName.indexOf('$')
    return if (dollar == -1) internalName else internalName.substring(0, dollar)
}

private fun crc32(bytes: ByteArray): Long {
    val crc = CRC32()
    crc.update(bytes)
    return crc.value
}

fun diffVersionsToolLogic(
    workspaceA: VersionWorkspace,
    workspaceB: VersionWorkspace,
    packagePrefix: String = "",
): DiffVersionsResult {
    val groupsA = classEntryGroups(workspaceA.remappedClasses)
    val groupsB = classEntryGroups(workspaceB.remappedClasses)

    val allBaseNames = (groupsA.keys + groupsB.keys)
        .filter { it.startsWith(packagePrefix) }
        .sorted()

    val added = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val modified = mutableListOf<String>()

    for (baseName in allBaseNames) {
        val groupA = groupsA[baseName]
        val groupB = groupsB[baseName]

        when {
            groupA == null -> added.add(baseName.replace('/', '.'))
            groupB == null -> deleted.add(baseName.replace('/', '.'))
            groupA.crcs != groupB.crcs -> modified.add(baseName.replace('/', '.'))
        }
    }

    return DiffVersionsResult(
        versionA = workspaceA.versionId,
        versionB = workspaceB.versionId,
        added = added,
        deleted = deleted,
        modified = modified,
        addedCount = added.size,
        deletedCount = deleted.size,
        modifiedCount = modified.size,
    )
}

fun diffClassToolLogic(
    workspaceA: VersionWorkspace,
    workspaceB: VersionWorkspace,
    dottedClassName: String,
    context: Int = 3,
): DiffClassResult {
    val internalName = dottedClassName.replace('.', '/')

    val sourceA = DecompileService.decompileClass(
        workspaceA.remappedClasses,
        internalName,
        cacheDir = workspaceA.cacheDir?.resolve("source/$SOURCE_CACHE_CONFIG_VERSION"),
    )
    val sourceB = DecompileService.decompileClass(
        workspaceB.remappedClasses,
        internalName,
        cacheDir = workspaceB.cacheDir?.resolve("source/$SOURCE_CACHE_CONFIG_VERSION"),
    )

    val diff = unifiedDiff(
        oldLines = sourceA.lines(),
        newLines = sourceB.lines(),
        oldLabel = "a/${internalName.replace('/', '.')}.java",
        newLabel = "b/${internalName.replace('/', '.')}.java",
        context = context,
    )

    return DiffClassResult(
        versionA = workspaceA.versionId,
        versionB = workspaceB.versionId,
        className = dottedClassName,
        diff = diff,
    )
}

// Myers O(ND) diff, then formatted as unified diff with `context` lines of context.
internal fun unifiedDiff(
    oldLines: List<String>,
    newLines: List<String>,
    oldLabel: String,
    newLabel: String,
    context: Int,
): String {
    if (oldLines == newLines) {
        return ""
    }

    val edits = myersDiff(oldLines, newLines)
    val hunks = buildHunks(edits, oldLines, newLines, context)
    if (hunks.isEmpty()) {
        return ""
    }

    val out = StringBuilder()
    out.appendLine("--- $oldLabel")
    out.appendLine("+++ $newLabel")
    for (hunk in hunks) {
        out.appendLine(hunk.header)
        for (line in hunk.lines) {
            out.appendLine(line)
        }
    }
    return out.toString().trimEnd()
}

private sealed interface Edit {
    data class Equal(val oldIndex: Int, val newIndex: Int) : Edit
    data class Delete(val oldIndex: Int) : Edit
    data class Insert(val newIndex: Int) : Edit
}

private fun myersDiff(old: List<String>, new: List<String>): List<Edit> {
    val n = old.size
    val m = new.size

    if (n == 0 && m == 0) return emptyList()
    if (n == 0) return new.indices.map { Edit.Insert(it) }
    if (m == 0) return old.indices.map { Edit.Delete(it) }

    val max = n + m
    val size = 2 * max + 1
    val v = IntArray(size)
    val trace = mutableListOf<IntArray>()

    var snake: (Int, Int) -> Int = { _, _ -> 0 }
    snake = { x, y ->
        var s = 0
        while (x + s < n && y + s < m && old[x + s] == new[y + s]) s++
        s
    }

    var found = false
    var finalX = 0
    var finalY = 0
    var finalD = 0

    outer@ for (d in 0..max) {
        trace.add(v.copyOf())
        for (k in -d..d step 2) {
            val index = k + max
            val x = when {
                k == -d || (k != d && v[index - 1] < v[index + 1]) -> v[index + 1]
                else -> v[index - 1] + 1
            }
            val y = x - k
            val s = snake(x, y)
            // The furthest point reached on this diagonal is (x+s, y+s), after the snake - not
            // (x, y). Checking (x, y) against the bounds here misses the true termination point
            // whenever the last elements of old/new match, letting the search run past it into a
            // higher d whose (x, y) overshoot n/m, which later produces out-of-bounds edit
            // indices during backtracking.
            val endX = x + s
            val endY = y + s
            v[index] = endX

            if (endX >= n && endY >= m) {
                finalX = endX
                finalY = endY
                finalD = d
                found = true
                break@outer
            }
        }
    }

    if (!found) return emptyList()

    val edits = mutableListOf<Edit>()
    var x = finalX
    var y = finalY
    for (d in finalD downTo 1) {
        val k = x - y
        val index = k + max
        val prevV = trace[d]
        val prevK = when {
            k == -d || (k != d && prevV[index - 1] < prevV[index + 1]) -> k + 1
            else -> k - 1
        }
        val prevIndex = prevK + max
        val prevX = prevV[prevIndex]
        val prevY = prevX - prevK

        while (x > prevX && y > prevY) {
            x--
            y--
            edits.add(Edit.Equal(x, y))
        }

        if (x == prevX && y > prevY) {
            y--
            edits.add(Edit.Insert(y))
        } else if (y == prevY && x > prevX) {
            x--
            edits.add(Edit.Delete(x))
        }
    }

    while (x > 0 && y > 0) {
        x--
        y--
        edits.add(Edit.Equal(x, y))
    }

    edits.reverse()
    return edits
}

private data class Hunk(
    val header: String,
    val lines: List<String>,
)

private fun buildHunks(
    edits: List<Edit>,
    oldLines: List<String>,
    newLines: List<String>,
    context: Int,
): List<Hunk> {
    if (edits.isEmpty()) return emptyList()

    val groups = mutableListOf<List<Edit>>()
    var current = mutableListOf<Edit>()

    for ((i, edit) in edits.withIndex()) {
        if (edit is Edit.Equal) {
            if (current.isNotEmpty() && (i == edits.lastIndex || edits.getOrNull(i + 1) !is Edit.Equal)) {
                current.add(edit)
                groups.add(current)
                current = mutableListOf()
            } else if (current.isNotEmpty()) {
                current.add(edit)
            }
        } else {
            current.add(edit)
        }
    }

    // Simplify: treat the whole diff as one big hunk for now, with leading/trailing context.
    // A production-quality implementation would split into multiple hunks; this is sufficient
    // for class-level diffs across Minecraft versions where changes are usually localized.
    val firstChange = edits.indexOfFirst { it !is Edit.Equal }
    val lastChange = edits.indexOfLast { it !is Edit.Equal }
    if (firstChange == -1) return emptyList()

    val start = (firstChange - context).coerceAtLeast(0)
    val end = (lastChange + context).coerceAtMost(edits.lastIndex)

    var oldLine = 1
    var newLine = 1
    for (i in 0 until start) {
        when (edits[i]) {
            is Edit.Equal -> {
                oldLine++
                newLine++
            }
            is Edit.Delete -> oldLine++
            is Edit.Insert -> newLine++
        }
    }

    val oldStart = oldLine
    val newStart = newLine
    var oldCount = 0
    var newCount = 0
    val hunkLines = mutableListOf<String>()

    for (i in start..end) {
        when (val edit = edits[i]) {
            is Edit.Equal -> {
                hunkLines.add(" " + oldLines[edit.oldIndex])
                oldCount++
                newCount++
            }
            is Edit.Delete -> {
                hunkLines.add("-" + oldLines[edit.oldIndex])
                oldCount++
            }
            is Edit.Insert -> {
                hunkLines.add("+" + newLines[edit.newIndex])
                newCount++
            }
        }
    }

    val header = "@@ -$oldStart,$oldCount +$newStart,$newCount @@"
    return listOf(Hunk(header, hunkLines))
}
