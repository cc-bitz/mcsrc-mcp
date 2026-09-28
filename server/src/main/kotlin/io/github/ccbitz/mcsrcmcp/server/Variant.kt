package io.github.ccbitz.mcsrcmcp.server

/**
 * A source variant: a flavor of Minecraft code this server can serve. "vanilla" is Mojang's own
 * code; every other variant is a server fork that publishes paperweight dev bundles (Paper and the
 * forks built on it), all served by the same [DevBundleWorkspaceBuilder] pipeline. Registered here
 * so list_variants, the variant argument's validation and the builder wiring stay data-driven -
 * adding a fork means adding a descriptor with its dev-bundle repository, and touching nothing else.
 */
data class Variant(
    val id: String,
    val title: String,
    val description: String,
    // The maven directory holding the fork's `dev-bundle` artifact (the one with maven-metadata.xml
    // in it). Null only for vanilla, which is built from Mojang's own downloads.
    val devBundleRepository: String? = null,
)

class UnknownVariantException(val id: String, val available: List<String>) :
    IllegalArgumentException("unknown variant '$id', available: ${available.joinToString()}")

class VariantSpecException(message: String) : IllegalArgumentException(message)

/** The result of parsing a raw variant argument: which variant, and the optional build pin. */
data class VariantSpec(val variant: Variant, val build: String?)

/**
 * Everything a builder needs to know that is not the Mojang version itself. [workspaceId] is the
 * cache key every layer uses (workspace cache, preparer state, derived-cache directory); for
 * vanilla it is just the version id, for variants "<variant>/<build>" so two builds of the same
 * Minecraft version never share a derived cache. [build] is the caller's raw pin (null means
 * "latest", which is what latest-build rotation keys on); [bundle] is what that pin resolved to
 * when [workspaceId] was computed, so the builder uses exactly that build instead of resolving
 * "latest" a second time and possibly landing on a newer one than the id names.
 */
data class WorkspaceRequest(
    val variant: String,
    val build: String?,
    val workspaceId: String,
    val bundle: DevBundle? = null,
)

object Variants {
    const val VANILLA = "vanilla"
    const val PAPER = "paper"
    const val FOLIA = "folia"
    const val PURPUR = "purpur"

    val ALL: List<Variant> = listOf(
        Variant(
            VANILLA,
            "Vanilla",
            "Unmodified Mojang sources: the client jar decompiled with Mojang mappings. Assets, " +
                "reports and game data are always served from this variant.",
        ),
        Variant(
            PAPER,
            "Paper",
            "Paper server sources, from the official mojang-mapped paperclip artifact: vanilla " +
                "code as recompiled with Paper's patches, plus Paper's own classes (CraftBukkit, " +
                "Spigot). No client assets.",
            "https://repo.papermc.io/repository/maven-public/io/papermc/paper/dev-bundle",
        ),
        Variant(
            FOLIA,
            "Folia",
            "Folia server sources: the Paper fork that adds regionised multithreading, from its " +
                "mojang-mapped paperclip artifact. Folia trails Paper, so the newest Minecraft " +
                "versions may not have a build yet. No client assets.",
            "https://repo.papermc.io/repository/maven-public/dev/folia/dev-bundle",
        ),
        Variant(
            PURPUR,
            "Purpur",
            "Purpur server sources: the Paper fork focused on configurable gameplay, from its " +
                "mojang-mapped paperclip artifact - Paper's patches plus Purpur's own. No client " +
                "assets.",
            "https://repo.purpurmc.org/snapshots/org/purpurmc/purpur/dev-bundle",
        ),
    )

    private val byId: Map<String, Variant> = ALL.associateBy { it.id }

    fun byId(id: String): Variant? = byId[id]

    /**
     * Parses a raw variant argument. Null or blank means vanilla. Otherwise the form is "id"
     * (latest build of that variant for the version) or "id/build" pinning one build - the same
     * exact-or-prefix philosophy the version argument has, resolved by the variant's own
     * metadata. "vanilla" takes no build pin: there is only one vanilla.
     */
    fun parse(raw: String?): VariantSpec {
        if (raw.isNullOrBlank()) return VariantSpec(byId.getValue(VANILLA), null)

        val id = raw.trim().substringBefore('/')
        val build = raw.trim().substringAfter('/', "").takeIf { it.isNotEmpty() }
        val variant = byId[id] ?: throw UnknownVariantException(id, ALL.map { it.id })
        if (variant.id == VANILLA && build != null) {
            throw VariantSpecException("variant 'vanilla' takes no build pin")
        }
        return VariantSpec(variant, build)
    }
}
