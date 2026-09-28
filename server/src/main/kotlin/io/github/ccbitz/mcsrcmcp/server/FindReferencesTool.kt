package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import io.github.ccbitz.mcsrcmcp.core.Indexer
import kotlinx.serialization.Serializable

/**
 * One caller class and every member of it that references the target. References used to be a flat
 * list, each repeating its caller's name and stats - on a hub like CraftPlayer.getHandle(), whose
 * callers are mostly CraftPlayer itself, that framing was most of the payload. Same fix as
 * search_code's per-class grouping.
 */
@Serializable
data class CallerHits(
    val callerClass: String,
    // The caller's own bytecode size/member counts - context for deciding whether to read the
    // whole caller class before diving in. Zero when remappedClasses wasn't supplied.
    val size: Int,
    val nMethods: Int,
    val nFields: Int,
    val members: List<String>,
)

@Serializable
data class FindReferencesResult(
    val targetClass: String,
    val targetMember: String?,
    val declaringClass: String,
    val declaringClassSize: Int,
    val declaringClassNMethods: Int,
    val declaringClassNFields: Int,
    val results: List<CallerHits>,
    val shown: Int,
    // Always exact, unlike search_code's "40+": references are a complete set already in memory,
    // so counting all of them costs nothing - and it is what makes offset paging possible.
    val total: Int,
    val truncated: Boolean,
    // Where the next page starts; null on the last one.
    val nextOffset: Int?,
)

/**
 * Which slice of the references to return: [limit] of them from [offset], in (caller class,
 * member) order, after dropping any whose "callerClass#member" matches [exclude] - the same
 * always-a-regex `rg -v` search_code's exclude is.
 */
internal class ReferencePaging(val limit: Int = 100, val offset: Int = 0, exclude: String? = null) {

    private val exclude = exclude?.takeIf { it.isNotEmpty() }?.let { LineMatcher.of(it, useRegex = true) }

    fun excludes(callerClass: String, member: String): Boolean = exclude?.matches("$callerClass#$member") == true

}

sealed interface FindReferencesOutcome {
    data class Found(val result: FindReferencesResult) : FindReferencesOutcome
    data class AmbiguousMember(val candidates: List<String>) : FindReferencesOutcome
}

class MemberNotFoundException(className: String, memberName: String) :
    NoSuchElementException("member '$memberName' not found on $className or its ancestors")

fun findReferencesToolLogic(
    indexData: IndexData,
    referenceIndexer: Indexer,
    dottedClassName: String,
    memberName: String? = null,
    kind: String? = null,
    resolveDeclaration: Boolean = true,
    remappedClasses: Map<String, ByteArray> = emptyMap(),
    limit: Int = 100,
    offset: Int = 0,
    exclude: String? = null,
): FindReferencesOutcome {
    val internalName = dottedClassName.replace('.', '/')
    if (internalName !in indexData.classes()) {
        throw ClassNotFoundInIndexException(dottedClassName)
    }
    val paging = ReferencePaging(limit, offset, exclude)

    if (memberName == null) {
        return FindReferencesOutcome.Found(
            resultOf(indexData, remappedClasses, dottedClassName, null, internalName, referenceIndexer.references(internalName), paging),
        )
    }

    when (val resolved = resolveMember(indexData, internalName, memberName, kind)) {
        is MemberResolution.Ambiguous -> return FindReferencesOutcome.AmbiguousMember(resolved.candidates)
        is MemberResolution.Found -> return foundReferences(indexData, referenceIndexer, dottedClassName, internalName, resolved, remappedClasses, paging)
        MemberResolution.NotFound -> {
            if (!resolveDeclaration) {
                throw MemberNotFoundException(dottedClassName, memberName)
            }

            for (ancestor in ancestorsOf(indexData, internalName)) {
                when (val ancestorResolved = resolveMember(indexData, ancestor, memberName, kind)) {
                    is MemberResolution.Ambiguous -> return FindReferencesOutcome.AmbiguousMember(ancestorResolved.candidates)
                    is MemberResolution.Found ->
                        return foundReferences(indexData, referenceIndexer, dottedClassName, ancestor, ancestorResolved, remappedClasses, paging)
                    MemberResolution.NotFound -> continue
                }
            }

            throw MemberNotFoundException(dottedClassName, memberName)
        }
    }
}

// Bytecode size (free, already in memory) and method/field counts (free, already in the index) -
// context for the caller to decide whether reading the whole class is worth it. Zero when
// remappedClasses is empty, e.g. a caller that only has IndexData/Indexer to hand.
private fun classStats(indexData: IndexData, remappedClasses: Map<String, ByteArray>, internalName: String): Triple<Int, Int, Int> {
    val size = remappedClasses[internalName]?.size ?: 0
    val members = indexData.members()[internalName]
    return Triple(size, members?.methods()?.size ?: 0, members?.fields()?.size ?: 0)
}

private fun foundReferences(
    indexData: IndexData,
    referenceIndexer: Indexer,
    dottedTargetClass: String,
    declaringInternalName: String,
    resolved: MemberResolution.Found,
    remappedClasses: Map<String, ByteArray>,
    paging: ReferencePaging,
): FindReferencesOutcome.Found {
    val key = "$declaringInternalName:${resolved.name}:${resolved.desc}"
    // The same shape the candidates are listed in, so it can be passed straight back.
    val targetMember = if (resolved.isField) "${resolved.name}: ${resolved.desc}" else "${resolved.name}${resolved.desc}"
    return FindReferencesOutcome.Found(
        resultOf(indexData, remappedClasses, dottedTargetClass, targetMember, declaringInternalName, referenceIndexer.references(key), paging),
    )
}

// Reference values are "m:owner:name:desc" (method-body/signature-driven) or "f:owner:name:desc"
// (a field declaration whose own type is the referenced class) - never assume method-only.
private fun resultOf(
    indexData: IndexData,
    remappedClasses: Map<String, ByteArray>,
    dottedTargetClass: String,
    targetMember: String?,
    declaringInternalName: String,
    rawReferences: Set<String>,
    paging: ReferencePaging,
): FindReferencesResult {
    class Ref(val ownerInternalName: String, val callerClass: String, val member: String)

    // Sorted so a page means the same thing on every call; the set underneath has no order.
    val refs = rawReferences
        .map { raw ->
            val parts = raw.substring(2).split(":", limit = 3)
            val member = if (raw.startsWith("m:")) "${parts[1]}${parts[2]}" else "${parts[1]}: ${parts[2]}"
            Ref(parts[0], parts[0].replace('/', '.'), member)
        }
        .filterNot { paging.excludes(it.callerClass, it.member) }
        .sortedWith(compareBy({ it.callerClass }, { it.member }))

    val page = refs.drop(paging.offset).take(paging.limit)
    val end = paging.offset + page.size
    val results = page.groupBy { it.ownerInternalName }.map { (ownerInternalName, members) ->
        val (size, nMethods, nFields) = classStats(indexData, remappedClasses, ownerInternalName)
        CallerHits(members.first().callerClass, size, nMethods, nFields, members.map { it.member })
    }
    val (size, nMethods, nFields) = classStats(indexData, remappedClasses, declaringInternalName)
    return FindReferencesResult(
        targetClass = dottedTargetClass,
        targetMember = targetMember,
        declaringClass = declaringInternalName.replace('/', '.'),
        declaringClassSize = size,
        declaringClassNMethods = nMethods,
        declaringClassNFields = nFields,
        results = results,
        shown = page.size,
        total = refs.size,
        truncated = end < refs.size,
        nextOffset = end.takeIf { it < refs.size },
    )
}

private sealed interface MemberResolution {
    data class Found(val name: String, val desc: String, val isField: Boolean) : MemberResolution
    data class Ambiguous(val candidates: List<String>) : MemberResolution
    object NotFound : MemberResolution
}

/**
 * What the member argument names: a bare name, or one candidate exactly as an ambiguity lists it -
 * "name(desc)" for a method, "name: desc" for a field. A bare name plus 'kind' can't tell two
 * overloads apart, or the covariant bridges a compiler adds beside an override (every CraftBukkit
 * wrapper's getHandle()), so the candidate form is the only way through those.
 */
internal data class MemberQuery(val name: String, val methodDesc: String?, val fieldDesc: String?) {

    companion object {

        fun parse(member: String): MemberQuery = when {
            '(' in member -> MemberQuery(member.substringBefore('('), "(" + member.substringAfter('('), null)
            ": " in member -> MemberQuery(member.substringBefore(": "), null, member.substringAfter(": "))
            else -> MemberQuery(member, null, null)
        }

    }

}

private fun resolveMember(indexData: IndexData, internalName: String, memberName: String, kind: String?): MemberResolution {
    val memberData = indexData.members()[internalName] ?: return MemberResolution.NotFound
    val query = MemberQuery.parse(memberName)
    val wantsMethods = kind != "field" && query.fieldDesc == null
    val wantsFields = kind != "method" && query.methodDesc == null
    val methodCandidates = if (!wantsMethods) emptyList() else memberData.methods().filter {
        it.name() == query.name && (query.methodDesc == null || it.desc() == query.methodDesc)
    }
    val fieldCandidates = if (!wantsFields) emptyList() else memberData.fields().filter {
        it.name() == query.name && (query.fieldDesc == null || it.desc() == query.fieldDesc)
    }
    val total = methodCandidates.size + fieldCandidates.size

    return when {
        total == 0 -> MemberResolution.NotFound
        total == 1 && methodCandidates.size == 1 -> MemberResolution.Found(methodCandidates[0].name(), methodCandidates[0].desc(), isField = false)
        total == 1 && fieldCandidates.size == 1 -> MemberResolution.Found(fieldCandidates[0].name(), fieldCandidates[0].desc(), isField = true)
        else -> MemberResolution.Ambiguous(
            (methodCandidates.map { "${it.name()}${it.desc()}" } + fieldCandidates.map { "${it.name()}: ${it.desc()}" }).sorted(),
        )
    }
}
