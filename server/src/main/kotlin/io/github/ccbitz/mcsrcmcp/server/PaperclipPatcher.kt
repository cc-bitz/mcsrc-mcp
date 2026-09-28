package io.github.ccbitz.mcsrcmcp.server

import io.sigpipe.jbsdiff.Patch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class PaperclipPatchException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Applies paperclip's binary patches: a paperclip jar carries `META-INF/patches.list`, one line
 * per patched file, each a whole-file bsdiff from a Mojang-shipped original to the variant's final
 * artifact. The mojang-mapped paperclip bundles a single entry covering the entire server jar, so
 * the output is the complete mojang-mapped variant jar - exactly what the indexer wants, no
 * remapping step involved.
 *
 * Format of a patches.list line (7 tab-separated fields, from paperclip's PatchEntry):
 * `location \t originalSha256 \t patchSha256 \t outputSha256 \t originalPath \t patchPath \t outputPath`
 */
object PaperclipPatcher {
    data class PatchEntry(
        val location: String,
        val originalHash: ByteArray,
        val patchHash: ByteArray,
        val outputHash: ByteArray,
        val originalPath: String,
        val patchPath: String,
        val outputPath: String,
    )

    fun parsePatchesList(text: String): List<PatchEntry> = buildList {
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split("\t")
            require(parts.size == 7) { "invalid patches.list line: $line" }
            add(
                PatchEntry(
                    parts[0],
                    fromHex(parts[1]),
                    fromHex(parts[2]),
                    fromHex(parts[3]),
                    parts[4],
                    parts[5],
                    parts[6],
                ),
            )
        }
    }

    /**
     * Produces the patched variant jar from the paperclip jar and the original Mojang jar it
     * patches. Every hash paperclip ships is verified: patch bytes against [PatchEntry.patchHash],
     * the original against [PatchEntry.originalHash], and the bsdiff output against
     * [PatchEntry.outputHash] - a mismatch at any stage means a corrupted download, not a
     * decompilation oddity, and fails the build.
     */
    fun apply(paperclipJar: ByteArray, originalJar: ByteArray): ByteArray {
        val entries = parsePatchesList(
            readZipEntry(paperclipJar, "META-INF/patches.list")
                ?.toString(Charsets.UTF_8)
                ?: throw PaperclipPatchException("paperclip jar has no META-INF/patches.list"),
        )
        if (entries.isEmpty()) {
            throw PaperclipPatchException("paperclip jar's patches.list is empty")
        }

        // The mojang-mapped bundles patch exactly one file - the whole jar. Refuse anything else
        // rather than silently producing a partial jar.
        val distinctOriginals = entries.map { it.originalPath }.distinct()
        if (distinctOriginals.size != 1) {
            throw PaperclipPatchException(
                "unsupported paperclip bundle: patches ${distinctOriginals.size} distinct original files (${distinctOriginals.joinToString()})",
            )
        }

        val originalEntry = entries.first()
        if (!MessageDigest.isEqual(sha256(originalJar), originalEntry.originalHash)) {
            throw PaperclipPatchException("original jar hash mismatch for ${originalEntry.originalPath}")
        }

        var output: ByteArray? = null
        for (entry in entries) {
            val patchPath = "META-INF/${entry.location.trimEnd('/')}/${entry.patchPath}"
            val patchBytes = readZipEntry(paperclipJar, patchPath)
                ?: throw PaperclipPatchException("patch file not found in paperclip jar: $patchPath")
            if (!MessageDigest.isEqual(sha256(patchBytes), entry.patchHash)) {
                throw PaperclipPatchException("patch hash mismatch for $patchPath")
            }

            val patched = try {
                ByteArrayOutputStream(originalJar.size).use { out ->
                    Patch.patch(originalJar, patchBytes, out)
                    out.toByteArray()
                }
            } catch (e: Exception) {
                throw PaperclipPatchException("failed to apply patch $patchPath", e)
            }
            if (!MessageDigest.isEqual(sha256(patched), entry.outputHash)) {
                throw PaperclipPatchException("patched output hash mismatch for ${entry.outputPath}")
            }
            output = patched
        }

        return output ?: throw PaperclipPatchException("patches.list produced no output")
    }

    /**
     * Extracts the real server jar nested inside Mojang's bundler-format download. The published
     * server.jar is a wrapper: its `META-INF/versions.list` names the actual server classes jar
     * (tab-separated: sha256, version, path), and paperclip's patches apply to THAT jar - the
     * wrapper's own bytes never match patches.list's originalHash.
     */
    fun extractVanillaServerJar(bundlerJar: ByteArray): ByteArray {
        val versionsList = readZipEntry(bundlerJar, "META-INF/versions.list")?.toString(Charsets.UTF_8)
            ?: throw PaperclipPatchException("server jar has no META-INF/versions.list - not a bundler-format jar")
        val line = versionsList.lineSequence().firstOrNull { it.isNotBlank() }
            ?: throw PaperclipPatchException("META-INF/versions.list is empty")
        val parts = line.split("\t")
        if (parts.size < 3) {
            throw PaperclipPatchException("unexpected META-INF/versions.list line: $line")
        }
        val nestedPath = "META-INF/versions/${parts.last()}"
        val nested = readZipEntry(bundlerJar, nestedPath)
            ?: throw PaperclipPatchException("bundler jar has no $nestedPath entry")
        if (!sha256Hex(nested).equals(parts[0], ignoreCase = true)) {
            throw PaperclipPatchException("nested server jar hash mismatch for $nestedPath")
        }
        return nested
    }

    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).joinToString("") { "%02x".format(it) }

    fun readZipEntry(zipBytes: ByteArray, path: String): ByteArray? {
        // A corrupted zip must surface as a patch/config failure, not a raw ZipException from
        // mid-stream - callers turn PaperclipPatchException into tool errors.
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name == path) return zip.readBytes()
                    entry = zip.nextEntry
                }
            }
            return null
        } catch (e: IOException) {
            throw PaperclipPatchException("failed to read $path from paperclip-style zip", e)
        }
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun fromHex(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex string length must be even: $s" }
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }
}

