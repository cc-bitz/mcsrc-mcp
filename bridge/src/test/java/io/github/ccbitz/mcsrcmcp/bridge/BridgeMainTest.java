package io.github.ccbitz.mcsrcmcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// handle() only bootstraps the game for net.minecraft./com.mojang. classes, so JDK classes
// exercise the protocol end to end without a Minecraft jar anywhere on the classpath.
class BridgeMainTest {
    @Test
    void staticReadsAJdkField() throws Exception {
        assertEquals("{\"ok\":true,\"value\":2147483647}", BridgeMain.handle("static\tjava.lang.Integer\tMAX_VALUE"));
    }

    @Test
    void slashedClassNamesWork() throws Exception {
        assertEquals("{\"ok\":true,\"value\":2147483647}", BridgeMain.handle("static\tjava/lang/Integer\tMAX_VALUE"));
    }

    @Test
    void unknownFieldIsAnErrorNotACrash() throws Exception {
        String response = BridgeMain.handle("static\tjava.lang.Integer\tNOPE");
        assertTrue(response.contains("\"ok\":false"));
        assertTrue(response.contains("NOPE"));
    }

    @Test
    void nonStaticFieldIsRejected() throws Exception {
        String response = BridgeMain.handle("static\tjava.util.ArrayList\tsize");
        assertTrue(response.contains("not static"));
    }

    @Test
    void callInvokesAStaticNoArgMethod() throws Exception {
        String response = BridgeMain.handle("call\tjava.lang.System\tlineSeparator");
        assertTrue(response.startsWith("{\"ok\":true,\"value\":\""));
    }

    @Test
    void malformedLinesAreErrors() throws Exception {
        assertTrue(BridgeMain.handle("garbage").contains("\"ok\":false"));
        assertTrue(BridgeMain.handle("bogus\tjava.lang.Integer\tMAX_VALUE").contains("unknown op"));
    }
}
