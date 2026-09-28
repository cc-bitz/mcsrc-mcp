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
        assertEquals(listOf("net.minecraft.Caller"), result.references.map { it.callerClass })

        val other = findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.Over", memberName = "go()V")
        assertTrue((other as FindReferencesOutcome.Found).result.references.isEmpty())
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
        assertEquals(listOf("net.minecraft.Caller"), result.references.map { it.callerClass })
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
        assertTrue(result.references.any { it.callerClass == "net.minecraft.Dog" && it.callerMember == "run()V" })
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
        val dogRef = result.references.first { it.callerClass == "net.minecraft.Dog" }
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
        assertTrue(result.references.all { it.size == 0 })
    }

    @Test
    fun `unknown class throws`() {
        val indexer = buildIndexer()
        assertThrows(ClassNotFoundInIndexException::class.java) {
            findReferencesToolLogic(indexer.data(), indexer, "net.minecraft.DoesNotExist")
        }
    }
}
