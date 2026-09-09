package io.github.ccbitz.mcsrcmcp.server

import java.nio.file.Files
import java.nio.file.Path

/**
 * Copies one jar entry out to [destination] and returns the number of bytes written. Binary assets
 * are the point - everything get_asset can read as text an agent could write itself, while a
 * texture or sound can only leave this server as bytes.
 */
fun extractToolLogic(
    assets: Map<String, AssetInfo>,
    // Takes a reader rather than the jar: same on-demand pattern as getAssetToolLogic's readText -
    // the client jar stays in the blob store and is opened only for the entry actually asked for.
    readBytes: (String) -> ByteArray?,
    path: String,
    destination: Path,
): Int {
    // The assets map catalogs every non-class entry, so a class file or a made-up path lands here:
    // same message either way, and the tools that address classes by dotted name are the ones to
    // reach for instead.
    assets[path] ?: throw AssetNotFoundException(path)

    // The entry is in the jar (it was catalogued from that same jar), so a null here means the jar
    // changed underneath the cached listing.
    val bytes = readBytes(path) ?: throw AssetNotFoundException(path)

    destination.parent?.let { Files.createDirectories(it) }
    Files.write(destination, bytes)
    return bytes.size
}
