package io.github.ccbitz.mcsrcmcp.cache

import java.nio.file.Path
import java.nio.file.Paths

object CacheRoot {
    private const val ENV_OVERRIDE = "MCSRC_MCP_CACHE_DIR"
    private const val APP_DIR_NAME = "mcsrc-mcp"

    fun resolve(
        env: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
    ): Path {
        env[ENV_OVERRIDE]?.let { return Paths.get(it) }

        val lowerOs = osName.lowercase()
        return when {
            lowerOs.contains("win") -> {
                val base = env["LOCALAPPDATA"]
                    ?: throw IllegalStateException("LOCALAPPDATA is not set")
                Paths.get(base, APP_DIR_NAME, "cache")
            }
            lowerOs.contains("mac") -> {
                val home = env["HOME"] ?: System.getProperty("user.home")
                Paths.get(home, "Library", "Caches", APP_DIR_NAME)
            }
            else -> {
                val base = env["XDG_CACHE_HOME"]
                    ?: Paths.get(System.getProperty("user.home"), ".cache").toString()
                Paths.get(base, APP_DIR_NAME)
            }
        }
    }
}
