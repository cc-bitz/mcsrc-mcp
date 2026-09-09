plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "mcsrc-mcp"
include("core", "cache", "server", "bridge")
