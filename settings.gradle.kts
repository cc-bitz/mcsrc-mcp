plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mcsrc-mcp"
include("core", "cache", "server", "bridge", "decompiler")
