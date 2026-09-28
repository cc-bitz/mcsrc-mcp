package io.github.ccbitz.mcsrcmcp.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Shared, content-keyed decompile caches. A pool holds the per-class decompiled sources for every
 * workspace that maps onto it, keyed by [DecompileService.sourceCacheKey], so a class whose bytes
 * didn't change between two workspaces is decompiled once. It lives at
 * derived/<variant>/<pool key>/source-cache/<config version>, outside every per-workspace
 * directory, and cache eviction sweeps it down to the keys some workspace still on disk lists in
 * its [KEYS_FILE].
 *
 * Pools are deliberately narrower than "everything with this variant". The key covers a class and
 * its inner classes, but Vineflower also reads the rest of the jar - supertype signatures, the
 * same-package names its import collision check looks at - so an unchanged class can decompile
 * slightly differently once enough around it changed. Scoping a pool to one fork's builds of one
 * Minecraft version, or to one vanilla release train (26.3's pre-releases, release candidates and
 * the release itself all share 99%+ of their classes byte for byte), keeps nearly all the reuse
 * while bounding that drift to neighbours that are almost identical anyway.
 */
object SourcePools {

    /** One line per cache key the workspace's classes produce, written beside its index.bin. */
    const val KEYS_FILE = "source-keys.txt"

    // "26.3", "26.3-pre-2", "26.3-rc-3", "26.4-snapshot-1", "1.21.4-pre1" -> the release they lead
    // up to. Legacy weekly snapshots ("24w33a") and alpha/beta ids name no release, so each is its
    // own train - exactly the per-version cache they had before pools existed.
    private val RELEASE_TRAIN = Regex("""(\d+(?:\.\d+)+)(?:-.+)?""")

    fun releaseTrain(versionId: String): String =
        RELEASE_TRAIN.matchEntire(versionId)?.groupValues?.get(1) ?: versionId

    /** The pool a workspace of [variant] on Minecraft [mcVersion] shares. */
    fun poolKey(variant: String, mcVersion: String): String =
        if (variant == Variants.VANILLA) releaseTrain(mcVersion) else mcVersion

    fun dir(cacheRoot: Path, variant: String, mcVersion: String): Path =
        cacheRoot.resolve("derived").resolve(variant).resolve(poolKey(variant, mcVersion))
            .resolve("source-cache").resolve(SOURCE_CACHE_CONFIG_VERSION)

    /**
     * Records every key [classes] can hit in [unitDir], unless it is already there - a derived
     * unit's classes never change once saved, so neither does the list. Units from before pools
     * existed get theirs on their next load.
     */
    fun writeKeys(unitDir: Path, classes: Map<String, ByteArray>) {
        val target = unitDir.resolve(KEYS_FILE)
        if (Files.exists(target)) return

        // Only outer classes get entries - inner classes are decompiled into them.
        val keys = classes.keys.asSequence()
            .filter { '$' !in it }
            .map { DecompileService.sourceCacheKey(classes, it) }
            .joinToString("\n")
        val tmp = Files.createTempFile(unitDir, "keys-", ".tmp")
        try {
            Files.writeString(tmp, keys)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** The keys [unitDir] recorded, or null when it has no list yet - nothing is known about it. */
    fun readKeys(unitDir: Path): Set<String>? {
        val file = unitDir.resolve(KEYS_FILE)
        if (!Files.exists(file)) return null
        return Files.readAllLines(file).filterTo(HashSet()) { it.isNotBlank() }
    }

}
