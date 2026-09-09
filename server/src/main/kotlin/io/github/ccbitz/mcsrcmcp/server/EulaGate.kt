package io.github.ccbitz.mcsrcmcp.server

import java.nio.file.Files
import java.nio.file.Path

class EulaNotAcceptedException(message: String) : IllegalStateException(message)

class EulaGate(
    private val configFile: Path,
    private val env: Map<String, String> = System.getenv(),
) {
    fun isAccepted(): Boolean {
        if (env["MCSRC_MCP_ACCEPT_EULA"] == "1") {
            return true
        }
        return Files.exists(configFile) && Files.readString(configFile).trim() == "accepted"
    }

    fun accept() {
        Files.createDirectories(configFile.parent)
        Files.writeString(configFile, "accepted")
    }

    fun requireAccepted() {
        if (!isAccepted()) {
            throw EulaNotAcceptedException(
                "The Minecraft EULA and Mojang's mapping-file terms have not been accepted. " +
                    "Set MCSRC_MCP_ACCEPT_EULA=1 after reading https://www.minecraft.net/en-us/eula, " +
                    "or call the accept step."
            )
        }
    }
}
