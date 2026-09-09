package io.github.ccbitz.mcsrcmcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ClassFileRemapperTest {
    private byte[] loadFixture(String internalName) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "fixture class not found on test classpath: " + internalName);
            return in.readAllBytes();
        }
    }

    // Proguard mapping direction is "deobf -> obf:", matching Mojang's real client_mappings.txt.
    // Our compiled net.minecraft.Fauna / net.minecraft.Canine fixtures stand in for the
    // "obfuscated" input jar; the mapping renames them (and moves them into a net.minecraft.wolf
    // package) to simulate a real deobfuscation pass. Canine#bark() calls the INHERITED instance
    // method makeSound() without qualification, so javac compiles that call with owner=Canine
    // (not Fauna) even though makeSound is declared on Fauna — this is exactly the scenario
    // InheritanceAwareRemapper.findInheritedMemberOwner exists to handle.
    private static final String MAPPING_TEXT = """
            net.minecraft.wolf.Beast -> net.minecraft.Fauna:
                1:1:void makeNoise() -> makeSound
            net.minecraft.wolf.Hound -> net.minecraft.Canine:
                2:2:void woof() -> bark
            """;

    @Test
    void remapsClassNamesAndInheritedMethodCalls() throws IOException {
        byte[] faunaBytes = loadFixture("net/minecraft/Fauna");
        byte[] canineBytes = loadFixture("net/minecraft/Canine");

        Indexer indexer = new Indexer();
        indexer.indexDeclarations(faunaBytes);
        indexer.indexDeclarations(canineBytes);
        IndexData indexData = indexer.data();

        ClassFileRemapper remapper = new ClassFileRemapper(
                MAPPING_TEXT.getBytes(StandardCharsets.UTF_8), indexData);

        byte[] remappedFauna = remapper.remap(faunaBytes);
        byte[] remappedCanine = remapper.remap(canineBytes);

        ClassNameCapture faunaCapture = readClassStructure(remappedFauna);
        assertEquals("net/minecraft/wolf/Beast", faunaCapture.className);

        ClassNameCapture canineCapture = readClassStructure(remappedCanine);
        assertEquals("net/minecraft/wolf/Hound", canineCapture.className);
        assertEquals("net/minecraft/wolf/Beast", canineCapture.superName);

        // The key inheritance-aware assertion: Canine#bark() (remapped to "woof") calls the
        // inherited makeSound(), compiled with owner=Canine in the original bytecode (the
        // receiver's static type — an invokevirtual's owner slot is always that, not
        // necessarily the declaring class, and stays that way after remap: Canine -> Hound).
        // What genuinely requires the inheritance walk is the METHOD NAME: Canine's own
        // mapping section doesn't declare "makeSound" at all, so the remapper has to walk up
        // to Fauna's mapping entry to learn that "makeSound" -> "makeNoise". Look specifically
        // at the "woof" method's own invocations, not the whole class's (the implicit
        // constructor also has one, for its super() call — a second, separate confirmation the
        // class-level remap works, but not what this assertion is about).
        java.util.List<InvokedMethod> woofInvocations = canineCapture.invokedMethodsByMethod.get("woof");
        assertNotNull(woofInvocations);
        assertEquals(1, woofInvocations.size());
        InvokedMethod invoked = woofInvocations.get(0);
        assertEquals("net/minecraft/wolf/Hound", invoked.owner);
        assertEquals("makeNoise", invoked.name);
    }

    @Test
    void classMappingsExposesObfToDeobf() throws IOException {
        Indexer indexer = new Indexer();
        indexer.indexDeclarations(loadFixture("net/minecraft/Fauna"));
        indexer.indexDeclarations(loadFixture("net/minecraft/Canine"));

        ClassFileRemapper remapper = new ClassFileRemapper(
                MAPPING_TEXT.getBytes(StandardCharsets.UTF_8), indexer.data());

        var mappings = remapper.classMappings();
        assertEquals("net/minecraft/wolf/Beast", mappings.get("net/minecraft/Fauna"));
        assertEquals("net/minecraft/wolf/Hound", mappings.get("net/minecraft/Canine"));
    }

    private record InvokedMethod(String owner, String name) {
    }

    private static final class ClassNameCapture {
        String className;
        String superName;
        final java.util.Map<String, java.util.List<InvokedMethod>> invokedMethodsByMethod = new java.util.HashMap<>();
    }

    private ClassNameCapture readClassStructure(byte[] classBytes) {
        ClassNameCapture capture = new ClassNameCapture();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                capture.className = name;
                capture.superName = superName;
            }

            @Override
            public MethodVisitor visitMethod(int access, String enclosingName, String descriptor, String signature, String[] exceptions) {
                java.util.List<InvokedMethod> invocations =
                        capture.invokedMethodsByMethod.computeIfAbsent(enclosingName, ignored -> new java.util.ArrayList<>());
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        invocations.add(new InvokedMethod(owner, name));
                    }
                };
            }
        }, 0);
        return capture;
    }
}
