package io.github.ccbitz.mcsrcmcp.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.modelcontextprotocol.kotlin.sdk.shared.MCP_SUBPROTOCOL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WebSocketEndpointTest {

    // Nothing here should reach the network: the eviction pass buildServer starts asks for the
    // version manifest, and treats a failed fetch as "nothing superseded".
    private object OfflineFetcher : BlobFetcher {

        override suspend fun fetch(url: String): ByteArray = throw java.io.IOException("offline: $url")

    }

    private fun serve(cacheRoot: Path, test: suspend ApplicationTestBuilder.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob())
        try {
            testApplication {
                application { mcpWebSocketModule(buildServer(cacheRoot, OfflineFetcher, scope = scope)) }
                test()
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun WebSocketSession.call(id: Int, method: String, params: String = "{}"): JsonObject {
        send(Frame.Text("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""))
        return Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
    }

    // What every MCP client does first; returns the tool names the server then lists.
    private suspend fun WebSocketSession.handshakeAndListTools(): List<String> {
        val init = call(1, "initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}""")
        assertEquals(SERVER_NAME, init["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        send(Frame.Text("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        val tools = call(2, "tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
        return tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    }

    @Test
    fun `a client initializes and lists tools over the socket`(@TempDir cacheRoot: Path) = serve(cacheRoot) {
        wsClient().webSocket(MCP_PATH, request = { header(HttpHeaders.SecWebSocketProtocol, MCP_SUBPROTOCOL) }) {
            assertTrue("get_class_source" in handshakeAndListTools())
        }
    }

    // One server behind every connection is the point of the transport: clients share its warm
    // workspaces instead of each starting a JVM of its own.
    @Test
    fun `concurrent clients are separate sessions of one server`(@TempDir cacheRoot: Path) = serve(cacheRoot) {
        val client = wsClient()
        val toolLists = coroutineScope {
            (1..3).map {
                async {
                    var tools = emptyList<String>()
                    client.webSocket(MCP_PATH, request = { header(HttpHeaders.SecWebSocketProtocol, MCP_SUBPROTOCOL) }) {
                        tools = handshakeAndListTools()
                    }
                    tools
                }
            }.awaitAll()
        }
        assertEquals(1, toolLists.toSet().size)
        assertTrue(toolLists.first().isNotEmpty())
    }

    // Any page in the user's browser can open a WebSocket to a loopback port - CORS doesn't apply
    // to the handshake - and extract writes wherever it's told.
    @Test
    fun `a handshake from a browser page on another site is refused`(@TempDir cacheRoot: Path) = serve(cacheRoot) {
        val response = client.get(MCP_PATH) { header(HttpHeaders.Origin, "https://example.com") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `a loopback origin is let through`(@TempDir cacheRoot: Path) = serve(cacheRoot) {
        wsClient().webSocket(MCP_PATH, request = {
            header(HttpHeaders.SecWebSocketProtocol, MCP_SUBPROTOCOL)
            header(HttpHeaders.Origin, "http://localhost:3000")
        }) {
            assertTrue(handshakeAndListTools().isNotEmpty())
        }
    }

    @Test
    fun `port and host default to loopback when unset`() {
        assertEquals(DEFAULT_PORT, resolvePort(emptyMap()))
        assertEquals(DEFAULT_HOST, resolveHost(emptyMap()))
        assertEquals(8080, resolvePort(mapOf("MCSRC_MCP_PORT" to "8080")))
        assertEquals("0.0.0.0", resolveHost(mapOf("MCSRC_MCP_HOST" to "0.0.0.0")))
    }

    @Test
    fun `a port that is not a port number is an error, not the default`() {
        for (bad in listOf("http", "0", "70000"))
            assertThrows(IllegalArgumentException::class.java) { resolvePort(mapOf("MCSRC_MCP_PORT" to bad)) }
    }

}
