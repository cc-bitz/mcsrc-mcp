package io.github.ccbitz.mcsrcmcp.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.shared.MCP_SUBPROTOCOL
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
import kotlin.time.Duration.Companion.milliseconds

private const val INITIALIZE_PARAMS =
    """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}"""

class McpEndpointTest {

    // Nothing here should reach the network: the eviction pass buildServer starts asks for the
    // version manifest, and treats a failed fetch as "nothing superseded".
    private object OfflineFetcher : BlobFetcher {

        override suspend fun fetch(url: String): ByteArray = throw java.io.IOException("offline: $url")

    }

    private fun serve(
        cacheRoot: Path,
        module: Application.(Server) -> Unit = { mcpModule(it) },
        test: suspend ApplicationTestBuilder.(Server) -> Unit
    ) {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val server = buildServer(cacheRoot, OfflineFetcher, scope = scope)
            testApplication {
                application { module(server) }
                test(server)
            }
        } finally {
            scope.cancel()
        }
    }

    // The HTTP transport alone, with sessions expiring fast enough to watch.
    private val quickExpiry: Application.(Server) -> Unit = { server ->
        install(ContentNegotiation) { json(McpJson) }
        routing { mcpStreamableHttp(server, idleTimeout = 100.milliseconds) }
    }

    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    private fun rpc(id: Int, method: String, params: String = "{}") =
        """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

    private fun toolNames(response: String): List<String> {
        val tools = Json.parseToJsonElement(response).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        return tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    }

    private suspend fun WebSocketSession.call(id: Int, method: String, params: String = "{}"): JsonObject {
        send(Frame.Text(rpc(id, method, params)))
        return Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
    }

    // What every MCP client does first; returns the tool names the server then lists.
    private suspend fun WebSocketSession.handshakeAndListTools(): List<String> {
        val init = call(1, "initialize", INITIALIZE_PARAMS)
        assertEquals(SERVER_NAME, init["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        send(Frame.Text("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        return toolNames(call(2, "tools/list").toString())
    }

    private suspend fun HttpClient.postRpc(body: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        post(MCP_PATH) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody(body)
            block()
        }

    private fun HttpRequestBuilder.session(id: String) {
        header(MCP_SESSION_ID_HEADER, id)
        header("MCP-Protocol-Version", "2025-06-18")
    }

    // The HTTP version of the handshake; returns the session id the server assigned.
    private suspend fun HttpClient.initializeOverHttp(): String {
        val init = postRpc(rpc(1, "initialize", INITIALIZE_PARAMS))
        assertEquals(HttpStatusCode.OK, init.status)
        val id = checkNotNull(init.headers[MCP_SESSION_ID_HEADER]) { "initialize assigned no session id" }
        val initialized = postRpc("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") { session(id) }
        assertEquals(HttpStatusCode.Accepted, initialized.status)
        return id
    }

    @Test
    fun `a client initializes and lists tools over the socket`(@TempDir cacheRoot: Path) = serve(cacheRoot) {
        wsClient().webSocket(MCP_PATH, request = { header(HttpHeaders.SecWebSocketProtocol, MCP_SUBPROTOCOL) }) {
            assertTrue("get_class_source" in handshakeAndListTools())
        }
    }

    @Test
    fun `a client initializes and lists tools over http`(@TempDir cacheRoot: Path) = serve(cacheRoot) {
        val id = client.initializeOverHttp()
        val tools = client.postRpc(rpc(2, "tools/list")) { session(id) }
        assertEquals(HttpStatusCode.OK, tools.status)
        assertTrue("get_class_source" in toolNames(tools.bodyAsText()))
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

    // A plain GET on the shared path must reach the HTTP transport, not a failed WebSocket upgrade.
    @Test
    fun `a stream opened without a session is a bad request`(@TempDir cacheRoot: Path) = serve(cacheRoot) {
        val response = client.get(MCP_PATH) { header(HttpHeaders.Accept, "text/event-stream") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a sessionless request that isn't an initialize leaves no session behind`(@TempDir cacheRoot: Path) =
        serve(cacheRoot) { server ->
            val response = client.postRpc(rpc(1, "tools/list"))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(server.sessions.isEmpty())
        }

    @Test
    fun `deleting a session ends it`(@TempDir cacheRoot: Path) = serve(cacheRoot) { server ->
        val id = client.initializeOverHttp()
        assertEquals(1, server.sessions.size)
        assertEquals(HttpStatusCode.OK, client.delete(MCP_PATH) { session(id) }.status)
        assertEquals(HttpStatusCode.NotFound, client.postRpc(rpc(2, "tools/list")) { session(id) }.status)
        assertTrue(server.sessions.isEmpty())
    }

    // A client that exits without a DELETE leaves its session behind, and this server outlives
    // every client that connects to it.
    @Test
    fun `an idle session is closed`(@TempDir cacheRoot: Path) = serve(cacheRoot, module = quickExpiry) { server ->
        val id = client.initializeOverHttp()
        withTimeout(5_000) {
            while (server.sessions.isNotEmpty())
                delay(50)
        }
        assertEquals(HttpStatusCode.NotFound, client.postRpc(rpc(2, "tools/list")) { session(id) }.status)
    }

    // A client listening for notifications makes no requests while it waits for one.
    @Test
    fun `a session with an open stream is not idle`(@TempDir cacheRoot: Path) = serve(cacheRoot, module = quickExpiry) { server ->
        val id = client.initializeOverHttp()
        client.prepareGet(MCP_PATH) {
            session(id)
            header(HttpHeaders.Accept, "text/event-stream")
        }.execute { stream ->
            assertEquals(HttpStatusCode.OK, stream.status)
            assertEquals(ContentType.Text.EventStream, stream.contentType()?.withoutParameters())
            delay(500)
            assertEquals(1, server.sessions.size)
        }
    }

    // Any page in the user's browser can open a WebSocket to a loopback port - CORS doesn't apply
    // to the handshake - and extract writes wherever it's told.
    @Test
    fun `a handshake from a browser page on another site is refused`(@TempDir cacheRoot: Path) = serve(cacheRoot) {
        val response = client.get(MCP_PATH) { header(HttpHeaders.Origin, "https://example.com") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // Nor does it apply to a cross-site POST the browser considers "simple", and the request is
    // processed whether or not the page can read the reply.
    @Test
    fun `a post from a browser page on another site is refused`(@TempDir cacheRoot: Path) = serve(cacheRoot) {
        val response = client.postRpc(rpc(1, "initialize", INITIALIZE_PARAMS)) { header(HttpHeaders.Origin, "https://example.com") }
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
