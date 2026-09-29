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
 * All of vanilla shares one pool. The key covers a class and its inner classes but not the rest of
 * the jar Vineflower reads while decompiling it (supertype signatures, the same-package names its
 * import collision check looks at), so in principle an unchanged class could decompile differently
 * once enough around it changed - which is why pools were first scoped to one release train. It
 * didn't survive measurement: every class whose own bytes were identical but whose dependencies'
 * API changed (7,338 of them, over 26.3-pre-2 -> rc-2, 26.3 -> 26.4-snapshot-1 and 26.2 -> 26.3),
 * decompiled fresh against each version's own jar, came out identical, while a control run over
 * classes that did change caught 185 of 200. Pooling across trains is what reuses the 92% a release
 * shares with the next snapshot and the 65% it shares with the next release.
 *
 * A fork's pool stays per Minecraft version: its builds of one version are near-identical, and that
 * measurement covered vanilla only.
 */
object SourcePools {

    /** One line per cache key the workspace's classes produce, written beside its index.bin. */
    const val KEYS_FILE = "source-keys.txt"

    /** Vanilla's one pool. Not a Minecraft version id, so it can't collide with a fork's pool key. */
    const val VANILLA_POOL = "all"

    /** The pool a workspace of [variant] on Minecraft [mcVersion] shares. */
    fun poolKey(variant: String, mcVersion: String): String =
        if (variant == Variants.VANILLA) VANILLA_POOL else mcVersion

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
