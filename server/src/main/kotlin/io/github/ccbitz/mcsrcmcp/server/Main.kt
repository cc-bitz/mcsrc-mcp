package io.github.ccbitz.mcsrcmcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.server.application.ServerReady
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    // stdout carries nothing now, but diagnostics stay on stderr so a launcher's redirect of one
    // stream still captures all of them.
    KotlinLoggingConfiguration.logStartupMessage = false
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")

    val host = resolveHost()
    val port = resolvePort()
    val server = buildServer()

    embeddedServer(CIO, host = host, port = port) {
        // Announced once bound rather than before start(), so a port already in use can't print
        // an address nothing is listening on.
        monitor.subscribe(ServerReady) { System.err.println("mcsrc-mcp: listening on ws://$host:$port$MCP_PATH") }
        mcpWebSocketModule(server)
    }.start(wait = true)
}
