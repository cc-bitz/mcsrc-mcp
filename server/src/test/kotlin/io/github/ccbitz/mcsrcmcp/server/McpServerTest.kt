package io.github.ccbitz.mcsrcmcp.server

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpServerTest {
    private fun classArgRequest(vararg args: Pair<String, String>): CallToolRequest =
        CallToolRequest(
            CallToolRequestParams(
                name = "get_class_source",
                arguments = buildJsonObject { for ((k, v) in args) put(k, JsonPrimitive(v)) },
            ),
        )

    @Test
    fun `classArg reads the documented class property`() {
        assertEquals("net.minecraft.world.level.Level", classArgRequest("class" to "net.minecraft.world.level.Level").classArg())
    }

    @Test
    fun `classArg falls back to className when class is absent`() {
        assertEquals("net.minecraft.world.level.Level", classArgRequest("className" to "net.minecraft.world.level.Level").classArg())
    }

    @Test
    fun `classArg prefers class over className when both are given`() {
        assertEquals("Right", classArgRequest("class" to "Right", "className" to "Wrong").classArg())
    }

    @Test
    fun `classArg falls back to targetClass when class and className are absent`() {
        assertEquals("net.minecraft.world.level.Level", classArgRequest("targetClass" to "net.minecraft.world.level.Level").classArg())
    }

    @Test
    fun `classArg prefers className over targetClass when both are given`() {
        assertEquals("Right", classArgRequest("className" to "Right", "targetClass" to "Wrong").classArg())
    }

    @Test
    fun `classArg is null when neither property is given`() {
        assertNull(classArgRequest().classArg())
    }

    private fun stringListArgRequest(value: JsonElement?): CallToolRequest =
        CallToolRequest(
            CallToolRequestParams(
                name = "search_code",
                arguments = value?.let { buildJsonObject { put("classes", it) } },
            ),
        )

    @Test
    fun `stringListArg reads a JSON array as-is`() {
        val request = stringListArgRequest(buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
        assertEquals(listOf("a", "b"), request.stringListArg("classes"))
    }

    // find_references' own callerClass field is a bare string, so a model scoping search_code
    // to one class from that result sometimes passes the string straight through instead of
    // wrapping it in an array - tolerate that guess instead of crashing on the JsonArray cast.
    @Test
    fun `stringListArg wraps a bare string into a one-element list`() {
        val request = stringListArgRequest(JsonPrimitive("net.minecraft.client.renderer.entity.EntityRenderers"))
        assertEquals(listOf("net.minecraft.client.renderer.entity.EntityRenderers"), request.stringListArg("classes"))
    }

    @Test
    fun `stringListArg is null when the property is absent`() {
        assertNull(stringListArgRequest(null).stringListArg("classes"))
    }

    // A tool result carrying source/bytecode/asset text JSON-encoded whole -
    // TextContent(Json.encodeToString(result)) - buries real newlines as literal "\n" inside a
    // single JSON string value, which clients rendering raw output show as one long escaped
    // blob. These cover the plain-text formatting.
    @Test
    fun `formatContentResult shows the label and body with no range note when nothing was cut`() {
        val text = formatContentResult("net.minecraft.Foo", "line one\nline two", startLine = 1, totalLines = 2, truncated = false)
        assertEquals("net.minecraft.Foo\n\n1\tline one\n2\tline two", text)
    }

    // The numbers are absolute, not relative to the slice - they're what gets cited as #L<n>, so a
    // paged read must not restart at 1.
    @Test
    fun `formatContentResult adds a range note when the content is a slice of a larger file`() {
        val text = formatContentResult("net.minecraft.Foo", "line five\nline six", startLine = 5, totalLines = 20, truncated = false)
        assertEquals("net.minecraft.Foo (lines 5-6 of 20)\n\n5\tline five\n6\tline six", text)
    }

    @Test
    fun `formatContentResult flags truncation with the start_line to continue from`() {
        val text = formatContentResult("net.minecraft.Foo", "line one", startLine = 1, totalLines = 500, truncated = true)
        assertEquals(
            "net.minecraft.Foo (lines 1-1 of 500, truncated - call again with start_line=2 to continue)\n\n1\tline one",
            text,
        )
    }

    // A file ending in a newline yields a trailing empty line, and mcsrc.dev counts it too - so it
    // gets a number rather than being trimmed away, or every citation past it would be off by one.
    @Test
    fun `formatContentResult numbers a trailing empty line rather than dropping it`() {
        val text = formatContentResult("net.minecraft.Foo", "only line\n", startLine = 1, totalLines = 2, truncated = false)
        assertEquals("net.minecraft.Foo\n\n1\tonly line\n2\t", text)
    }

    @Test
    fun `formatContentResult returns just the label when there is no content`() {
        assertEquals("net.minecraft.Foo", formatContentResult("net.minecraft.Foo", "", startLine = 1, totalLines = 0, truncated = false))
    }

    // Server.serverInfo/instructionsProvider are `protected` on the SDK's Server type (by
    // design, not reachable from an external test), so we assert on our own source-of-truth
    // constants directly and only check that construction itself doesn't throw.
    @Test
    fun `server identity and agent-guidance instructions are set as expected`() {
        assertEquals("mcsrc-mcp", SERVER_NAME)
        // The instructions carry only what the server is for plus the pointer at get_instructions;
        // the guidance itself moved behind that tool (AGENT_GUIDE), so it must not leak back here.
        assertTrue(AGENT_INSTRUCTIONS.contains("decompiled Minecraft"))
        assertTrue(AGENT_INSTRUCTIONS.contains("get_instructions"))
        assertFalse(AGENT_INSTRUCTIONS.contains("https://mcsrc.dev/2/"))

        // The per-tool descriptions deliberately no longer repeat what's true of every tool, so
        // the guide is the only place a model is told about aliases, dotted class names,
        // auto-prepare, and how to cite. Assert each survives an edit for brevity.
        assertTrue(AGENT_GUIDE.contains("infer"))
        assertTrue(AGENT_GUIDE.contains("latest-release"))
        assertTrue(AGENT_GUIDE.contains("net.minecraft.world.level.Level"))
        assertTrue(AGENT_GUIDE.contains("prepare_version"))
        assertTrue(AGENT_GUIDE.contains("get_report"))
        assertTrue(AGENT_GUIDE.contains("list_reports"))
        assertTrue(AGENT_GUIDE.contains("list_paths"))
        assertTrue(AGENT_GUIDE.contains("get_game_data"))
        assertTrue(AGENT_GUIDE.contains("https://mcsrc.dev/2/"))
        assertTrue(AGENT_GUIDE.contains("[Tag](https://mcsrc.dev/2/"))
        // mcsrc.dev hosts decompiled source only. A model that cites an asset there produces a
        // dead link, so the guide must say so outright and must not offer a second asset
        // host to pick the wrong one from.
        assertTrue(AGENT_GUIDE.contains("does NOT serve assets"))
        assertTrue(AGENT_GUIDE.contains("github.com/InventivetalentDev/minecraft-assets"))
        assertFalse(AGENT_GUIDE.contains("mcasset.cloud"))
    }

    @Test
    fun `buildServer constructs without throwing`() {
        val server = buildServer()
        assertNotNull(server)
    }
}
