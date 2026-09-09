package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import kotlinx.serialization.Serializable

@Serializable
data class ClassOutlineResult(
    val className: String,
    val superName: String?,
    val interfaces: List<String>,
    val methods: List<String>,
    val fields: List<String>,
)

class ClassNotFoundInIndexException(className: String) :
    NoSuchElementException("class not found in index: $className")

fun getClassOutlineToolLogic(indexData: IndexData, dottedClassName: String): ClassOutlineResult {
    val internalName = dottedClassName.replace('.', '/')
    val classData = indexData.classes()[internalName] ?: throw ClassNotFoundInIndexException(dottedClassName)
    val memberData = indexData.members()[internalName]

    return ClassOutlineResult(
        className = dottedClassName,
        superName = classData.superName()?.replace('/', '.'),
        interfaces = classData.interfaces().map { it.replace('/', '.') },
        methods = memberData?.methods()?.map { "${it.name()}${it.desc()}" }?.sorted() ?: emptyList(),
        fields = memberData?.fields()?.map { "${it.name()}: ${it.desc()}" }?.sorted() ?: emptyList(),
    )
}
