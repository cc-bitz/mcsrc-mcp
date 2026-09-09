package io.github.ccbitz.mcsrcmcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoundedWriterTest {
    private final BoundedWriter writer = new BoundedWriter();

    @Test
    void writesPrimitivesStringsEnumsAndNulls() {
        assertEquals("null", writer.write(null));
        assertEquals("42", writer.write(42));
        assertEquals("true", writer.write(true));
        assertEquals("1.5", writer.write(1.5));
        // NaN/Infinity are not valid JSON numbers - they render as strings rather than corrupting
        // the response line.
        assertEquals("\"NaN\"", writer.write(Double.NaN));
        assertEquals("\"hello\"", writer.write("hello"));
        assertEquals("\"DAYS\"", writer.write(java.time.temporal.ChronoUnit.DAYS));
    }

    @Test
    void writesMapsListsAndArraysRecursively() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("count", 3);
        map.put("names", List.of("a", "b"));
        assertEquals("{\"count\":3,\"names\":[\"a\",\"b\"]}", writer.write(map));

        assertEquals("[1,2,3]", writer.write(new int[] {1, 2, 3}));
    }

    @Test
    void summarizesUnknownObjectsWithTheirFields() {
        class Holder {
            final String name = "x";
            final int size = 7;
        }
        String json = writer.write(new Holder());
        assertTrue(json.contains("\"type\":\"Holder\""));
        assertTrue(json.contains("\"name\":\"x\""));
        assertTrue(json.contains("\"size\":7"));
    }

    // Live game objects cycle (a trade list referencing its item, which references back). The
    // depth budget stops expansion; when the cap lands on a self-referential collection, whose
    // own toString recurses until the stack dies, safeToString contains that too. The fixture map
    // is an IdentityHashMap because a HashMap would recurse its own hashCode at insert time.
    @Test
    void selfReferentialStructuresStayBoundedAndDoNotThrow() {
        List<Object> list = new ArrayList<>();
        list.add(list);
        String json = writer.write(list);
        assertTrue(json.length() < 50_000);

        Map<Object, Object> left = new java.util.IdentityHashMap<>();
        Map<Object, Object> right = new java.util.IdentityHashMap<>();
        left.put(right, right);
        right.put(left, left);
        assertTrue(writer.write(left).length() < 50_000);
    }

    @Test
    void longStringsAreCut() {
        String json = writer.write("x".repeat(10_000));
        assertTrue(json.length() < 5_000);
        assertTrue(json.contains("truncated"));
    }

    @Test
    void quotesControlCharactersAndBackslashes() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", BoundedWriter.quote("a\"b\\c\nd"));
    }
}
