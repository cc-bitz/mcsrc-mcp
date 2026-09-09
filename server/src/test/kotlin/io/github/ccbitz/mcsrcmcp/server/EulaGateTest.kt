package io.github.ccbitz.mcsrcmcp.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EulaGateTest {
    @Test
    fun `not accepted by default`(@TempDir tempDir: Path) {
        val gate = EulaGate(tempDir.resolve("eula.txt"), env = emptyMap())
        assertFalse(gate.isAccepted())
        assertThrows(EulaNotAcceptedException::class.java) { gate.requireAccepted() }
    }

    @Test
    fun `env var accepts without a config file`(@TempDir tempDir: Path) {
        val gate = EulaGate(tempDir.resolve("eula.txt"), env = mapOf("MCSRC_MCP_ACCEPT_EULA" to "1"))
        assertTrue(gate.isAccepted())
        gate.requireAccepted() // does not throw
    }

    @Test
    fun `accept() persists acceptance across instances`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("nested").resolve("eula.txt")
        EulaGate(configFile, env = emptyMap()).accept()

        val reopened = EulaGate(configFile, env = emptyMap())
        assertTrue(reopened.isAccepted())
    }
}
