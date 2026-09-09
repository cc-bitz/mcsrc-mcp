package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.BytecodePrinter
import kotlinx.serialization.Serializable

@Serializable
data class BytecodeResult(
    val className: String,
    val bytecode: String,
    val totalLines: Int,
    val truncated: Boolean,
    val startLine: Int,
)

fun getBytecodeToolLogic(
    remappedClasses: Map<String, ByteArray>,
    dottedClassName: String,
    startLine: Int = 1,
    maxLines: Int = 1500,
): BytecodeResult {
    val internalName = dottedClassName.replace('.', '/')
    val bytes = remappedClasses[internalName] ?: throw ClassNotFoundInIndexException(dottedClassName)

    // BytecodePrinter.print is a Java varargs method (byte[]... classes); from Kotlin that
    // requires the spread operator over an Array<ByteArray>, not just the array itself.
    val text = BytecodePrinter.print(*arrayOf(bytes))
    val lines = text.lines()
    val totalLines = lines.size
    val fromIndex = (startLine - 1).coerceIn(0, lines.size)
    val toIndex = (fromIndex + maxLines).coerceAtMost(lines.size)

    return BytecodeResult(
        className = dottedClassName,
        bytecode = lines.subList(fromIndex, toIndex).joinToString("\n"),
        totalLines = totalLines,
        truncated = toIndex < totalLines,
        startLine = startLine,
    )
}
