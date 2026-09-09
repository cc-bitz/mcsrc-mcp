package io.github.ccbitz.mcsrcmcp.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TokenKind {
    @SerialName("class")
    CLASS,

    @SerialName("field")
    FIELD,

    @SerialName("method")
    METHOD,

    @SerialName("parameter")
    PARAMETER,

    @SerialName("local")
    LOCAL,
}

/**
 * Name and descriptor together identify one member exactly, which is what makes a token
 * resolvable without guessing at overloads. Class, parameter and local tokens carry neither,
 * so the pair is nullable as a unit rather than as two independently-null fields.
 */
@Serializable
data class TokenMember(val name: String, val descriptor: String)

/**
 * One resolved symbol in decompiled source: where it sits in the text, and what it refers to.
 * [start]/[length] are character offsets into the source Vineflower emitted for the same class,
 * so the two only line up when they came from the same decompile.
 */
@Serializable
data class SourceToken(
    val start: Int,
    val length: Int,
    val kind: TokenKind,
    val className: String,
    val member: TokenMember?,
    val declaration: Boolean,
)

@Serializable
data class DecompiledClass(val source: String, val tokens: List<SourceToken>)
