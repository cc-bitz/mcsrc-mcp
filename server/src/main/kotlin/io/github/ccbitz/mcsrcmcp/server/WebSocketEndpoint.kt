package io.github.ccbitz.mcsrcmcp.server

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.WebSocketMcpServerTransport
import io.modelcontextprotocol.kotlin.sdk.shared.MCP_SUBPROTOCOL

/** Serves [server] over WebSocket at [MCP_PATH], one session per connection. */
fun Route.mcpWebSocket(server: Server) {
    webSocket(MCP_PATH, MCP_SUBPROTOCOL) {
        server.createSession(WebSocketMcpServerTransport(this))
        // Returning from this block closes the socket, and the transport reads frames on a
        // coroutine of its own, so wait for the socket itself. Waiting on the session's onClose
        // instead would hang forever if the client dropped before the callback was registered.
        closeReason.await()
    }
}
