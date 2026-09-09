// Serializes an arbitrary object graph into JSON with hard bounds: depth, total nodes, fields per
// object, string length. The values arriving here are live game objects (ItemStacks, trade lists,
// fastutil maps) that were never meant to be serialized whole - cycles exist, back-references to
// registries exist, and one ItemStack can drag in half the component system. The output is
// descriptive, not faithful: registry objects collapse to their ids, deep structure is capped,
// and everything past the depth budget becomes a toString. Read it for values, not for types.
package io.github.ccbitz.mcsrcmcp.bridge;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;

public final class BoundedWriter {
    private static final int MAX_DEPTH = 5;
    private static final int MAX_NODES = 20_000;
    private static final int MAX_FIELDS = 24;
    private static final int MAX_STRING = 4_000;

    private int nodes;
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();

    // value -> registry path ("minecraft:iron_ingot"), built once after bootstrap by walking every
    // Registry-typed static field of BuiltInRegistries. Identity-based on purpose: registry values
    // don't override equals, and identity is exactly the relationship "this object is the
    // registered one".
    private static volatile IdentityHashMap<Object, String> registryNames;

    public String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    private void writeValue(StringBuilder sb, Object value, int depth) {
        if (nodes++ > MAX_NODES) {
            sb.append("\"<truncated: node budget>\"");
            return;
        }
        if (value == null) {
            sb.append("null");
            return;
        }
        if (depth > MAX_DEPTH) {
            sb.append(quote(shorten(safeToString(value))));
            return;
        }

        String registryName = registryNameOf(value);
        if (registryName != null) {
            sb.append(quote(registryName));
            return;
        }

        if (value instanceof String s) {
            sb.append(quote(shorten(s)));
            return;
        }
        if (value instanceof Enum<?> e) {
            sb.append(quote(e.name()));
            return;
        }
        if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
            sb.append(quote(d.toString()));
            return;
        }
        if (value instanceof Float f && (f.isNaN() || f.isInfinite())) {
            sb.append(quote(f.toString()));
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
            return;
        }
        if (value instanceof Character c) {
            sb.append(quote(c.toString()));
            return;
        }
        if (value instanceof Class<?> c) {
            sb.append(quote(c.getName()));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            writeMapping(sb, map.keySet().toArray(), key -> map.get(key), depth);
            return;
        }
        if (value instanceof Iterable<?> it) {
            java.util.List<Object> items = new java.util.ArrayList<>();
            it.forEach(items::add);
            writeSequence(sb, items.toArray(), depth);
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object[] boxed = new Object[length];
            for (int i = 0; i < length; i++) boxed[i] = Array.get(value, i);
            writeSequence(sb, boxed, depth);
            return;
        }
        if (writeFastutilMap(sb, value, depth)) {
            return;
        }
        writeObjectSummary(sb, value, depth);
    }

    private void writeSequence(StringBuilder sb, Object[] items, int depth) {
        // No identity-based cycle detection here: iterables and arrays are boxed into fresh arrays
        // on the way in, so identity is already lost. A sequence that contains itself expands
        // until the depth budget stops it - bounded, and the common case (a trade list of items)
        // isn't cyclic anyway.
        sb.append('[');
        for (int i = 0; i < items.length; i++) {
            if (i > 0) sb.append(',');
            writeValue(sb, items[i], depth + 1);
        }
        sb.append(']');
    }

    private interface Getter {
        Object apply(Object key) throws Exception;
    }

    private void writeMapping(StringBuilder sb, Object[] keys, Getter getter, int depth) {
        sb.append('{');
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(',');
            Object value;
            try {
                value = getter.apply(keys[i]);
            } catch (Exception e) {
                value = null;
            }
            sb.append(quote(keyName(keys[i]))).append(':');
            writeValue(sb, value, depth + 1);
        }
        sb.append('}');
    }

    private void writeObjectSummary(StringBuilder sb, Object value, int depth) {
        if (seen.put(value, Boolean.TRUE) != null) {
            sb.append("\"<cycle>\"");
            return;
        }
        sb.append("{\"type\":").append(quote(value.getClass().getSimpleName())).append(",\"fields\":{");
        int written = 0;
        for (Class<?> k = value.getClass(); k != null && k != Object.class && written < MAX_FIELDS; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || written >= MAX_FIELDS) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(value);
                    // Null fields are skipped rather than listed: the summary is dense on purpose,
                    // and game objects carry plenty of always-null transient state.
                    if (v == null) continue;
                    if (written++ > 0) sb.append(',');
                    sb.append(quote(f.getName())).append(':');
                    writeValue(sb, v, depth + 1);
                } catch (Throwable ignored) {
                    // An unreadable field (hidden by module rules, threw in a getter) never sinks
                    // the whole serialization.
                }
            }
        }
        sb.append("}}");
        seen.remove(value);
    }

    // fastutil's primitive-valued maps (Object2FloatMap<Item> for compostables, Int2ObjectMap for
    // trades...) implement java.util.Map only in the boxed flavor, and some of the typed ones
    // don't at all. Any method ending in "EntrySet" that returns an Iterable is treated as the
    // map's entries; entries answer getKey()/getValue() via reflection (getValue boxes primitives).
    private boolean writeFastutilMap(StringBuilder sb, Object value, int depth) {
        if (!value.getClass().getName().startsWith("it.unimi.dsi.fastutil")) return false;
        Method entrySet = null;
        for (Method m : value.getClass().getMethods()) {
            String name = m.getName();
            if (name.endsWith("EntrySet") && !name.equals("entrySet") && m.getParameterCount() == 0
                && Iterable.class.isAssignableFrom(m.getReturnType())) {
                entrySet = m;
                break;
            }
        }
        if (entrySet == null) return false;
        try {
            Iterable<?> entries = (Iterable<?>) entrySet.invoke(value);
            sb.append('{');
            boolean first = true;
            for (Object entry : entries) {
                if (!first) sb.append(',');
                first = false;
                Object key = entry.getClass().getMethod("getKey").invoke(entry);
                Object entryValue = entry.getClass().getMethod("getValue").invoke(entry);
                sb.append(quote(keyName(key))).append(':');
                writeValue(sb, entryValue, depth + 1);
            }
            sb.append('}');
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String keyName(Object key) {
        if (key == null) return "null";
        String registryName = registryNameOf(key);
        if (registryName != null) return registryName;
        String s = safeToString(key);
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    // toString on live game data is best-effort: a self-referential collection recurses until the
    // stack dies, and a buggy toString can throw anything. Neither is allowed to sink a query that
    // already produced good output.
    private static String safeToString(Object value) {
        try {
            return value.toString();
        } catch (Throwable t) {
            return "<toString failed: " + t.getClass().getSimpleName() + ">";
        }
    }

    private static String registryNameOf(Object value) {
        IdentityHashMap<Object, String> index = registryNames;
        if (index == null) {
            index = buildRegistryIndex();
            registryNames = index;
        }
        return index.get(value);
    }

    private static IdentityHashMap<Object, String> buildRegistryIndex() {
        IdentityHashMap<Object, String> index = new IdentityHashMap<>();
        try {
            Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Class<?> registryType = Class.forName("net.minecraft.core.Registry");
            for (Field f : registries.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !registryType.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object registry = f.get(null);
                    if (registry == null) continue;
                    // Registry#entrySet(): Set<Map.Entry<ResourceKey<T>, T>>. Falls back to
                    // holders() (Set<Holder.Reference<T>>) for versions where entrySet drifted;
                    // both shapes expose getKey/getValue-style access via reflection below.
                    Iterable<?> entries = null;
                    for (String methodName : new String[] {"entrySet", "holders"}) {
                        try {
                            Object candidate = registry.getClass().getMethod(methodName).invoke(registry);
                            if (candidate instanceof Iterable<?> iterable) {
                                entries = iterable;
                                break;
                            }
                        } catch (Throwable ignored) {
                            // try the next shape
                        }
                    }
                    if (entries == null) continue;
                    for (Object entry : entries) {
                        Object key = invokeFirst(entry, "getKey", "key");
                        Object location = key == null ? null : invokeFirst(key, "location", "getLocation");
                        Object registered = invokeFirst(entry, "getValue", "value");
                        if (location != null && registered != null) {
                            index.put(registered, location.toString());
                        }
                    }
                } catch (Throwable ignored) {
                    // One unreadable registry never blocks the rest of the index.
                }
            }
        } catch (Throwable ignored) {
            // No game on the classpath (unit tests): the index stays empty, values serialize
            // descriptively instead of as ids.
        }
        return index;
    }

    private static Object invokeFirst(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                return target.getClass().getMethod(name).invoke(target);
            } catch (Throwable ignored) {
                // try the next name
            }
        }
        return null;
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String shorten(String s) {
        return s.length() <= MAX_STRING ? s : s.substring(0, MAX_STRING) + "...<truncated>";
    }
}
