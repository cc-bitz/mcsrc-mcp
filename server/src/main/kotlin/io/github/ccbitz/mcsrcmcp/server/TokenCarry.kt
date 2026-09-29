package io.github.ccbitz.mcsrcmcp.server

/**
 * Tokens that survived a patch, and the output lines that have none carried onto them because the
 * patch wrote them: those still need resolving some other way (see [BytecodeTokens]).
 */
data class CarriedTokens(val tokens: List<SourceToken>, val uncoveredLines: Set<Int>)

/**
 * Moves [tokens], recorded against [oldText], onto [newText]: a token keeps its column and follows its
 * line when the patch copied the line unchanged ([originFor], from [SourcePatch]), and is dropped
 * when the line was removed or rewritten. A line the patch rewrote keeps no token at all, not even
 * one whose text still happens to be there - its resolution may have changed with it.
 */
fun carryTokens(oldText: String, tokens: List<SourceToken>, newText: String, originFor: IntArray): CarriedTokens {
    val oldLines = LineIndex(oldText)
    val newLines = LineIndex(newText)
    val newLineFor = IntArray(oldLines.count + 1)
    val uncovered = HashSet<Int>()
    for ((index, origin) in originFor.withIndex()) {
        if (origin == 0) uncovered.add(index + 1) else newLineFor[origin] = index + 1
    }

    val carried = tokens.mapNotNull { token ->
        val oldLine = oldLines.lineOf(token.start)
        val newLine = newLineFor.getOrElse(oldLine) { 0 }
        if (newLine == 0) return@mapNotNull null
        // Both starts exist: oldLine is a line of oldText, newLine one of newText.
        val column = token.start - oldLines.startOf(oldLine)!!
        token.copy(start = newLines.startOf(newLine)!! + column)
    }
    return CarriedTokens(carried, uncovered)
}

/** Lines of text as an editor numbers them: a trailing newline ends the last line, it doesn't start one. */
fun lineCount(text: String): Int =
    if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1

/**
 * The output lines of a patch that were copied from one of [lines] - an earlier patch's uncovered
 * lines stay uncovered wherever the next patch moves them.
 */
fun carryLines(lines: Set<Int>, originFor: IntArray): Set<Int> {
    if (lines.isEmpty()) return emptySet()
    val carried = HashSet<Int>()
    for ((index, origin) in originFor.withIndex()) if (origin in lines) carried.add(index + 1)
    return carried
}

/** Writes tokens in the format [parseSidecarTokens] reads. */
fun formatSidecarTokens(tokens: List<SourceToken>): String =
    tokens.joinToString("") { t ->
        val kind = SIDECAR_KINDS.entries.first { it.value == t.kind }.key
        "${t.start}\t${t.length}\t$kind\t${t.className}\t${t.member?.name ?: "-"}\t${t.member?.descriptor ?: "-"}\t${if (t.declaration) 1 else 0}\n"
    }

private val SIDECAR_KINDS = mapOf(
    "class" to TokenKind.CLASS,
    "field" to TokenKind.FIELD,
    "method" to TokenKind.METHOD,
    "parameter" to TokenKind.PARAMETER,
    "local" to TokenKind.LOCAL,
)

/**
 * Reads the decompiler sidecar's `.tokens` format: one token per line, `start, length, kind, className,
 * memberName, memberDescriptor, declaration(1/0)` tab-separated, with "-" for an absent member.
 */
fun parseSidecarTokens(text: String): List<SourceToken> =
    text.lineSequence().filter { it.isNotBlank() }.map { line ->
        val f = line.split('\t')
        require(f.size == 7) { "malformed token line: $line" }
        val kind = SIDECAR_KINDS[f[2]] ?: throw IllegalArgumentException("unknown token kind in: $line")
        val member = if (f[4] == "-") null else TokenMember(f[4], f[5])
        SourceToken(f[0].toInt(), f[1].toInt(), kind, f[3], member, declaration = f[6] == "1")
    }.toList()
