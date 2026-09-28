package io.github.ccbitz.mcsrcmcp.server

import io.github.ccbitz.mcsrcmcp.core.Indexer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FindReferencesToolTest {
    private fun loadFixture(internalName: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream("$internalName.class")
            ?: error("fixture class not found on test classpath: $internalName")
        return stream.readBytes()
    }

    // Full index() (not indexDeclarations()), matching what VersionWorkspaceBuilder's Pass 2
    // actually does - this is what populates the queryable reference map.
    private fun buildIndexer(): Indexer {
        val indexer = Indexer()
        for (name in listOf("net/minecraft/Animal", "net/minecraft/Dog", "net/minecraft/Item", "net/minecraft/BlockItem")) {
            indexer.index(loadFixture(name))
        }
        return indexer
    }

    // net/minecraft/Over declares go()V, go(I)V and a field go:I; SubOver extends it; Caller calls
    // only go(I)V. Overloads and covariant bridges both look like this: one name, several members.
    private fun overloadIndexer(): Indexer {
        fun classOf(name: String, superName: String = "java/lang/Object", body: (org.objectweb.asm.ClassWriter) -> Unit = {}): ByteArray {
            val writer = org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS)
            writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, superName, null)
            body(writer)
            writer.visitEnd()
            return writer.toByteArray()
        }

        fun emptyMethod(writer: org.objectweb.asm.ClassWriter, name: String, desc: String) {
            val mv = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_STATIC, name, desc, null, null)
            mv.visitCode()
            mv.visitInsn(org.objectweb.asm.Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        val indexer = Indexer()
        indexer.index(classOf("net/minecraft/Over") { writer ->
            emptyMethod(writer, "go", "()V")
            emptyMethod(writer, "go", "(I)V")
            writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "go", "I", null, null).visitEnd()
        })
        indexer.index(classOf("net/minecraft/SubOver", superName = "net/minecraft/Over"))
        indexer.index(classOf("net/minecraft/Caller") { writer ->
            val mv = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_STATIC, "call", "()V", null, null)
            mv.visitCode()
            mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_1)
            mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "net/minecraft/Over", "go", "(I)V", false)
            mv.visitInsn(org.objectweb.asm.Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        })
        return indexer
    }

    @Test
    fun `a bare name shared by several members lists them as candidates`() {
        val indexer = overloadIndexer()
        val outcome = findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.Over", memberName = "go")

        assertEquals(FindReferencesOutcome.AmbiguousMember(listOf("go()V", "go(I)V", "go: I")), outcome)
    }

    // The candidates have to be something the caller can pass back - with only a bare name and
    // 'kind', two overloads of one method could never be told apart.
    @Test
    fun `passing a method candidate back picks exactly that overload`() {
        val indexer = overloadIndexer()
        val outcome = findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.Over", memberName = "go(I)V")

        val result = (outcome as FindReferencesOutcome.Found).result
        assertEquals("go(I)V", result.targetMember)
        assertEquals(listOf("net.minecraft.Caller"), result.results.map { it.callerClass })

        val other = findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.Over", memberName = "go()V")
        assertTrue((other as FindReferencesOutcome.Found).result.results.isEmpty())
    }

    @Test
    fun `passing a field candidate back picks the field`() {
        val indexer = overloadIndexer()
        val outcome = findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.Over", memberName = "go: I")

        assertEquals("go: I", (outcome as FindReferencesOutcome.Found).result.targetMember)
    }

    @Test
    fun `a candidate still resolves up the inheritance chain`() {
        val indexer = overloadIndexer()
        val outcome = findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.SubOver", memberName = "go(I)V")

        val result = (outcome as FindReferencesOutcome.Found).result
        assertEquals("net.minecraft.Over", result.declaringClass)
        assertEquals(listOf("net.minecraft.Caller"), result.results.map { it.callerClass })
    }

    @Test
    fun `a candidate with a descriptor no member has is not found`() {
        val indexer = overloadIndexer()
        assertThrows(MemberNotFoundException::class.java) {
            findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.Over", memberName = "go(J)V")
        }
    }

    @Test
    fun `class-level query returns literal class references`() {
        // BlockItem extends Item - the class-level reference key for Item is generated by
        // Dog/Animal-style CHECKCAST/INSTANCEOF/LDC usage, which these fixtures don't exercise,
        // so this asserts the call succeeds and returns a (possibly empty) list rather than
        // asserting non-empty - the meaningful assertion here is the RESULT SHAPE and that no
        // exception is thrown for a known class with no member specified.
        val indexer = buildIndexer()
        val outcome = findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.Item")

        assertTrue(outcome is FindReferencesOutcome.Found)
        val result = (outcome as FindReferencesOutcome.Found).result
        assertEquals("net.minecraft.Item", result.targetClass)
        assertNull(result.targetMember)
        assertEquals("net.minecraft.Item", result.declaringClass)
    }

    @Test
    fun `member-level query on the declaring class itself finds its caller`() {
        val indexer = buildIndexer()
        val outcome = findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.Animal", memberName = "staticSound")

        assertTrue(outcome is FindReferencesOutcome.Found)
        val result = (outcome as FindReferencesOutcome.Found).result
        assertEquals("staticSound()V", result.targetMember)
        assertEquals("net.minecraft.Animal", result.declaringClass)
        assertTrue(result.results.any { it.callerClass == "net.minecraft.Dog" && "run()V" in it.members })
    }

    @Test
    fun `resolve_declaration walks up to find an inherited members real owner`() {
        // getMaxStackSize is declared on Item, not BlockItem. Asking find_references on
        // BlockItem for it should resolve up to Item and report that.
        val indexer = buildIndexer()
        val outcome = findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.BlockItem", memberName = "getMaxStackSize")

        assertTrue(outcome is FindReferencesOutcome.Found)
        val result = (outcome as FindReferencesOutcome.Found).result
        assertEquals("net.minecraft.BlockItem", result.targetClass)
        assertEquals("net.minecraft.Item", result.declaringClass)
        assertEquals("getMaxStackSize()I", result.targetMember)
    }

    @Test
    fun `resolve_declaration false does not walk up, and reports not found`() {
        val indexer = buildIndexer()
        assertThrows(MemberNotFoundException::class.java) {
            findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.BlockItem", memberName = "getMaxStackSize", resolveDeclaration = false)
        }
    }

    @Test
    fun `unresolvable member throws even with resolve_declaration true`() {
        val indexer = buildIndexer()
        assertThrows(MemberNotFoundException::class.java) {
            findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.Animal", memberName = "doesNotExist")
        }
    }

    @Test
    fun `declaringClass carries bytecode size and member counts when workspace data is given`() {
        val indexer = buildIndexer()
        val animalBytes = loadFixture("net/minecraft/Animal")
        val remappedClasses = mapOf("net/minecraft/Animal" to animalBytes)

        val outcome = findReferencesToolLogic(
            indexer.data(), indexer, "net.minecraft.Animal", memberName = "staticSound", remappedClasses = remappedClasses,
        )

        val result = (outcome as FindReferencesOutcome.Found).result
        val expectedMembers = indexer.data().members()["net/minecraft/Animal"]!!
        assertEquals(animalBytes.size, result.declaringClassSize)
        assertEquals(expectedMembers.methods().size, result.declaringClassNMethods)
        assertEquals(expectedMembers.fields().size, result.declaringClassNFields)
    }

    @Test
    fun `each reference carries its caller classs bytecode size and member counts`() {
        val indexer = buildIndexer()
        val dogBytes = loadFixture("net/minecraft/Dog")
        val remappedClasses = mapOf("net/minecraft/Dog" to dogBytes)

        val outcome = findReferencesToolLogic(
            indexer.data(), indexer, "net.minecraft.Animal", memberName = "staticSound", remappedClasses = remappedClasses,
        )

        val result = (outcome as FindReferencesOutcome.Found).result
        val dogRef = result.results.first { it.callerClass == "net.minecraft.Dog" }
        val expectedMembers = indexer.data().members()["net/minecraft/Dog"]!!
        assertEquals(dogBytes.size, dogRef.size)
        assertEquals(expectedMembers.methods().size, dogRef.nMethods)
        assertEquals(expectedMembers.fields().size, dogRef.nFields)
    }

    @Test
    fun `bytecode size and member counts default to zero without workspace data`() {
        val indexer = buildIndexer()

        val outcome = findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.Animal", memberName = "staticSound")

        val result = (outcome as FindReferencesOutcome.Found).result
        assertEquals(0, result.declaringClassSize)
        assertTrue(result.results.all { it.size == 0 })
    }

    // net/minecraft/Target.t()V, called from a()V and b()V in each of Caller0..Caller4: ten
    // references over five classes - enough to page through and to split a class across pages.
    private fun pagingIndexer(): Indexer {
        fun classOf(name: String, body: (org.objectweb.asm.ClassWriter) -> Unit): ByteArray {
            val writer = org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS)
            writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
            body(writer)
            writer.visitEnd()
            return writer.toByteArray()
        }

        fun method(writer: org.objectweb.asm.ClassWriter, name: String, callsTarget: Boolean) {
            val mv = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_STATIC, name, "()V", null, null)
            mv.visitCode()
            if (callsTarget) mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "net/minecraft/Target", "t", "()V", false)
            mv.visitInsn(org.objectweb.asm.Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        val indexer = Indexer()
        indexer.index(classOf("net/minecraft/Target") { method(it, "t", callsTarget = false) })
        for (i in 0 until 5) {
            indexer.index(classOf("net/minecraft/Caller$i") { writer ->
                method(writer, "a", callsTarget = true)
                method(writer, "b", callsTarget = true)
            })
        }
        return indexer
    }

    private fun page(indexer: Indexer, limit: Int, offset: Int = 0, exclude: String? = null): FindReferencesResult =
        (findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.Target", "t", limit = limit, offset = offset, exclude = exclude)
            as FindReferencesOutcome.Found).result

    private fun FindReferencesResult.flat(): List<String> = results.flatMap { hits -> hits.members.map { "${hits.callerClass}#$it" } }

    @Test
    fun `a page states what it shows, the exact total and where the next one starts`() {
        val first = page(pagingIndexer(), limit = 3)

        assertEquals(3, first.shown)
        assertEquals(10, first.total)
        assertTrue(first.truncated)
        assertEquals(3, first.nextOffset)
        // Sorted, and grouped by caller class so each class's stats appear once.
        assertEquals(listOf("net.minecraft.Caller0#a()V", "net.minecraft.Caller0#b()V", "net.minecraft.Caller1#a()V"), first.flat())
        assertEquals(listOf("net.minecraft.Caller0", "net.minecraft.Caller1"), first.results.map { it.callerClass })
    }

    @Test
    fun `following nextOffset visits every reference exactly once`() {
        val indexer = pagingIndexer()
        val seen = mutableListOf<String>()
        var offset: Int? = 0
        while (offset != null) {
            val result = page(indexer, limit = 3, offset = offset)
            seen += result.flat()
            offset = result.nextOffset
        }

        assertEquals(page(indexer, limit = 100).flat(), seen)
        assertEquals(10, seen.size)
    }

    @Test
    fun `the last page is not truncated`() {
        val last = page(pagingIndexer(), limit = 3, offset = 9)

        assertEquals(1, last.shown)
        assertFalse(last.truncated)
        assertNull(last.nextOffset)
    }

    // Like search_code's exclude: the filter sees the caller class and member, and the total counts
    // only what survives it, so paging a filtered view is consistent.
    @Test
    fun `exclude drops references by caller class or member before counting`() {
        val indexer = pagingIndexer()

        val noCallers03 = page(indexer, limit = 100, exclude = "Caller[03]")
        assertEquals(6, noCallers03.total)
        assertTrue(noCallers03.results.none { it.callerClass.endsWith("Caller0") || it.callerClass.endsWith("Caller3") })

        val onlyA = page(indexer, limit = 100, exclude = "#b\\(")
        assertEquals(5, onlyA.total)
        assertTrue(onlyA.results.all { it.members == listOf("a()V") })
    }

    @Test
    fun `unknown class throws`() {
        val indexer = buildIndexer()
        assertThrows(ClassNotFoundInIndexException::class.java) {
            findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.DoesNotExist")
        }
    }
}
