package io.github.ccbitz.mcsrcmcp.server

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class ClassSourceResult(
    val className: String,
    val source: String,
    val totalLines: Int,
    val truncated: Boolean,
    val startLine: Int,
)

fun getClassSourceToolLogic(
    remappedClasses: Map<String, ByteArray>,
    dottedClassName: String,
    startLine: Int = 1,
    maxLines: Int = 1500,
    sourceCacheDir: Path? = null,
    sources: ClassSources = DecompiledSources(remappedClasses, sourceCacheDir),
): ClassSourceResult {
    val internalName = dottedClassName.replace('.', '/')
    val fullSource = sources.source(internalName)
    val lines = fullSource.lines()
    val totalLines = lines.size
    val fromIndex = (startLine - 1).coerceIn(0, lines.size)
    val toIndex = (fromIndex + maxLines).coerceAtMost(lines.size)

    return ClassSourceResult(
        className = dottedClassName,
        source = lines.subList(fromIndex, toIndex).joinToString("\n"),
        totalLines = totalLines,
        truncated = toIndex < totalLines,
        startLine = startLine,
    )
}
