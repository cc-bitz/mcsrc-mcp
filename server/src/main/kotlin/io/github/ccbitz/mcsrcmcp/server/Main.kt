package io.github.ccbitz.mcsrcmcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

fun main() = runBlocking {
    // Stdio MCP transport owns stdout. Keep kotlin-logging's own startup diagnostic off stdout,
    // and route the slf4j-simple backend (which kotlin-logging delegates to) to stderr.
    KotlinLoggingConfiguration.logStartupMessage = false
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")

    val server = buildServer()
    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    )

    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}
