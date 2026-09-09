package io.github.ccbitz.mcsrcmcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class BytecodePrinterTest {
    private byte[] loadFixture(String internalName) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "fixture class not found on test classpath: " + internalName);
            return in.readAllBytes();
        }
    }

    @Test
    void printsReadableAsmTextForAMethod() throws IOException {
        byte[] animalBytes = loadFixture("net/minecraft/Animal");

        String text = BytecodePrinter.print(animalBytes);

        assertTrue(text.contains("staticSound"), "expected the method name in the output, got:\n" + text);
        assertTrue(text.contains("INVOKESTATIC") || text.contains("RETURN"), "expected real opcode mnemonics, got:\n" + text);
    }

    @Test
    void printsMultipleClassesConcatenated() throws IOException {
        byte[] animalBytes = loadFixture("net/minecraft/Animal");
        byte[] dogBytes = loadFixture("net/minecraft/Dog");

        String text = BytecodePrinter.print(animalBytes, dogBytes);

        assertTrue(text.contains("staticSound"));
        assertTrue(text.contains(" run("), "expected Dog's run() method in the output, got:\n" + text);
    }
}
