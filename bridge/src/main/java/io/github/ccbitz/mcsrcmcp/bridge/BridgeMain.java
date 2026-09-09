// The sidecar that boots the actual game so mcsrc-mcp can read what only a running game knows:
// villager trades, composting chances, attributes - anything code initializes at boot rather than
// shipping as JSON. Spawned by GameBridge with the version's client jar + libraries on the
// classpath; spoken to over stdin/stdout, one line per request, one line per response.
package io.github.ccbitz.mcsrcmcp.bridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

public final class BridgeMain {
    public static final String PROTOCOL = "v1";

    private static boolean booted;

    public static void main(String[] args) throws Exception {
        // stdout is the protocol channel. Anything the game's logging decides to print to
        // System.out must not corrupt it, so System.out is handed to stderr and the original
        // stream is kept for protocol output only.
        PrintStream protocol = System.out;
        System.setOut(System.err);
        protocol.println("{\"ok\":true,\"protocol\":\"" + PROTOCOL + "\"}");
        protocol.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String response;
            try {
                response = handle(line);
            } catch (Throwable t) {
                response = "{\"ok\":false,\"error\":" + BoundedWriter.quote(t.toString()) + "}";
            }
            protocol.println(response);
            protocol.flush();
        }
        // stdin closed - the server either shut down or is respawning us. Die quietly; a partial
        // bootstrap in a zombie process serves nobody.
    }

    // Requests are tab-separated rather than JSON on purpose: the bridge must run with only the
    // game's classpath (shipping a JSON parser inside it would mean a shaded dependency and a
    // bigger attack surface for classpath conflicts), and requests are two or three short strings.
    //
    //   static\t<class>\t<field>    read a static field
    //   call\t<class>\t<method>     invoke a static no-arg method (usually for its side effect,
    //                               e.g. filling a map from a bootStrap()-style method)
    //
    // Class names are dotted or slashed. Game classes trigger the one-time bootstrap
    // (SharedConstants.tryDetectVersion + Bootstrap.bootStrap) first; JDK classes don't, which is
    // what makes the bridge testable without a Minecraft jar at all.
    static String handle(String line) throws Exception {
        String[] parts = line.split("\t", -1);
        if (parts.length < 3) {
            return error("expected '<op>\\t<class>\\t<name>'");
        }
        String op = parts[0];
        String className = parts[1].replace('/', '.');
        String member = parts[2];

        if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")) {
            ensureBooted();
        }

        Class<?> clazz = Class.forName(className, true, BridgeMain.class.getClassLoader());
        switch (op) {
            case "static": {
                Field field = findField(clazz, member);
                if (field == null) return error("no field '" + member + "' on " + clazz.getName());
                if (!Modifier.isStatic(field.getModifiers())) return error("'" + member + "' is not static");
                field.setAccessible(true);
                return "{\"ok\":true,\"value\":" + new BoundedWriter().write(field.get(null)) + "}";
            }
            case "call": {
                Method method = findMethod(clazz, member);
                if (method == null) return error("no method '" + member + "' on " + clazz.getName());
                if (!Modifier.isStatic(method.getModifiers())) return error("'" + member + "' is not static");
                method.setAccessible(true);
                return "{\"ok\":true,\"value\":" + new BoundedWriter().write(method.invoke(null)) + "}";
            }
            default:
                return error("unknown op '" + op + "'");
        }
    }

    // SharedConstants.tryDetectVersion reads version.json off the classpath; Bootstrap.bootStrap
    // registers every vanilla object, which is the whole reason this process exists. If these
    // names drift in a future Minecraft version, ops fail with the reflection error and
    // GameBridge surfaces it - nothing here can silently return wrong data.
    private static synchronized void ensureBooted() throws Exception {
        if (booted) return;
        try {
            Class<?> shared = Class.forName("net.minecraft.SharedConstants");
            findMethod(shared, "tryDetectVersion").invoke(null);
        } catch (Throwable ignored) {
            // Version detection is a nicety - the version.json on the classpath is the right one.
        }
        Class<?> bootstrap = Class.forName("net.minecraft.server.Bootstrap");
        findMethod(bootstrap, "bootStrap").invoke(null);
        booted = true;
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> k = clazz; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        for (Class<?> k = clazz; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
        }
        return null;
    }

    private static String error(String message) {
        return "{\"ok\":false,\"error\":" + BoundedWriter.quote(message) + "}";
    }
}
