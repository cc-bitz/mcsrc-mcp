package io.github.ccbitz.mcsrcmcp.server

class PatchFailedException(message: String) : IllegalStateException(message)

/**
 * A patched text, plus where each of its lines came from: `originFor[n - 1]` is the 1-based line of
 * the original that output line `n` was copied from unchanged, or 0 for a line the patch added. The
 * mapping is what lets tokens recorded against the original text follow their line into the patched
 * one.
 */
class Patched(val text: String, val originFor: IntArray)

/**
 * Applies a unified diff the way paperweight applies Paper's source patches - DiffPatch in EXACT mode:
 * each hunk must match at the line it names, every context and removed line exactly. Anything else
 * means the text isn't what the patch was written against, which [PatchFailedException] reports
 * rather than guessing at a fuzzy match.
 *
 * Accepts paperweight's `+_,n` new-range headers (its patches leave the new start unwritten).
 */
object SourcePatch {

    private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(?:_|\d+)(?:,\d+)? @@""")

    fun apply(original: String, patchText: String): Patched {
        val endsWithNewline = original.endsWith('\n')
        val lines = original.removeSuffix("\n").split('\n').let { if (original.isEmpty()) emptyList() else it }

        val out = ArrayList<String>(lines.size + 64)
        val origin = ArrayList<Int>(lines.size + 64)
        var pos = 0

        for (hunk in parseHunks(patchText)) {
            // "-a,0" inserts after line a; any other range starts at line a itself.
            val start = if (hunk.oldLength == 0) hunk.oldStart else hunk.oldStart - 1
            if (start < pos || start > lines.size) {
                throw PatchFailedException("hunk at line ${hunk.oldStart} is out of order or past the end (${lines.size} lines)")
            }
            while (pos < start) {
                out.add(lines[pos])
                origin.add(pos + 1)
                pos++
            }
            for (bodyLine in hunk.body) {
                val text = bodyLine.substring(1)
                when (bodyLine[0]) {
                    '+' -> {
                        out.add(text)
                        origin.add(0)
                    }

                    ' ', '-' -> {
                        if (pos >= lines.size || lines[pos] != text) {
                            throw PatchFailedException("hunk at line ${hunk.oldStart} doesn't match line ${pos + 1}")
                        }
                        if (bodyLine[0] == ' ') {
                            out.add(text)
                            origin.add(pos + 1)
                        }
                        pos++
                    }

                    else -> throw PatchFailedException("unexpected patch line: $bodyLine")
                }
            }
        }
        while (pos < lines.size) {
            out.add(lines[pos])
            origin.add(pos + 1)
            pos++
        }

        val text = out.joinToString("\n") + if (endsWithNewline && out.isNotEmpty()) "\n" else ""
        return Patched(text, origin.toIntArray())
    }

    private class Hunk(val oldStart: Int, val oldLength: Int, val body: List<String>)

    private fun parseHunks(patchText: String): List<Hunk> {
        val hunks = mutableListOf<Hunk>()
        var header: MatchResult? = null
        var body = mutableListOf<String>()

        fun flush() {
            val h = header ?: return
            hunks.add(Hunk(h.groupValues[1].toInt(), h.groupValues[2].ifEmpty { "1" }.toInt(), body))
        }

        for (raw in patchText.split('\n')) {
            val line = raw.removeSuffix("\r")
            val match = HUNK_HEADER.find(line)
            when {
                match != null -> {
                    flush()
                    header = match
                    body = mutableListOf()
                }

                header == null -> continue
                line.startsWith("\\") -> continue
                // A context line whose single space was stripped somewhere along the way.
                line.isEmpty() -> body.add(" ")
                else -> body.add(line)
            }
        }
        flush()
        // The split leaves a trailing empty string after the last newline; it was never a line.
        hunks.lastOrNull()?.let { last ->
            if (patchText.endsWith('\n') && last.body.lastOrNull() == " ") {
                hunks[hunks.lastIndex] = Hunk(last.oldStart, last.oldLength, last.body.dropLast(1))
            }
        }
        return hunks
    }

}
