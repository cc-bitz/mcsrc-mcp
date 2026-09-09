package io.github.ccbitz.mcsrcmcp.cache

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class VersionManifest(val versions: List<VersionListEntry>)

@Serializable
data class VersionListEntry(
    val id: String,
    val type: String,
    val url: String,
    val time: String,
    val releaseTime: String,
    val sha1: String,
)

@Serializable
data class VersionDetail(
    val downloads: VersionDownloads,
    val libraries: List<VersionLibrary> = emptyList(),
    val javaVersion: VersionJavaVersion? = null,
)

@Serializable
data class VersionDownloads(
    val client: DownloadArtifact,
    @SerialName("client_mappings") val clientMappings: DownloadArtifact? = null,
)

@Serializable
data class DownloadArtifact(val url: String, val sha1: String, val size: Long)

// Libraries used to be parsed away: VersionDetail kept only `downloads`, which was fine while
// nothing needed a classpath. The datagen reports run net.minecraft.data.Main from this server's
// own subprocess, and that needs every rule-applicable library jar on the classpath.
@Serializable
data class VersionLibrary(
    val name: String,
    val downloads: VersionLibraryDownloads? = null,
    val rules: List<VersionLibraryRule> = emptyList(),
)

@Serializable
data class VersionLibraryDownloads(val artifact: VersionLibraryArtifact? = null)

@Serializable
data class VersionLibraryArtifact(val path: String, val sha1: String, val url: String)

@Serializable
data class VersionLibraryRule(
    val action: String,
    val os: VersionLibraryRuleOs? = null,
)

@Serializable
data class VersionLibraryRuleOs(val name: String? = null, val arch: String? = null)

@Serializable
data class VersionJavaVersion(val component: String, val majorVersion: Int)

/**
 * Official-launcher rule semantics: a library with no rules applies everywhere; otherwise the last
 * rule whose os constraint matches decides - a rule with no os section matches every machine -
 * defaulting to disallow when nothing matches. [osName]/[osArch] are raw JVM values
 * ("Windows 11", "amd64"); the launcher's own names ("windows"/"osx"/"linux") and its arch
 * convention (only "x86", meaning 32-bit) are normalized here.
 */
fun VersionLibrary.appliesTo(osName: String, osArch: String): Boolean {
    if (rules.isEmpty()) return true
    val launcherOs = when {
        osName.startsWith("Windows") -> "windows"
        osName.startsWith("Mac") -> "osx"
        osName.startsWith("Linux") -> "linux"
        else -> osName.lowercase()
    }
    val is32Bit = osArch == "x86" || osArch == "i386"
    var allowed = false
    for (rule in rules) {
        val os = rule.os
        val matches = (os?.name == null || os.name == launcherOs) &&
            (os?.arch == null || (os.arch == "x86") == is32Bit)
        if (matches) allowed = rule.action == "allow"
    }
    return allowed
}

fun VersionLibrary.appliesToThisJvm(): Boolean =
    appliesTo(System.getProperty("os.name") ?: "", System.getProperty("os.arch") ?: "")

/**
 * The library artifacts a classpath needs on the given machine: rule-applicable entries that ship
 * a main artifact, in manifest order. Natives classifiers are skipped - a datagen run opens no
 * window and touches no native library.
 */
fun VersionDetail.classpathLibraryArtifacts(osName: String, osArch: String): List<VersionLibraryArtifact> =
    libraries
        .filter { it.appliesTo(osName, osArch) }
        .mapNotNull { it.downloads?.artifact }

private val manifestJson = Json { ignoreUnknownKeys = true }

fun parseVersionManifest(text: String): VersionManifest = manifestJson.decodeFromString(text)

fun parseVersionDetail(text: String): VersionDetail = manifestJson.decodeFromString(text)
