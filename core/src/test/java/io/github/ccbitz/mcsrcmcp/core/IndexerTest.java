package io.github.ccbitz.mcsrcmcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class IndexerTest {
    private byte[] loadFixture(String internalName) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "fixture class not found on test classpath: " + internalName);
            return in.readAllBytes();
        }
    }

    @Test
    void indexesClassHierarchyAndMembers() throws IOException {
        Indexer indexer = new Indexer();
        indexer.index(loadFixture("net/minecraft/Animal"));
        indexer.index(loadFixture("net/minecraft/Dog"));

        IndexData data = indexer.data();

        ClassData animal = data.classes().get("net/minecraft/Animal");
        assertNotNull(animal);
        assertEquals("java/lang/Object", animal.superName());
        assertTrue(animal.interfaces().isEmpty());

        ClassData dog = data.classes().get("net/minecraft/Dog");
        assertNotNull(dog);
        assertEquals("net/minecraft/Animal", dog.superName());
        assertEquals(java.util.List.of("java/lang/Runnable"), dog.interfaces());

        MemberData animalMembers = data.members().get("net/minecraft/Animal");
        assertTrue(animalMembers.methods().contains(new Entry.Method("net/minecraft/Animal", "staticSound", "()V")));
        assertTrue(animalMembers.methods().contains(new Entry.Method("net/minecraft/Animal", "makeSound", "()V")));

        MemberData dogMembers = data.members().get("net/minecraft/Dog");
        assertTrue(dogMembers.methods().contains(new Entry.Method("net/minecraft/Dog", "run", "()V")));
    }

    @Test
    void indexesCrossClassMethodReferences() throws IOException {
        Indexer indexer = new Indexer();
        indexer.index(loadFixture("net/minecraft/Animal"));
        indexer.index(loadFixture("net/minecraft/Dog"));

        java.util.Set<String> refs = indexer.references("net/minecraft/Animal:staticSound:()V");
        assertTrue(refs.contains("m:net/minecraft/Dog:run:()V"));
    }

    @Test
    void declarationOnlyPassSkipsReferenceTracking() throws IOException {
        Indexer indexer = new Indexer();
        indexer.indexDeclarations(loadFixture("net/minecraft/Animal"));
        indexer.indexDeclarations(loadFixture("net/minecraft/Dog"));

        assertEquals(0, indexer.referenceCount());
        assertNotNull(indexer.data().classes().get("net/minecraft/Dog"));
    }

    @Test
    void allReferencesReturnsEveryKeyAndValueSet() throws IOException {
        Indexer indexer = new Indexer();
        indexer.index(loadFixture("net/minecraft/Animal"));
        indexer.index(loadFixture("net/minecraft/Dog"));

        java.util.Map<String, java.util.Set<String>> all = indexer.allReferences();

        assertTrue(all.containsKey("net/minecraft/Animal:staticSound:()V"));
        assertTrue(all.get("net/minecraft/Animal:staticSound:()V").contains("m:net/minecraft/Dog:run:()V"));
        // Must match individual references(key) lookups exactly, not just be non-empty.
        assertEquals(indexer.references("net/minecraft/Animal:staticSound:()V"), all.get("net/minecraft/Animal:staticSound:()V"));
    }

    @Test
    void loadReferencesReplacesQueryableState() {
        Indexer indexer = new Indexer();
        java.util.Map<String, java.util.Set<String>> loaded = java.util.Map.of(
                "net/minecraft/Foo:bar:()V", java.util.Set.of("m:net/minecraft/Baz:qux:()V"));

        indexer.loadReferences(loaded);

        assertEquals(java.util.Set.of("m:net/minecraft/Baz:qux:()V"), indexer.references("net/minecraft/Foo:bar:()V"));
        assertTrue(indexer.references("net/minecraft/DoesNotExist:x:()V").isEmpty());
    }

    @Test
    void addAllMergesShardedIndexersWithReferenceSetUnion() throws IOException {
        // Two workers' shape: one indexed Animal, one indexed Dog, and both hold references
        // pointing INTO the class the other one visited - reference keys are the referenced
        // member, not the visited class, so they overlap where the visited classes don't.
        Indexer animalWorker = new Indexer();
        animalWorker.index(loadFixture("net/minecraft/Animal"));
        Indexer dogWorker = new Indexer();
        dogWorker.index(loadFixture("net/minecraft/Dog"));

        animalWorker.addAll(dogWorker);
        dogWorker.clear();

        IndexData data = animalWorker.data();
        assertNotNull(data.classes().get("net/minecraft/Animal"));
        assertNotNull(data.classes().get("net/minecraft/Dog"));
        assertNotNull(data.members().get("net/minecraft/Animal"));
        assertNotNull(data.members().get("net/minecraft/Dog"));

        java.util.Set<String> refs = animalWorker.references("net/minecraft/Animal:staticSound:()V");
        assertTrue(refs.contains("m:net/minecraft/Dog:run:()V"));
        // clear() after absorption is the caller's way to free the merged-away copy.
        assertEquals(0, dogWorker.referenceCount());
        assertTrue(dogWorker.data().classes().isEmpty());
    }

    // A class whose one method calls target.method()V - enough bytecode for a reference.
    private static byte[] callerClass(String name, String target, String method) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "call", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, target, method, "()V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    // A fork's own code lives outside net/minecraft - org/bukkit, io/papermc, org/purpurmc - so
    // the default Mojang-only scope would drop every reference to it.
    @Test
    void scopedIndexerKeepsReferencesToEveryClassInScope() {
        java.util.Set<String> jar = java.util.Set.of("org/bukkit/Caller", "org/bukkit/Target");
        Indexer indexer = new Indexer(jar::contains);
        indexer.index(callerClass("org/bukkit/Caller", "org/bukkit/Target", "run"));

        assertTrue(indexer.references("org/bukkit/Target:run:()V").contains("m:org/bukkit/Caller:call:()V"));
        // Targets outside the scope are still dropped - the JDK is referenced by every class.
        assertTrue(indexer.allReferences().keySet().stream().noneMatch(key -> key.startsWith("java/")));
    }

    @Test
    void defaultIndexerKeepsOnlyMojangReferences() {
        Indexer indexer = new Indexer();
        indexer.index(callerClass("org/bukkit/Caller", "org/bukkit/Target", "run"));

        assertTrue(indexer.references("org/bukkit/Target:run:()V").isEmpty());
    }

    @Test
    void loadReferencesClearsPreviousState() throws IOException {
        Indexer indexer = new Indexer();
        indexer.index(loadFixture("net/minecraft/Animal"));
        indexer.index(loadFixture("net/minecraft/Dog"));
        assertFalse(indexer.references("net/minecraft/Animal:staticSound:()V").isEmpty());

        indexer.loadReferences(java.util.Map.of());

        assertTrue(indexer.references("net/minecraft/Animal:staticSound:()V").isEmpty());
    }
}
