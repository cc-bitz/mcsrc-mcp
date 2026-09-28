package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class MethodSourceResult(
    // The class that declares the method - an ancestor of the one asked about when it is inherited.
    val className: String,
    val member: String,
    val source: String,
    // Numbered as in the declaring class's own decompiled source (for an inner class, its outer
    // class's), so they mean the same to get_class_source and find_declaration.
    val startLine: Int,
    // The last line shown; methodEndLine is where the method really ends when truncated.
    val endLine: Int,
    val methodEndLine: Int,
    val totalLines: Int,
    val truncated: Boolean,
)

sealed interface MethodSourceOutcome {

    data class Found(val result: MethodSourceResult) : MethodSourceOutcome

    data class Ambiguous(val candidates: List<String>) : MethodSourceOutcome

}

/**
 * One method's decompiled source, cut out of its class's. The method is located by the
 * declaration token Vineflower emits for it, not by searching the text for its name, so an
 * overload can't be mistaken for another and a call that merely mentions the name is never
 * matched. [member] is a bare name, a "name(desc)" candidate, "<init>" or the class's own simple
 * name for a constructor. A name not declared on the class is looked up the inheritance chain, the
 * way find_references resolves one.
 *
 * Candidates for a bare name come from the source, not the index: the index also holds the
 * synthetic bridges a covariant override leaves behind, which Vineflower hides, and listing a
 * method there is no source for would be a candidate that can never be shown.
 *
 * @throws ClassNotFoundInIndexException If [dottedClassName] is not in this version's index.
 * @throws MemberNotFoundException If no class in the chain declares a method by that name.
 */
fun getMethodSourceToolLogic(
    indexData: IndexData,
    remappedClasses: Map<String, ByteArray>,
    dottedClassName: String,
    member: String,
    maxLines: Int = 1500,
    sourceCacheDir: Path? = null,
): MethodSourceOutcome {
    val internalName = dottedClassName.replace('.', '/')
    if (internalName !in indexData.classes()) {
        throw ClassNotFoundInIndexException(dottedClassName)
    }

    val parsed = MemberQuery.parse(member)
    val simpleName = internalName.substringAfterLast('/').substringAfterLast('$')
    val query = if (parsed.name == simpleName) parsed.copy(name = "<init>") else parsed
    // Constructors aren't inherited: a superclass's would be the wrong answer, not a fallback.
    val owners = if (query.name == "<init>") listOf(internalName) else listOf(internalName) + ancestorsOf(indexData, internalName)

    for (owner in owners) {
        val declares = indexData.members()[owner]?.methods()?.any { it.name() == query.name } == true
        // Out-of-jar ancestors (the JDK) are in the index's hierarchy but have no bytes to decompile.
        if (!declares || owner !in remappedClasses) continue

        val decompiled = DecompileService.decompileWithTokens(remappedClasses, owner, cacheDir = sourceCacheDir)
        val declarations = decompiled.tokens.filter { token ->
            token.declaration && token.kind == TokenKind.METHOD && token.className == owner &&
                token.member?.name == query.name && (query.methodDesc == null || token.member.descriptor == query.methodDesc)
        }
        return when (declarations.size) {
            0 -> continue
            1 -> MethodSourceOutcome.Found(extract(decompiled.source, declarations.single(), owner, maxLines))
            else -> MethodSourceOutcome.Ambiguous(declarations.map { "${it.member!!.name}${it.member.descriptor}" }.sorted())
        }
    }
    throw MemberNotFoundException(dottedClassName, member)
}

private fun extract(source: String, declaration: SourceToken, owner: String, maxLines: Int): MethodSourceResult {
    val lines = LineIndex(source)
    val nameLine = lines.lineOf(declaration.start)

    // Annotations and comments sit on their own lines right above the signature and belong to it.
    var startLine = nameLine
    while (startLine > 1) {
        val above = lines.textOf(startLine - 1).trim()
        if (above.startsWith("@") || above.startsWith("//") || above.startsWith("/*") || above.startsWith("*")) startLine--
        else break
    }

    val methodEndLine = lines.lineOf(methodEnd(source, declaration.start + declaration.length))
    val endLine = minOf(methodEndLine, startLine + maxLines - 1)
    val shown = (startLine..endLine).joinToString("\n") { lines.textOf(it).trimEnd('\n', '\r') }
    // TokenMember is non-null on a method declaration token: getMethodSourceToolLogic matched on it.
    val member = declaration.member!!
    return MethodSourceResult(
        className = owner.replace('/', '.'),
        member = "${member.name}${member.descriptor}",
        source = shown,
        startLine = startLine,
        endLine = endLine,
        methodEndLine = methodEndLine,
        totalLines = lines.count,
        truncated = endLine < methodEndLine,
    )
}

/**
 * The offset of the last character of a method whose name ends at [from]: the ';' of a body-less
 * declaration, or the brace closing its body. Braces are only counted outside string, char and
 * text-block literals and comments - decompiled code is full of "{" in strings - and the signature's
 * own parentheses are skipped, since an annotation argument inside them can hold braces too.
 */
internal fun methodEnd(source: String, from: Int): Int {
    var i = from
    var parens = 0
    var braces = 0
    while (i < source.length) {
        val c = source[i]
        when {
            source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it }
            source.startsWith("/*", i) -> i = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 1 }
            source.startsWith("\"\"\"", i) -> i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 2 }
            c == '"' || c == '\'' -> i = literalEnd(source, i)
            c == '(' -> parens++
            c == ')' -> parens--
            parens > 0 -> {}
            c == ';' && braces == 0 -> return i
            c == '{' -> braces++
            c == '}' -> if (--braces == 0) return i
        }
        i++
    }
    return source.length - 1
}

// The index of the quote closing the literal opened at [start], stepping over escapes.
private fun literalEnd(source: String, start: Int): Int {
    val quote = source[start]
    var i = start + 1
    while (i < source.length && source[i] != quote) {
        if (source[i] == '\\') i++
        i++
    }
    return i
}
