package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.IndexData
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * Resolves the symbols on source lines no decompiler tokenized - the lines a fork's patches wrote, and
 * the fork's own classes - from the class's bytecode instead, without parsing Java.
 *
 * It works because the source is exactly what the bytecode was compiled from (Paper compiles its
 * patched tree), so the LineNumberTable says which instructions each line produced: every call, field
 * access, cast and method reference on a line arrives already resolved to its owner and descriptor,
 * and only has to be matched to the name it prints as. What bytecode doesn't show - declarations, and
 * names the compiler erased or folded (a type in a declaration, a constant) - is matched by rules
 * narrow enough to be right on code written the way Minecraft and Paper write it:
 *
 * - a method declaration is its name followed by `(` at or above the method's first line, after a
 *   type; code-less (abstract, native) methods are matched in the order the class file lists them,
 *   which is source order;
 * - a field declaration is `Type name =|;|,` outside method bodies, confirmed by the initializer's
 *   store on that line or by the field's constant value;
 * - locals and parameters come from the LocalVariableTable's scopes;
 * - a type name resolves through the nest, the imports, the package and java.lang; `Type.NAME` and a
 *   bare constant through the index's fields.
 *
 * Where one line calls two different overloads of one name, occurrences and instructions are paired
 * in order, which is evaluation order rather than text order when the calls nest - the one place a
 * token here can name the wrong overload. Vineflower's own tokens cover every line the patches left
 * alone, so this only ever runs on the fork's lines.
 */
object BytecodeTokens {

    fun derive(source: String, lines: Set<Int>, nest: Map<String, ByteArray>, index: IndexData): List<SourceToken> {
        if (lines.isEmpty() || nest.isEmpty()) return emptyList()
        val text = MaskedSource(source)
        val classes = nest.values.map { bytes -> ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) } }
        val facts = Facts(classes)
        val sink = Sink(text, lines)

        declarations(text, facts, sink)
        references(text, facts, sink)
        locals(text, facts, sink)
        names(text, facts, sink, index)
        return sink.tokens()
    }

    /**
     * Carried tokens the fork's index can't back: a member reference whose owner is in the index but
     * which neither it nor any ancestor declares (the fork changed the member's signature, not this
     * line), or a declaration of a member the class no longer has. Returns the tokens to keep and the
     * lines the dropped ones were on, which then need [derive].
     */
    fun verifyCarried(tokens: List<SourceToken>, index: IndexData, source: String): Pair<List<SourceToken>, Set<Int>> {
        val lineIndex = LineIndex(source)
        val dropped = HashSet<Int>()
        val kept = tokens.filter { token ->
            val member = token.member
            val ok = when {
                member == null || token.kind == TokenKind.CLASS -> true
                token.className !in index.classes() -> true
                token.declaration -> declares(index, token.className, token.kind, member)
                else -> declares(index, token.className, token.kind, member) ||
                    ancestorsOf(index, token.className).any { declares(index, it, token.kind, member) }
            }
            if (!ok) dropped.add(lineIndex.lineOf(token.start))
            ok
        }
        return kept to dropped
    }

    private fun declares(index: IndexData, owner: String, kind: TokenKind, member: TokenMember): Boolean {
        val data = index.members()[owner] ?: return false
        return if (kind == TokenKind.FIELD) data.fields().any { it.name() == member.name && it.desc() == member.descriptor }
        else data.methods().any { it.name() == member.name && it.desc() == member.descriptor }
    }

    // ---------------------------------------------------------------------------------------------
    // Declarations
    // ---------------------------------------------------------------------------------------------

    private fun declarations(text: MaskedSource, facts: Facts, sink: Sink) {
        // Classes first: their offsets bound where each class's members are looked for.
        for (match in CLASS_DECLARATION.findAll(text.masked)) {
            val name = match.groups[2]!!
            val cls = facts.classes.firstOrNull { simpleName(it.name) == name.value && !isAnonymous(it.name) } ?: continue
            facts.classOffset.putIfAbsent(cls.name, name.range.first)
            sink.add(name.range.first, name.value.length, TokenKind.CLASS, cls.name, null, declaration = true)
        }

        val ordered = facts.classes.sortedBy { facts.classOffset[it.name] ?: 0 }
        val usedCodeless = HashSet<Int>()
        for (cls in ordered) {
            val from = facts.classOffset[cls.name] ?: 0
            for (method in cls.methods) {
                if (method.access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0 || method.name == "<clinit>") continue
                val symbol = if (method.name == "<init>") simpleName(cls.name) else method.name
                val first = facts.firstLine[method]
                val at = if (first != null) {
                    declarationAbove(text, symbol, first, constructor = method.name == "<init>")
                } else {
                    text.occurrences(symbol).firstOrNull { it >= from && it !in usedCodeless && isMethodDeclaration(text, it, symbol) && text.lineOf(it) !in facts.codeLines }
                        ?.also { usedCodeless.add(it) }
                } ?: continue
                facts.declarationLine[method] = text.lineOf(at)
                sink.add(at, symbol.length, TokenKind.METHOD, cls.name, TokenMember(method.name, method.desc), declaration = true)
            }

            for (field in cls.fields) {
                if (field.access and Opcodes.ACC_SYNTHETIC != 0 || '$' in field.name) continue
                val at = text.occurrences(field.name).firstOrNull { offset ->
                    offset >= from && isFieldDeclaration(text, offset, field.name, cls.name, field.value != null, facts)
                } ?: continue
                sink.add(at, field.name.length, TokenKind.FIELD, cls.name, TokenMember(field.name, field.desc), declaration = true)
            }
        }
    }

    // Scans up from a method's first line: annotations and a multi-line signature can put its name
    // well above the first instruction, never below it.
    private fun declarationAbove(text: MaskedSource, symbol: String, firstLine: Int, constructor: Boolean): Int? {
        for (line in firstLine downTo maxOf(1, firstLine - DECLARATION_SEARCH_LINES)) {
            val hit = text.identifiersOn(line).firstOrNull { (offset, name) ->
                name == symbol && if (constructor) isConstructorDeclaration(text, offset, symbol) else isMethodDeclaration(text, offset, symbol)
            }
            if (hit != null) return hit.first
        }
        return null
    }

    private fun isMethodDeclaration(text: MaskedSource, offset: Int, symbol: String): Boolean {
        if (text.nextChar(offset + symbol.length) != '(') return false
        val before = text.prevChar(offset)
        if (before == '.' || before == null) return false
        if (!(before.isJavaIdentifierPart() || before == '>' || before == ']')) return false
        return text.prevWord(offset) !in NOT_A_TYPE
    }

    private fun isConstructorDeclaration(text: MaskedSource, offset: Int, symbol: String): Boolean {
        if (text.nextChar(offset + symbol.length) != '(') return false
        val before = text.prevChar(offset)
        return before != '.' && text.prevWord(offset) !in setOf("new", "return", "throw", "=")
    }

    private fun isFieldDeclaration(text: MaskedSource, offset: Int, name: String, owner: String, hasConstant: Boolean, facts: Facts): Boolean {
        val next = text.nextChar(offset + name.length)
        val before = text.prevChar(offset) ?: return false
        if (before == '.' || !(before.isJavaIdentifierPart() || before == '>' || before == ']')) return false
        if (text.prevWord(offset) in NOT_A_TYPE) return false
        val line = text.lineOf(offset)
        if (line in facts.methodBodyLines) return false
        if (facts.localNamesOn[line]?.contains(name) == true) return false
        return when (next) {
            '=' -> hasConstant || facts.stores[line]?.any { it.owner == owner && it.name == name } == true
            ';', ',' -> true
            else -> false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // References
    // ---------------------------------------------------------------------------------------------

    private fun references(text: MaskedSource, facts: Facts, sink: Sink) {
        for ((line, refs) in facts.refsByLine) {
            if (line !in sink.lines) continue
            val occurrences = text.identifiersOn(line)
            for ((_, group) in refs.groupBy { it.symbol to it.shape }) {
                val sample = group.first()
                val matching = occurrences.filter { (offset, name) ->
                    name == sample.symbol && !sink.claimed(offset) && fits(text, offset, sample, facts.localNamesOn[line].orEmpty())
                }.map { it.first }
                if (matching.isEmpty()) continue

                // One resolution for every occurrence when every instruction agrees; otherwise pair
                // them in order.
                val distinct = group.distinctBy { Triple(it.owner, it.name, it.desc) }
                val pairs = if (distinct.size == 1) matching.map { it to sample } else matching.zip(group)
                for ((offset, ref) in pairs) {
                    val member = if (ref.kind == TokenKind.CLASS) null else TokenMember(ref.name, ref.desc)
                    sink.add(offset, ref.symbol.length, ref.kind, ref.owner, member, declaration = false)
                }
            }
        }
    }

    private fun fits(text: MaskedSource, offset: Int, ref: Ref, localsHere: Set<String>): Boolean {
        val end = offset + ref.symbol.length
        return when (ref.shape) {
            Shape.CALL -> text.nextChar(end) == '(' || text.precededBy(offset, "::")
            Shape.METHOD_REFERENCE -> text.precededBy(offset, "::")
            Shape.NEW -> text.nextChar(end) == '(' || text.nextChar(end) == '<'
            Shape.FIELD -> text.nextChar(end) != '(' && (ref.symbol !in localsHere || text.prevChar(offset) == '.')
            Shape.TYPE -> true
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Locals and parameters
    // ---------------------------------------------------------------------------------------------

    private fun locals(text: MaskedSource, facts: Facts, sink: Sink) {
        for (local in facts.locals) {
            val lines = local.lines + (facts.declarationLine[local.method]?.takeIf { local.parameter }?.let { setOf(it) } ?: emptySet())
            for (line in lines) {
                if (line !in sink.lines) continue
                for ((offset, name) in text.identifiersOn(line)) {
                    if (name != local.name || sink.claimed(offset)) continue
                    if (text.prevChar(offset) == '.' || text.nextChar(offset + name.length) == '(') continue
                    sink.add(offset, name.length, if (local.parameter) TokenKind.PARAMETER else TokenKind.LOCAL, local.owner, null, declaration = false)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Type names and constants
    // ---------------------------------------------------------------------------------------------

    private fun names(text: MaskedSource, facts: Facts, sink: Sink, index: IndexData) {
        val imports = Imports.of(text.masked)
        val nestBySimpleName = facts.classes.filter { !isAnonymous(it.name) }.associateBy { simpleName(it.name) }

        fun resolveType(name: String): String? =
            nestBySimpleName[name]?.name
                ?: imports.single[name]
                ?: "${imports.pkg}$name".takeIf { it in index.classes() }
                ?: imports.wildcards.map { "$it$name" }.firstOrNull { it in index.classes() || isJdkClass(it) }
                ?: "java/lang/$name".takeIf { isJdkClass(it) }

        fun fieldOf(owner: String, name: String): TokenMember? =
            (listOf(owner) + ancestorsOf(index, owner)).firstNotNullOfOrNull { cls ->
                index.members()[cls]?.fields()?.firstOrNull { it.name() == name }?.let { TokenMember(it.name(), it.desc()) }
            }

        for (line in sink.lines.sorted()) {
            // What each identifier on the line resolved to, so a qualifier can resolve what follows it.
            val resolvedAt = HashMap<Int, String>()
            for ((offset, name) in text.identifiersOn(line)) {
                sink.classAt(offset)?.let { resolvedAt[offset] = it }
                if (sink.claimed(offset) || name in KEYWORDS) continue

                val qualifier = if (text.prevChar(offset) == '.') text.qualifierBefore(offset) else null
                val qualifierType = qualifier?.let { resolvedAt[it.first] }
                if (qualifierType != null) {
                    val inner = "$qualifierType\$$name"
                    if (inner in index.classes()) {
                        sink.add(offset, name.length, TokenKind.CLASS, inner, null, declaration = false)
                        resolvedAt[offset] = inner
                    } else {
                        fieldOf(qualifierType, name)?.let { sink.add(offset, name.length, TokenKind.FIELD, qualifierType, it, declaration = false) }
                    }
                    continue
                }
                if (!name.first().isUpperCase()) continue

                if (qualifier != null) {
                    // A fully qualified name: every part before this one a lower-case package name.
                    val path = text.packagePathBefore(offset) ?: continue
                    val type = "$path$name".takeIf { it in index.classes() || isJdkClass(it) } ?: continue
                    sink.add(offset, name.length, TokenKind.CLASS, type, null, declaration = false)
                    resolvedAt[offset] = type
                    continue
                }

                val type = resolveType(name)
                if (type != null) {
                    sink.add(offset, name.length, TokenKind.CLASS, type, null, declaration = false)
                    resolvedAt[offset] = type
                    continue
                }
                // A bare constant of the nest, or one it inherits; or a static import.
                val owner = facts.classes.firstOrNull { cls -> fieldOf(cls.name, name) != null }?.name
                    ?: imports.staticMembers[name]?.takeIf { fieldOf(it, name) != null }
                if (owner != null) sink.add(offset, name.length, TokenKind.FIELD, owner, fieldOf(owner, name), declaration = false)
            }
        }
    }

    private fun isJdkClass(internalName: String): Boolean =
        ClassLoader.getSystemResource("$internalName.class") != null

    // ---------------------------------------------------------------------------------------------
    // What the bytecode says
    // ---------------------------------------------------------------------------------------------

    private enum class Shape { CALL, METHOD_REFERENCE, NEW, FIELD, TYPE }

    private class Ref(val kind: TokenKind, val shape: Shape, val owner: String, val name: String, val desc: String, val symbol: String)

    private class Local(val owner: String, val method: MethodNode, val name: String, val parameter: Boolean, val lines: Set<Int>)

    private class Facts(val classes: List<ClassNode>) {

        val refsByLine = HashMap<Int, MutableList<Ref>>()
        val stores = HashMap<Int, MutableList<Ref>>()
        val firstLine = HashMap<MethodNode, Int>()
        val declarationLine = HashMap<MethodNode, Int>()
        val classOffset = HashMap<String, Int>()
        val locals = mutableListOf<Local>()
        val localNamesOn = HashMap<Int, MutableSet<String>>()

        /** Every line some method's code is on. */
        val codeLines = HashSet<Int>()

        /** Lines inside a method body proper - code of anything but the constructors and static init. */
        val methodBodyLines = HashSet<Int>()

        init {
            for (cls in classes) for (method in cls.methods) read(cls, method)
        }

        private fun read(cls: ClassNode, method: MethodNode) {
            val instructions = method.instructions ?: return
            if (instructions.size() == 0) return
            val lineAt = IntArray(instructions.size())
            val labelIndex = HashMap<LabelNode, Int>()
            val pendingNew = ArrayDeque<String>()
            var line = 0
            var lo = Int.MAX_VALUE
            var hi = 0
            var first = 0

            for ((i, insn) in instructions.withIndex()) {
                if (insn is LabelNode) labelIndex[insn] = i
                if (insn is LineNumberNode) {
                    line = insn.line
                    if (first == 0) first = line
                }
                lineAt[i] = line
                if (line == 0) continue
                if (insn.opcode >= 0) {
                    lo = minOf(lo, line)
                    hi = maxOf(hi, line)
                }
                val ref = when (insn) {
                    is MethodInsnNode -> methodRef(cls, method, insn, pendingNew)
                    is FieldInsnNode -> if ('$' in insn.name) null else Ref(TokenKind.FIELD, Shape.FIELD, insn.owner, insn.name, insn.desc, insn.name)
                    is TypeInsnNode -> typeRef(insn, pendingNew)
                    is InvokeDynamicInsnNode -> methodReference(insn)
                    is LdcInsnNode -> (insn.cst as? Type)?.takeIf { it.sort == Type.OBJECT }?.let { classRef(it.internalName) }
                    is MultiANewArrayInsnNode -> classRef(Type.getType(insn.desc).elementType.internalName)
                    else -> null
                } ?: continue
                refsByLine.getOrPut(line) { mutableListOf() }.add(ref)
                if (insn.opcode == Opcodes.PUTFIELD || insn.opcode == Opcodes.PUTSTATIC) stores.getOrPut(line) { mutableListOf() }.add(ref)
            }
            if (lo == Int.MAX_VALUE) return

            // The first line in execution order, not the lowest: javac compiles field initializers into
            // every constructor after its super() call, so a constructor's lowest line can be a field
            // declared far above it.
            firstLine[method] = first
            val lines = lo..hi
            codeLines.addAll(lines)
            if (method.name != "<init>" && method.name != "<clinit>" && !method.name.startsWith("lambda$")) methodBodyLines.addAll(lines)

            val parameterSlots = (if (method.access and Opcodes.ACC_STATIC != 0) 0 else 1) +
                Type.getArgumentTypes(method.desc).sumOf { it.size }
            for (variable in method.localVariables.orEmpty()) {
                if (variable.name == "this") continue
                val start = labelIndex[variable.start] ?: continue
                val end = labelIndex[variable.end] ?: instructions.size()
                val covered = HashSet<Int>()
                for (i in start until end) if (lineAt[i] != 0) covered.add(lineAt[i])
                // A local's scope opens just after the store that initializes it, which is usually on
                // its declaration line.
                if (start > 0 && lineAt[start - 1] != 0) covered.add(lineAt[start - 1])
                locals.add(Local(cls.name, method, variable.name, variable.index < parameterSlots, covered))
                for (l in covered) localNamesOn.getOrPut(l) { HashSet() }.add(variable.name)
            }
        }

        private fun methodRef(cls: ClassNode, method: MethodNode, insn: MethodInsnNode, pendingNew: ArrayDeque<String>): Ref? {
            if (insn.name != "<init>") return Ref(TokenKind.METHOD, Shape.CALL, insn.owner, insn.name, insn.desc, insn.name)
            // An <init> call either finishes a `new` or is a constructor's super(...)/this(...).
            if (pendingNew.lastOrNull() == insn.owner) {
                pendingNew.removeLast()
                if (isAnonymous(insn.owner)) return null
                return Ref(TokenKind.METHOD, Shape.NEW, insn.owner, "<init>", insn.desc, simpleName(insn.owner))
            }
            if (method.name != "<init>") return null
            val symbol = if (insn.owner == cls.name) "this" else "super"
            return Ref(TokenKind.METHOD, Shape.CALL, insn.owner, "<init>", insn.desc, symbol)
        }

        private fun typeRef(insn: TypeInsnNode, pendingNew: ArrayDeque<String>): Ref? {
            if (insn.opcode == Opcodes.NEW) {
                pendingNew.addLast(insn.desc)
                return null
            }
            val type = if (insn.desc.startsWith("[")) Type.getType(insn.desc).elementType else Type.getObjectType(insn.desc)
            if (type.sort != Type.OBJECT) return null
            return classRef(type.internalName)
        }

        private fun classRef(internalName: String): Ref? =
            if (isAnonymous(internalName)) null else Ref(TokenKind.CLASS, Shape.TYPE, internalName, "", "", simpleName(internalName))

        // `Foo::bar` is an invokedynamic whose implementation handle is the method itself; a lambda's
        // is a synthetic lambda$ method, which prints as no name at all.
        private fun methodReference(insn: InvokeDynamicInsnNode): Ref? {
            if (insn.bsm.owner != "java/lang/invoke/LambdaMetafactory") return null
            val handle = insn.bsmArgs.getOrNull(1) as? Handle ?: return null
            if (handle.name.startsWith("lambda$") || handle.name == "<init>") return null
            return Ref(TokenKind.METHOD, Shape.METHOD_REFERENCE, handle.owner, handle.name, handle.desc, handle.name)
        }

    }

    // ---------------------------------------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------------------------------------

    /**
     * The source with every comment, string, text block and char literal blanked to spaces (newlines
     * kept), so a name inside one is never taken for a symbol and offsets still line up with the real
     * text.
     */
    private class MaskedSource(source: String) {

        val masked: String = mask(source)
        private val lineIndex = LineIndex(source)
        private val identifiersByLine = HashMap<Int, List<Pair<Int, String>>>()

        fun lineOf(offset: Int): Int = lineIndex.lineOf(offset)

        fun identifiersOn(line: Int): List<Pair<Int, String>> = identifiersByLine.getOrPut(line) {
            val start = lineIndex.startOf(line) ?: return@getOrPut emptyList()
            val end = lineIndex.endOf(line)
            IDENTIFIER.findAll(masked.substring(start, end)).map { start + it.range.first to it.value }.toList()
        }

        fun occurrences(name: String): List<Int> =
            Regex("""(?<![\w$])${Regex.escape(name)}(?![\w$])""").findAll(masked).map { it.range.first }.toList()

        fun nextChar(offset: Int): Char? {
            var i = offset
            while (i < masked.length && masked[i].isWhitespace()) i++
            return masked.getOrNull(i)
        }

        fun prevChar(offset: Int): Char? {
            var i = offset - 1
            while (i >= 0 && masked[i].isWhitespace()) i--
            return masked.getOrNull(i)
        }

        fun precededBy(offset: Int, token: String): Boolean {
            var i = offset - 1
            while (i >= 0 && masked[i].isWhitespace()) i--
            return i + 1 >= token.length && masked.regionMatches(i + 1 - token.length, token, 0, token.length)
        }

        /** The word before [offset] - an identifier, or the single symbol character there. */
        fun prevWord(offset: Int): String? {
            var i = offset - 1
            while (i >= 0 && masked[i].isWhitespace()) i--
            if (i < 0) return null
            if (!masked[i].isJavaIdentifierPart()) return masked[i].toString()
            val end = i + 1
            while (i >= 0 && masked[i].isJavaIdentifierPart()) i--
            return masked.substring(i + 1, end)
        }

        /** The identifier before the `.` preceding [offset], as (offset, name). */
        fun qualifierBefore(offset: Int): Pair<Int, String>? {
            var i = offset - 1
            while (i >= 0 && masked[i].isWhitespace()) i--
            if (i < 0 || masked[i] != '.') return null
            i--
            while (i >= 0 && masked[i].isWhitespace()) i--
            val end = i + 1
            while (i >= 0 && masked[i].isJavaIdentifierPart()) i--
            if (i + 1 == end) return null
            return i + 1 to masked.substring(i + 1, end)
        }

        /** `a.b.c.` before [offset] as `a/b/c/` when every part is a lower-case package name. */
        fun packagePathBefore(offset: Int): String? {
            val parts = ArrayDeque<String>()
            var at = offset
            while (true) {
                val (qualifierOffset, name) = qualifierBefore(at) ?: break
                if (!name.first().isLowerCase()) return null
                parts.addFirst(name)
                at = qualifierOffset
            }
            return if (parts.isEmpty()) null else parts.joinToString("/", postfix = "/")
        }

        private fun mask(source: String): String {
            val out = StringBuilder(source)
            var i = 0
            fun blank(from: Int, to: Int) {
                for (j in from until minOf(to, out.length)) if (out[j] != '\n' && out[j] != '\r') out.setCharAt(j, ' ')
            }
            while (i < source.length) {
                when {
                    source.startsWith("//", i) -> {
                        val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                        blank(i, end)
                        i = end
                    }

                    source.startsWith("/*", i) -> {
                        val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                        blank(i, end)
                        i = end
                    }

                    source.startsWith("\"\"\"", i) -> {
                        val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                        blank(i, end)
                        i = end
                    }

                    source[i] == '"' || source[i] == '\'' -> {
                        val quote = source[i]
                        var j = i + 1
                        while (j < source.length && source[j] != quote && source[j] != '\n') j += if (source[j] == '\\') 2 else 1
                        blank(i, j + 1)
                        i = j + 1
                    }

                    else -> i++
                }
            }
            return out.toString()
        }

    }

    private class Imports(val pkg: String, val single: Map<String, String>, val wildcards: List<String>, val staticMembers: Map<String, String>) {

        companion object {

            private val PACKAGE = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
            private val IMPORT = Regex("""(?m)^\s*import\s+(static\s+)?([\w.]+?)(\.\*)?\s*;""")

            fun of(masked: String): Imports {
                val pkg = PACKAGE.find(masked)?.groupValues?.get(1)?.replace('.', '/')?.plus('/') ?: ""
                val single = HashMap<String, String>()
                val wildcards = mutableListOf<String>()
                val staticMembers = HashMap<String, String>()
                for (match in IMPORT.findAll(masked)) {
                    val isStatic = match.groupValues[1].isNotBlank()
                    val path = match.groupValues[2]
                    val wildcard = match.groupValues[3].isNotEmpty()
                    when {
                        isStatic && !wildcard -> staticMembers[path.substringAfterLast('.')] = internalOf(path.substringBeforeLast('.'))
                        isStatic -> Unit
                        wildcard -> wildcards.add(internalOf(path) + "/")
                        else -> single[path.substringAfterLast('.')] = internalOf(path)
                    }
                }
                return Imports(pkg, single, wildcards, staticMembers)
            }

            // a.b.Outer.Inner -> a/b/Outer$Inner: package parts are lower case, classes aren't.
            private fun internalOf(dotted: String): String {
                val parts = dotted.split('.')
                val firstClass = parts.indexOfFirst { it.first().isUpperCase() }.let { if (it < 0) parts.lastIndex else it }
                return parts.subList(0, firstClass).joinToString("/", postfix = if (firstClass > 0) "/" else "") +
                    parts.subList(firstClass, parts.size).joinToString("$")
            }

        }

    }

    /** Collects tokens for [lines] only, one per text span; the first claim on a span wins. */
    private class Sink(private val text: MaskedSource, val lines: Set<Int>) {

        private val byStart = HashMap<Int, SourceToken>()

        fun claimed(offset: Int): Boolean = offset in byStart

        fun classAt(offset: Int): String? = byStart[offset]?.takeIf { it.kind == TokenKind.CLASS }?.className

        fun add(start: Int, length: Int, kind: TokenKind, owner: String, member: TokenMember?, declaration: Boolean) {
            if (text.lineOf(start) !in lines || start in byStart) return
            byStart[start] = SourceToken(start, length, kind, owner, member, declaration)
        }

        fun tokens(): List<SourceToken> = byStart.values.sortedBy { it.start }

    }

    private fun simpleName(internalName: String): String = internalName.substringAfterLast('/').substringAfterLast('$')

    private fun isAnonymous(internalName: String): Boolean = simpleName(internalName).all { it.isDigit() }

    private const val DECLARATION_SEARCH_LINES = 40

    private val IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
    private val CLASS_DECLARATION = Regex("""(?<![\w$])(class|interface|enum|record|@interface)\s+([A-Za-z_$][\w$]*)""")

    // Words that can sit right before `name(` without it being a declaration.
    private val NOT_A_TYPE = setOf("new", "return", "throw", "else", "case", "assert", "yield", "=", ".", "(", ",", "!", "&&", "||", "?", ":")

    private val KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do",
        "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
        "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
        "true", "false", "null", "var", "record", "yield", "sealed", "permits",
    )

}
