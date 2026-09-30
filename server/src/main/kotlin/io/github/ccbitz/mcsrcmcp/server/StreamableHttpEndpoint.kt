package io.github.ccbitz.mcsrcmcp.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.heartbeat
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How long a Streamable HTTP session may go without a request before it's closed. Generous on purpose: a
 * client idle past it gets a 404 and has to initialize again, which the spec obliges it to do, but a
 * session costs only a few kilobytes, so this exists to bound the ones abandoned clients leave behind rather
 * than to reclaim anything from live ones.
 */
val HTTP_SESSION_IDLE_TIMEOUT = 24.hours

/** The header carrying the session id; the SDK has the same constant, but internal. */
const val MCP_SESSION_ID_HEADER = "mcp-session-id"

// Also how an abandoned GET stream is noticed: a peer that died without closing the socket only shows up as
// a failed write, and a stream with nothing to say otherwise never writes.
private val SSE_HEARTBEAT = 30.seconds

/**
 * Serves [server] over Streamable HTTP at [MCP_PATH], in JSON-response mode: a POST is answered with the
 * JSON-RPC response itself, and server-initiated messages (progress, list changes) go down the GET stream.
 *
 * The SDK's `Application.mcpStreamableHttp` would do this routing, but it assumes a server per session. It
 * chains a new `onClose` callback onto the [Server] for every session, which on one server shared by every
 * client for the life of the process is a leak - and it never closes a session whose client simply went away,
 * where a WebSocket session ends when its socket does. So sessions are tracked here instead, and any left
 * idle longer than [idleTimeout] with no request in flight are closed.
 */
fun Route.mcpStreamableHttp(server: Server, idleTimeout: Duration = HTTP_SESSION_IDLE_TIMEOUT) {
    class HttpSession(val transport: StreamableHttpServerTransport) {

        // An open GET stream counts as in flight, so a client listening for notifications is never
        // considered idle however long it goes between requests.
        private val inFlight = AtomicInteger()

        @Volatile
        private var lastSeen = TimeSource.Monotonic.markNow()

        val idle: Boolean
            get() = inFlight.get() == 0 && lastSeen.elapsedNow() > idleTimeout

        suspend fun serve(call: ApplicationCall, stream: ServerSSESession? = null) {
            inFlight.incrementAndGet()
            try {
                transport.handleRequest(stream, call)
            } finally {
                lastSeen = TimeSource.Monotonic.markNow()
                inFlight.decrementAndGet()
            }
        }

    }

    val sessions = ConcurrentHashMap<String, HttpSession>()

    // Application is the server's lifetime scope, so the sweep stops with it.
    application.launch {
        val period = idleTimeout.coerceAtMost(1.hours)
        while (isActive) {
            delay(period)
            for ((id, session) in sessions) {
                if (session.idle && sessions.remove(id, session))
                    session.transport.close()
            }
        }
    }

    // Mirrors the transport's own rejections: a JSON-RPC error body, written as text so a client that
    // accepts only text/event-stream still gets the status.
    suspend fun ApplicationCall.reject(status: HttpStatusCode, message: String) {
        val error = JSONRPCError(id = null, error = RPCError(code = RPCError.ErrorCode.CONNECTION_CLOSED, message = message))
        respondText(McpJson.encodeToString(JSONRPCError.serializer(), error), ContentType.Application.Json, status)
    }

    suspend fun ApplicationCall.existingSession(): HttpSession? {
        val id = request.header(MCP_SESSION_ID_HEADER)
        if (id.isNullOrEmpty()) {
            reject(HttpStatusCode.BadRequest, "Bad Request: No valid session ID provided")
            return null
        }
        return sessions[id] ?: null.also { reject(HttpStatusCode.NotFound, "Session not found") }
    }

    route(MCP_PATH) {
        // Not Ktor's sse {}: that commits a 200 and the stream's headers before its handler runs, so an unknown
        // session could only be answered with an empty stream rather than the 404 that tells a client to
        // initialize again.
        get {
            val session = call.existingSession() ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                SSEServerContent(call) {
                    heartbeat { period = SSE_HEARTBEAT }
                    session.serve(call, this)
                }
            )
        }

        post {
            if (call.request.header(MCP_SESSION_ID_HEADER) != null) {
                call.existingSession()?.serve(call)
                return@post
            }

            // No session id, so this has to be an initialize. The transport assigns the id while handling it,
            // which is when the session becomes reachable.
            val transport = StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true))
            val session = HttpSession(transport)
            transport.setOnSessionInitialized { id -> sessions[id] = session }
            transport.setOnSessionClosed { id -> sessions.remove(id) }
            server.createSession(transport)
            session.serve(call)
            // Anything but an initialize is rejected, which leaves a session registered on the server that no
            // client holds an id for.
            if (transport.sessionId == null)
                transport.close()
        }

        delete {
            call.existingSession()?.serve(call)
        }
    }
}
