package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import io.github.ccbitz.mcsrcmcp.core.Indexer
import kotlinx.serialization.Serializable

@Serializable
data class ReferenceEntry(
    val callerClass: String,
    val callerMember: String,
    // The caller's own bytecode size/member counts - context for deciding whether to read the
    // whole caller class before diving in. Zero when remappedClasses wasn't supplied.
    val size: Int,
    val nMethods: Int,
    val nFields: Int,
)

@Serializable
data class FindReferencesResult(
    val targetClass: String,
    val targetMember: String?,
    val declaringClass: String,
    val declaringClassSize: Int,
    val declaringClassNMethods: Int,
    val declaringClassNFields: Int,
    val references: List<ReferenceEntry>,
)

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
): FindReferencesOutcome {
    val internalName = dottedClassName.replace('.', '/')
    if (internalName !in indexData.classes()) {
        throw ClassNotFoundInIndexException(dottedClassName)
    }

    if (memberName == null) {
        val refs = referenceIndexer.references(internalName)
        val (size, nMethods, nFields) = classStats(indexData, remappedClasses, internalName)
        return FindReferencesOutcome.Found(
            FindReferencesResult(
                targetClass = dottedClassName,
                targetMember = null,
                declaringClass = dottedClassName,
                declaringClassSize = size,
                declaringClassNMethods = nMethods,
                declaringClassNFields = nFields,
                references = refs.map { parseReferenceEntry(it, indexData, remappedClasses) },
            ),
        )
    }

    when (val resolved = resolveMember(indexData, internalName, memberName, kind)) {
        is MemberResolution.Ambiguous -> return FindReferencesOutcome.AmbiguousMember(resolved.candidates)
        is MemberResolution.Found -> return foundReferences(indexData, referenceIndexer, dottedClassName, internalName, resolved, remappedClasses)
        MemberResolution.NotFound -> {
            if (!resolveDeclaration) {
                throw MemberNotFoundException(dottedClassName, memberName)
            }

            for (ancestor in ancestorsOf(indexData, internalName)) {
                when (val ancestorResolved = resolveMember(indexData, ancestor, memberName, kind)) {
                    is MemberResolution.Ambiguous -> return FindReferencesOutcome.AmbiguousMember(ancestorResolved.candidates)
                    is MemberResolution.Found -> return foundReferences(indexData, referenceIndexer, dottedClassName, ancestor, ancestorResolved, remappedClasses)
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
): FindReferencesOutcome.Found {
    val key = "$declaringInternalName:${resolved.name}:${resolved.desc}"
    val refs = referenceIndexer.references(key)
    val (size, nMethods, nFields) = classStats(indexData, remappedClasses, declaringInternalName)
    return FindReferencesOutcome.Found(
        FindReferencesResult(
            targetClass = dottedTargetClass,
            // The same shape the candidates are listed in, so it can be passed straight back.
            targetMember = if (resolved.isField) "${resolved.name}: ${resolved.desc}" else "${resolved.name}${resolved.desc}",
            declaringClass = declaringInternalName.replace('/', '.'),
            declaringClassSize = size,
            declaringClassNMethods = nMethods,
            declaringClassNFields = nFields,
            references = refs.map { parseReferenceEntry(it, indexData, remappedClasses) },
        ),
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
private data class MemberQuery(val name: String, val methodDesc: String?, val fieldDesc: String?) {

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

// Reference values are "m:owner:name:desc" (method-body/signature-driven) or "f:owner:name:desc"
// (a field declaration whose own type is the referenced class) - never assume method-only.
private fun parseReferenceEntry(raw: String, indexData: IndexData, remappedClasses: Map<String, ByteArray>): ReferenceEntry {
    val isMethod = raw.startsWith("m:")
    val parts = raw.substring(2).split(":", limit = 3)
    val ownerInternalName = parts[0]
    val member = if (isMethod) "${parts[1]}${parts[2]}" else "${parts[1]}: ${parts[2]}"
    val (size, nMethods, nFields) = classStats(indexData, remappedClasses, ownerInternalName)
    return ReferenceEntry(ownerInternalName.replace('/', '.'), member, size, nMethods, nFields)
}
