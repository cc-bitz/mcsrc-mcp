package io.github.ccbitz.mcsrcmcp.server

import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class SearchMatch(
    val path: String,
    val line: Int,
    val text: String,
    // Total line count of the class/asset this match is in - free (the index already knows each
    // entry's length), and tells the caller how big a `get_class_source`/`get_asset` read of the
    // whole thing would be before committing to it.
    val nLines: Int,
)

enum class SearchEntryKind(internal val marker: String) {
    SOURCE("S"),
    ASSET("A"),
}

// This index used to be a @Serializable data class holding every entry's lines as a List<String>,
// persisted with Json.encodeToString and loaded back whole. That put the ENTIRE decompiled source
// of a version in the heap - measured at ~2.1M live Strings / ~360MB for 1.21.x, per warm version,
// held for the process's lifetime whether or not anyone ever called search_code. Worse, the load
// path (Json.decodeFromString(Files.readString(f))) peaked at the file as one UTF-16 String plus
// the parsed graph on top.
//
// Search here is a linear scan for a substring or regex over independent lines: it never needs two
// lines resident at once. So the index is now a flat file scanned line-by-line, and SearchIndex is
// just a handle to it. Memory is O(matches), not O(codebase).
//
// Format (UTF-8, '\n'-separated). Entries are length-framed rather than delimiter-framed, so no
// escaping is needed and no source line can be mistaken for a header:
//
//     mcsrc-search-index<TAB>2<TAB><versionId>
//     S<TAB><nLines><TAB><path>
//     <line 1>
//     ...  (exactly nLines lines)
//     A<TAB><nLines><TAB><path>
//     ...
//
// A sidecar .toc file (one "kind<TAB>nLines<TAB>byteOffset<TAB>path" per entry) keeps a scoped
// search - search_code with `classes`, the cheap path the get_instructions guide pushes models toward -
// genuinely cheap: it seeks straight to each wanted entry instead of scanning the whole file.
private const val INDEX_MAGIC = "mcsrc-search-index"
private const val INDEX_FORMAT_VERSION = "2"

/**
 * One scan's outcome. [exhaustive] means the scan reached the end of everything it was asked to
 * look at rather than stopping early at the limit - which is what makes [totalMatches] exact, and
 * exact for free: a search that never fills its limit has already counted every hit there is.
 */
data class SearchScan(
    val matches: List<SearchMatch>,
    val totalMatches: Int,
    val exhaustive: Boolean,
) {
    /** "12" when the count is exact, "40+" when the scan stopped early. */
    fun countLabel(): String = if (exhaustive) totalMatches.toString() else "$totalMatches+"
}

/**
 * Handle to one version's on-disk search index. Holds no entry content: every [search] streams the
 * file and retains only the matches it returns.
 */
class SearchIndex internal constructor(
    val versionId: String,
    internal val entriesFile: Path,
    internal val tocFile: Path,
) {
    fun search(
        query: String,
        useRegex: Boolean,
        limit: Int,
        includeSource: Boolean,
        includeAssets: Boolean,
        // Scopes the search to these paths (dotted class names, or asset paths) when given, instead
        // of every entry - lets a caller who already knows where to look (e.g. from find_references)
        // avoid paying for a full-codebase search. Null/empty means unscoped, same as before.
        paths: Set<String>? = null,
        // Drops hits in the same pass - the one-call stand-in for `| rg -v`, so narrowing an
        // over-broad query costs no extra I/O and no extra round trip.
        //
        // ALWAYS a regex, even when the query is a literal. Tying it to [useRegex] made the
        // overwhelmingly common form - an alternation like "Gl|Vk" - silently match nothing on a
        // literal query, and a filter that quietly does nothing is worse than one that errors.
        exclude: String? = null,
        // Keeps scanning past [limit] to count every remaining hit. Off by default because a scan
        // that stops at the limit is the whole reason search is cheap - and a query that never
        // fills its limit is counted exactly anyway, for free.
        countAll: Boolean = false,
    ): SearchScan {
        val kinds = buildSet {
            if (includeSource) add(SearchEntryKind.SOURCE)
            if (includeAssets) add(SearchEntryKind.ASSET)
        }
        if (kinds.isEmpty() || limit <= 0) return SearchScan(emptyList(), 0, exhaustive = true)

        val scan = ScanState(
            matcher = LineMatcher.of(query, useRegex),
            exclude = exclude?.takeIf { it.isNotEmpty() }?.let { LineMatcher.of(it, useRegex = true) },
            limit = limit,
            countAll = countAll,
        )
        if (paths.isNullOrEmpty()) scanWholeFile(kinds, scan) else scanScoped(kinds, paths, scan)
        return scan.toResult()
    }

    private fun scanWholeFile(kinds: Set<SearchEntryKind>, scan: ScanState) {
        openEntries(skipBytes = 0L).use { reader ->
            reader.readLine() ?: return
            while (true) {
                val header = EntryHeader.parse(reader.readLine() ?: return)
                if (header.kind in kinds) {
                    if (scanEntry(reader, header, scan)) return
                } else {
                    repeat(header.nLines) { reader.readLine() ?: return }
                }
            }
        }
    }

    // Sorted by offset so the seeks run forward through the file rather than bouncing around it.
    private fun scanScoped(kinds: Set<SearchEntryKind>, paths: Set<String>, scan: ScanState) {
        val wanted = readToc().filter { it.kind in kinds && it.path in paths }.sortedBy { it.offset }
        for (entry in wanted) {
            val stop = openEntries(skipBytes = entry.offset).use { reader ->
                val header = EntryHeader.parse(reader.readLine() ?: return)
                scanEntry(reader, header, scan)
            }
            if (stop) return
        }
    }

    /** Returns true when the scan should stop entirely. */
    private fun scanEntry(reader: BufferedReader, header: EntryHeader, scan: ScanState): Boolean {
        // An exclusion that matches the path kills every hit in this entry, so skip its lines
        // outright rather than testing each one.
        if (scan.excludesPath(header.path)) {
            repeat(header.nLines) { reader.readLine() ?: return scan.stopShort() }
            return false
        }
        for (i in 0 until header.nLines) {
            // A truncated entry means the file was cut short; treat it as the end of the data
            // rather than a completed scan, so the count isn't reported as exact.
            val line = reader.readLine() ?: return scan.stopShort()
            if (scan.record(header.path, i + 1, line, header.nLines)) return true
        }
        return false
    }

    private class ScanState(
        private val matcher: LineMatcher,
        private val exclude: LineMatcher?,
        private val limit: Int,
        private val countAll: Boolean,
    ) {
        private val matches = mutableListOf<SearchMatch>()
        private var total = 0
        private var stoppedEarly = false

        // Mirrors `rg <query> | rg -v <exclude>`, where the filtered text is the rendered
        // path:line:text - so excluding "Gl" drops hits inside GlBackend as well as lines that
        // mention it. Filtering only the line text would leave exactly the class-level noise a
        // caller reaches for this to get rid of.
        fun excludesPath(path: String): Boolean = exclude?.matches(path) == true

        /** Returns true when the caller should stop scanning. */
        fun record(path: String, line: Int, text: String, nLines: Int): Boolean {
            if (!matcher.matches(text)) return false
            if (exclude?.matches(text) == true) return false

            total++
            if (matches.size < limit) {
                matches.add(SearchMatch(path, line, text, nLines))
            }
            if (matches.size >= limit && !countAll) {
                stoppedEarly = true
                return true
            }
            return false
        }

        fun stopShort(): Boolean {
            stoppedEarly = true
            return true
        }

        fun toResult() = SearchScan(matches, total, exhaustive = !stoppedEarly)
    }

    private fun readToc(): List<TocEntry> =
        Files.newBufferedReader(tocFile, StandardCharsets.UTF_8).use { reader ->
            reader.lineSequence().mapNotNull { TocEntry.parseOrNull(it) }.toList()
        }

    private fun openEntries(skipBytes: Long): BufferedReader {
        val stream = Files.newInputStream(entriesFile)
        return try {
            if (skipBytes > 0) stream.skipNBytes(skipBytes)
            stream.bufferedUtf8Reader()
        } catch (e: Throwable) {
            stream.close()
            throw e
        }
    }

    private data class EntryHeader(val kind: SearchEntryKind, val nLines: Int, val path: String) {
        companion object {
            fun parse(line: String): EntryHeader {
                val parts = line.split('\t', limit = 3)
                // The file is written only by SearchIndexWriter, and finish() publishes it by
                // atomic move - a header that doesn't parse means the file was corrupted or
                // hand-edited underneath us, which is not something a search can sensibly limp on.
                require(parts.size == 3) { "malformed search index entry header: $line" }
                val kind = SearchEntryKind.entries.first { it.marker == parts[0] }
                return EntryHeader(kind, parts[1].toInt(), parts[2])
            }
        }
    }

    private data class TocEntry(val kind: SearchEntryKind, val nLines: Int, val offset: Long, val path: String) {
        companion object {
            // Unlike an entry header, a half-written trailing toc line is survivable: the worst
            // case is one entry missing from a scoped search, so skip it rather than failing.
            fun parseOrNull(line: String): TocEntry? {
                val parts = line.split('\t', limit = 4)
                if (parts.size != 4) return null
                val kind = SearchEntryKind.entries.firstOrNull { it.marker == parts[0] } ?: return null
                val nLines = parts[1].toIntOrNull() ?: return null
                val offset = parts[2].toLongOrNull() ?: return null
                return TocEntry(kind, nLines, offset, parts[3])
            }
        }
    }
}

// A literal query used to be run as Regex(Regex.escape(query)) - a full regex engine to answer
// "does this line contain this string", on every line of every class. String.contains does the
// same job without the engine, and this runs a few million times per unscoped search.
private sealed interface LineMatcher {
    fun matches(line: String): Boolean

    class Literal(private val needle: String) : LineMatcher {
        override fun matches(line: String) = line.contains(needle, ignoreCase = true)
    }

    class Pattern(private val regex: Regex) : LineMatcher {
        override fun matches(line: String) = regex.containsMatchIn(line)
    }

    companion object {
        fun of(query: String, useRegex: Boolean): LineMatcher =
            if (useRegex) Pattern(query.toRegex(RegexOption.IGNORE_CASE)) else Literal(query)
    }
}

/**
 * Streams entries into a new on-disk index. Writes to temp files and publishes them by atomic move
 * in [finish], so a crashed or cancelled build never leaves a half-written index that [load] would
 * accept. Not thread-safe: one build at a time writes one index.
 */
class SearchIndexWriter internal constructor(
    private val cacheDir: Path,
    private val versionId: String,
) : Closeable {
    private val entriesTmp = Files.createTempFile(cacheDir, "search-entries-", ".tmp")
    private val tocTmp = Files.createTempFile(cacheDir, "search-toc-", ".tmp")
    private val entriesOut = CountingOutputStream(Files.newOutputStream(entriesTmp).buffered())
    private val tocOut = Files.newOutputStream(tocTmp).buffered()
    private var published = false

    init {
        entriesOut.writeLine("$INDEX_MAGIC\t$INDEX_FORMAT_VERSION\t$versionId")
    }

    fun add(kind: SearchEntryKind, path: String, text: String) {
        // lines() materializes one entry's lines briefly; they're written straight out and dropped,
        // so peak stays at a single class's source rather than the whole codebase.
        val lines = text.lines()
        tocOut.writeLine("${kind.marker}\t${lines.size}\t${entriesOut.count}\t$path")
        entriesOut.writeLine("${kind.marker}\t${lines.size}\t$path")
        for (line in lines) {
            entriesOut.writeLine(line)
        }
    }

    fun finish(): SearchIndex {
        entriesOut.close()
        tocOut.close()
        published = true

        val entriesFile = cacheDir.resolve(ENTRIES_FILE)
        val tocFile = cacheDir.resolve(TOC_FILE)
        Files.move(tocTmp, tocFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // Entries last: load() gates on this file, so it must not become visible before its toc.
        Files.move(entriesTmp, entriesFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

        // The v1 whole-file JSON index this format replaces is dead weight on disk (it was the
        // largest file in the cache), and nothing reads it any more.
        Files.deleteIfExists(cacheDir.resolve(LEGACY_JSON_FILE))

        return SearchIndex(versionId, entriesFile, tocFile)
    }

    override fun close() {
        if (published) return
        runCatching { entriesOut.close() }
        runCatching { tocOut.close() }
        Files.deleteIfExists(entriesTmp)
        Files.deleteIfExists(tocTmp)
    }

    private companion object {
        const val ENTRIES_FILE = "search-index-v2.txt"
        const val TOC_FILE = "search-index-v2.toc"
        const val LEGACY_JSON_FILE = "search-index.json"
    }
}

object SearchIndexStore {
    fun writer(cacheDir: Path, versionId: String): SearchIndexWriter {
        Files.createDirectories(cacheDir)
        return SearchIndexWriter(cacheDir, versionId)
    }

    fun load(cacheDir: Path): SearchIndex? {
        val entriesFile = cacheDir.resolve("search-index-v2.txt")
        val tocFile = cacheDir.resolve("search-index-v2.toc")
        if (!Files.exists(entriesFile) || !Files.exists(tocFile)) return null

        return try {
            val header = Files.newBufferedReader(entriesFile, StandardCharsets.UTF_8).use { it.readLine() }
            val parts = header?.split('\t', limit = 3) ?: return null
            if (parts.size != 3 || parts[0] != INDEX_MAGIC || parts[1] != INDEX_FORMAT_VERSION) return null
            SearchIndex(parts[2], entriesFile, tocFile)
        } catch (e: Exception) {
            null // unreadable or truncated - treat as a miss, the caller rebuilds
        }
    }
}

// Tracks the byte offset each entry starts at, for the toc. Encoding line-by-line ourselves (rather
// than going through a Writer) keeps that count exact with no flush-to-measure dance.
private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }

    override fun flush() = out.flush()

    override fun close() = out.close()
}

private fun OutputStream.writeLine(line: String) {
    write(line.toByteArray(StandardCharsets.UTF_8))
    write('\n'.code)
}

private fun InputStream.bufferedUtf8Reader(): BufferedReader = bufferedReader(StandardCharsets.UTF_8)
