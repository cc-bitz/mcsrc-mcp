package io.github.ccbitz.mcsrcmcp.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.McpJson

/** Where the MCP endpoint is served: `ws://<host>:<port>/mcp` and `http://<host>:<port>/mcp`. */
const val MCP_PATH = "/mcp"

const val DEFAULT_HOST = "127.0.0.1"
const val DEFAULT_PORT = 25590

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

/**
 * The interface to bind, from `MCSRC_MCP_HOST`. Loopback unless told otherwise: the endpoint has no
 * authentication, and `extract` writes to any path it's given.
 */
fun resolveHost(env: Map<String, String> = System.getenv()): String =
    env["MCSRC_MCP_HOST"]?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST

/** The port to listen on, from `MCSRC_MCP_PORT`. */
fun resolvePort(env: Map<String, String> = System.getenv()): Int {
    val raw = env["MCSRC_MCP_PORT"]?.takeIf { it.isNotBlank() } ?: return DEFAULT_PORT
    return raw.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw IllegalArgumentException("MCSRC_MCP_PORT must be a port number (1-65535), got \"$raw\"")
}

/**
 * Serves [server] at [MCP_PATH] over both WebSocket and Streamable HTTP - a request carrying a WebSocket
 * upgrade goes to the socket, anything else to the HTTP transport. Every connection is a session of the
 * same [server], so all clients share one set of warm workspaces, one decompile queue and one JVM, whichever
 * transport they speak.
 *
 * Browsers are refused. A WebSocket handshake isn't subject to CORS, and neither is a "simple" cross-site
 * POST, so without this any page open in the user's browser could reach a loopback port and drive the
 * tools, `extract` included. MCP clients don't send an Origin header; browsers always do, including after a
 * DNS rebind (the Origin is still the attacker's site). A loopback Origin is let through, since a page served
 * from this machine can reach the port anyway.
 *
 * That is also why the SDK's own DNS-rebinding guard isn't used for the HTTP transport: it checks the Host
 * header against loopback too, which would lock out a client reaching an `MCSRC_MCP_HOST` bound elsewhere,
 * and would leave the two transports with different rules for the same port.
 */
fun Application.mcpModule(server: Server) {
    install(
        createApplicationPlugin("RejectBrowserOrigins") {
            onCall { call ->
                val origin = call.request.headers[HttpHeaders.Origin] ?: return@onCall
                val host = runCatching { Url(origin).host }.getOrNull()
                if (host !in LOOPBACK_HOSTS)
                    call.respond(HttpStatusCode.Forbidden)
            }
        },
    )
    install(WebSockets)
    // The HTTP transport responds with the SDK's message types, which only serialize correctly under
    // McpJson's settings (no explicit nulls, defaults encoded, no class discriminator).
    install(ContentNegotiation) { json(McpJson) }

    routing {
        mcpWebSocket(server)
        mcpStreamableHttp(server)
    }
}
