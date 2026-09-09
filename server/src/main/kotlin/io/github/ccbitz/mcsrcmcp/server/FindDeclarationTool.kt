package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.BytecodePrinter
import io.github.ccbitz.mcsrcmcp.core.IndexData
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.Arrays

@Serializable
data class DeclarationTarget(
    val symbol: String,
    val kind: TokenKind,
    val column: Int,
    val owner: String,
    // Null for a local or parameter, which no class declares, and for a member whose owner is
    // outside the jar (a JDK type): there is nothing here to walk to.
    val declaringClass: String?,
    val member: String?,
    val declarationLine: Int?,
    val declarationSnippet: String?,
)

@Serializable
data class FindDeclarationResult(
    val className: String,
    val line: Int,
    val targets: List<DeclarationTarget>,
)

class LineOutOfRangeException(className: String, line: Int, totalLines: Int) :
    IllegalArgumentException("line $line is outside $className, which has $totalLines lines")

/**
 * Where a symbol was seen. The two spaces number their lines differently and are read from
 * different tools, so they are separate cases rather than a pair of nullable line arguments that
 * could be passed together or not at all.
 */
sealed interface UseSite {
    /** A line as numbered by get_class_source. */
    data class SourceLine(val line: Int, val symbol: String? = null, val column: Int? = null) : UseSite

    /** A line as numbered by get_bytecode. */
    data class BytecodeLine(val line: Int) : UseSite
}

/**
 * Resolves what the symbols at a use site refer to, and where each one is declared - the "go to
 * declaration" an IDE gives you on ctrl+click.
 *
 * Every symbol comes from Vineflower's own token stream, so the owner and descriptor are what the
 * decompiler resolved rather than anything re-parsed out of the text: two same-named calls to
 * different classes on one line stay distinct. A [UseSite.SourceLine] with neither symbol nor
 * column returns every symbol on the line in column order, which is what lets a caller pick one
 * without having to know in advance what is there.
 *
 * @throws ClassNotFoundInIndexException If [dottedClassName] is not in this version's index.
 * @throws LineOutOfRangeException If the requested line is past the end of the class.
 */
fun findDeclarationToolLogic(
    indexData: IndexData,
    remappedClasses: Map<String, ByteArray>,
    dottedClassName: String,
    useSite: UseSite,
    sourceCacheDir: Path? = null,
): FindDeclarationResult {
    val internalName = dottedClassName.replace('.', '/')
    if (internalName !in indexData.classes()) {
        throw ClassNotFoundInIndexException(dottedClassName)
    }

    return when (useSite) {
        is UseSite.SourceLine -> fromSource(indexData, remappedClasses, dottedClassName, internalName, useSite, sourceCacheDir)
        is UseSite.BytecodeLine -> fromBytecode(indexData, remappedClasses, dottedClassName, internalName, useSite, sourceCacheDir)
    }
}

private fun fromSource(
    indexData: IndexData,
    remappedClasses: Map<String, ByteArray>,
    dottedClassName: String,
    internalName: String,
    useSite: UseSite.SourceLine,
    sourceCacheDir: Path?,
): FindDeclarationResult {
    val (line, symbol, column) = useSite
    val lookup = DeclarationLookup(remappedClasses, sourceCacheDir)
    val decompiled = DecompileService.decompileWithTokens(remappedClasses, internalName, cacheDir = sourceCacheDir)
    val lineIndex = LineIndex(decompiled.source)
    val lineStart = lineIndex.startOf(line) ?: throw LineOutOfRangeException(dottedClassName, line, lineIndex.count)
    val lineEnd = lineIndex.endOf(line)

    val targets = decompiled.tokens
        .filter { it.start >= lineStart && it.start < lineEnd }
        .filter { symbol == null || decompiled.source.substring(it.start, it.start + it.length) == symbol }
        .filter { column == null || (column >= it.start - lineStart + 1 && column < it.start - lineStart + 1 + it.length) }
        .map { token ->
            val declaringClass = declaringClassOf(indexData, token)
            val site = declaringClass?.let { lookup.siteOf(it, token) }
            DeclarationTarget(
                symbol = decompiled.source.substring(token.start, token.start + token.length),
                kind = token.kind,
                column = token.start - lineStart + 1,
                owner = token.className.replace('/', '.'),
                declaringClass = declaringClass?.replace('/', '.'),
                member = token.member?.let { formatMember(token.kind, it) },
                declarationLine = site?.line,
                declarationSnippet = site?.snippet,
            )
        }

    return FindDeclarationResult(dottedClassName, line, targets.sortedBy { it.column })
}

// ASM's Textifier writes one instruction per line in a fixed shape: "INVOKEVIRTUAL owner.name
// (desc)" for methods (with a trailing "(itf)" on interface calls), "GETFIELD owner.name : desc"
// for fields, and a bare internal name for type instructions. Reading the printed text rather than
// re-walking the class with ASM is what keeps the line numbers the caller quotes and the operands
// resolved here in step - get_bytecode prints through this same printer.
private val METHOD_INSN = Regex("""^\s*INVOKE(?:VIRTUAL|SPECIAL|STATIC|INTERFACE)\s+([\w/$]+)\.([\w<>$]+)\s+(\S+)""")
private val FIELD_INSN = Regex("""^\s*(?:GET|PUT)(?:FIELD|STATIC)\s+([\w/$]+)\.([\w$]+)\s*:\s*(\S+)""")
private val TYPE_INSN = Regex("""^\s*(?:NEW|CHECKCAST|INSTANCEOF|ANEWARRAY)\s+([\w/$]+)""")

private fun fromBytecode(
    indexData: IndexData,
    remappedClasses: Map<String, ByteArray>,
    dottedClassName: String,
    internalName: String,
    useSite: UseSite.BytecodeLine,
    sourceCacheDir: Path?,
): FindDeclarationResult {
    val bytes = remappedClasses[internalName] ?: throw ClassNotFoundInIndexException(dottedClassName)
    val lines = BytecodePrinter.print(*arrayOf(bytes)).lines()
    val text = lines.getOrNull(useSite.line - 1) ?: throw LineOutOfRangeException(dottedClassName, useSite.line, lines.size)

    // A token, not a real one: it never came from a decompile, so its offsets would be meaningless
    // and are left at zero. Everything downstream only reads the kind, owner and member.
    val token = referenceOn(text) ?: return FindDeclarationResult(dottedClassName, useSite.line, emptyList())

    val declaringClass = declaringClassOf(indexData, token)
    val site = declaringClass?.let { DeclarationLookup(remappedClasses, sourceCacheDir).siteOf(it, token) }
    val target = DeclarationTarget(
        symbol = token.member?.name ?: token.className.substringAfterLast('/'),
        kind = token.kind,
        column = text.indexOf(token.className) + 1,
        owner = token.className.replace('/', '.'),
        declaringClass = declaringClass?.replace('/', '.'),
        member = token.member?.let { formatMember(token.kind, it) },
        declarationLine = site?.line,
        declarationSnippet = site?.snippet,
    )

    return FindDeclarationResult(dottedClassName, useSite.line, listOf(target))
}

private fun referenceOn(text: String): SourceToken? {
    fun token(kind: TokenKind, className: String, member: TokenMember?) =
        SourceToken(0, 0, kind, className, member, declaration = false)

    METHOD_INSN.find(text)?.let {
        val (owner, name, desc) = it.destructured
        return token(TokenKind.METHOD, owner, TokenMember(name, desc))
    }
    FIELD_INSN.find(text)?.let {
        val (owner, name, desc) = it.destructured
        return token(TokenKind.FIELD, owner, TokenMember(name, desc))
    }
    TYPE_INSN.find(text)?.let {
        val (owner) = it.destructured
        return token(TokenKind.CLASS, owner, null)
    }
    return null
}

// find_references reports members the same way, so a member read off one tool can be handed
// straight to the other.
private fun formatMember(kind: TokenKind, member: TokenMember): String =
    if (kind == TokenKind.FIELD) "${member.name}: ${member.descriptor}" else "${member.name}${member.descriptor}"

// The owner on a token is the call site's static type, which is often not what declares the
// member: blockItem.getMaxStackSize() has owner BlockItem and declaration Item. Same walk
// find_references does.
private fun declaringClassOf(indexData: IndexData, token: SourceToken): String? {
    fun declares(internalName: String): Boolean {
        val member = token.member ?: return false
        val memberData = indexData.members()[internalName] ?: return false
        // Entry.Field and Entry.Method are separate records under a marker supertype that carries
        // no accessors, so there is no one set to search.
        return if (token.kind == TokenKind.FIELD) {
            memberData.fields().any { it.name() == member.name && it.desc() == member.descriptor }
        } else {
            memberData.methods().any { it.name() == member.name && it.desc() == member.descriptor }
        }
    }

    return when (token.kind) {
        // A class token declares itself; there is nothing to resolve.
        TokenKind.CLASS -> token.className

        TokenKind.FIELD, TokenKind.METHOD ->
            if (declares(token.className)) token.className
            else ancestorsOf(indexData, token.className).firstOrNull { declares(it) }

        // A local or parameter is declared inside a method body, not by a class. Vineflower emits
        // an index for these that would pin the exact declaration, but the token model here drops
        // it, so they are reported as use sites without a declaration site rather than guessed at.
        // TODO: carry the variable index on local/parameter tokens and resolve these too.
        TokenKind.PARAMETER, TokenKind.LOCAL -> null
    }
}

private class DeclarationSite(val line: Int, val snippet: String)

/**
 * Finds where a member is declared in its declaring class's own source, decompiling that class at
 * most once however many symbols on the line resolve into it - which is the common case, since a
 * line usually works against one or two types.
 */
private class DeclarationLookup(
    private val remappedClasses: Map<String, ByteArray>,
    private val sourceCacheDir: Path?,
) {
    private val decompiled = mutableMapOf<String, DecompiledClass?>()
    private val lineIndexes = mutableMapOf<String, LineIndex>()

    fun siteOf(declaringInternalName: String, token: SourceToken): DeclarationSite? {
        val source = decompiled.getOrPut(declaringInternalName) {
            try {
                DecompileService.decompileWithTokens(remappedClasses, declaringInternalName, cacheDir = sourceCacheDir)
            } catch (e: ClassNotFoundInIndexException) {
                // A JDK or otherwise out-of-jar type: the owner is still worth reporting, the
                // source is just not ours to show.
                null
            }
        } ?: return null

        val declaration = source.tokens.firstOrNull {
            it.declaration && it.kind == token.kind && it.className == declaringInternalName && it.member == token.member
        } ?: return null

        val lineIndex = lineIndexes.getOrPut(declaringInternalName) { LineIndex(source.source) }
        val line = lineIndex.lineOf(declaration.start)
        return DeclarationSite(line, lineIndex.textOf(line).trim())
    }
}

/**
 * Line starts for one source text, so offset-to-line runs as a binary search rather than by
 * counting newlines per token. Every call resolves several tokens against the same source, and
 * find_declaration is on the interactive path.
 */
private class LineIndex(private val source: String) {
    private val starts = buildList {
        add(0)
        source.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }.toIntArray()

    val count: Int get() = starts.size

    fun startOf(line: Int): Int? = if (line in 1..starts.size) starts[line - 1] else null

    fun endOf(line: Int): Int = if (line < starts.size) starts[line] else source.length

    fun textOf(line: Int): String {
        val start = startOf(line) ?: return ""
        return source.substring(start, endOf(line))
    }

    fun lineOf(offset: Int): Int {
        val found = Arrays.binarySearch(starts, offset)
        // binarySearch returns the insertion point negated when there is no exact hit; an offset
        // inside a line belongs to the line that starts before it.
        return if (found >= 0) found + 1 else -found - 1
    }
}
