package com.auravox.karaoke

/**
 * Reads LRCLIB's Lyricsfile, the format that carries word timings.
 *
 * It is YAML, but only ever this shape:
 *
 *     version: '1.0'
 *     metadata:
 *       title: Blinding Lights
 *       duration_ms: 200000
 *     lines:
 *     - text: I've been tryna call
 *       start_ms: 27170
 *       end_ms: 28268
 *       words:
 *       - text: 'I''ve '
 *         start_ms: 27209
 *
 * A YAML library for that is a dependency and a method count for nothing, so
 * this reads it directly: two levels of list, a known set of keys, times
 * always in milliseconds because the field names say so. A word carries only
 * a start — the next word closes it and the line's end closes the last one,
 * which is exactly how the view already sweeps.
 *
 * Anything unexpected returns null rather than half a lyric, and the caller
 * falls back to the plain LRC that the same record always carries.
 */
object LyricsFile {

    private class LineDraft {
        var text: String? = null
        var startMs = -1L
        val words = mutableListOf<LyricWord>()
    }

    private class WordDraft {
        var text: String? = null
        var startMs = -1L
    }

    fun parse(content: String): List<LyricLine>? {
        val out = mutableListOf<LyricLine>()
        var line: LineDraft? = null
        var word: WordDraft? = null
        var inLines = false

        fun flushWord() {
            val w = word ?: return
            val t = w.text
            if (t != null && w.startMs >= 0L) line?.words?.add(LyricWord(w.startMs, t))
            word = null
        }

        fun flushLine() {
            flushWord()
            val l = line ?: return
            val t = l.text
            if (t != null && l.startMs >= 0L) {
                out.add(LyricLine(timeMs = l.startMs, text = t.trim(), words = l.words.toList()))
            }
            line = null
        }

        for (raw in content.lineSequence()) {
            if (raw.isBlank()) continue
            val indent = raw.indexOfFirst { it != ' ' }
            if (indent < 0) continue
            var body = raw.substring(indent)

            if (indent == 0) {
                if (body == "lines:") { flushLine(); inLines = true; continue }
                if (!body.startsWith("- ")) { flushLine(); inLines = false; continue }
            }
            if (!inLines) continue

            val isItem = body.startsWith("- ")
            if (isItem) body = body.substring(2)

            if (isItem) {
                if (indent == 0) { flushLine(); line = LineDraft() }
                else { flushWord(); word = WordDraft() }
            }

            val colon = body.indexOf(':')
            if (colon <= 0) continue
            val key = body.substring(0, colon).trim()
            val value = body.substring(colon + 1).trim()
            if (key == "words") continue

            // Depth decides whose key it is: a word's keys sit deeper than the
            // dash that opened it, a line's keys sit at two
            val forWord = word != null && (indent >= 4 || (isItem && indent >= 2))
            if (forWord) {
                val w = word ?: continue
                when (key) {
                    "text" -> w.text = scalar(value) ?: return null
                    "start_ms" -> w.startMs = value.toLongOrNull() ?: return null
                }
            } else {
                val l = line ?: continue
                when (key) {
                    "text" -> l.text = scalar(value) ?: return null
                    "start_ms" -> l.startMs = value.toLongOrNull() ?: return null
                }
            }
        }
        flushLine()

        // Without word timings this format has nothing the LRC does not
        return out.takeIf { it.isNotEmpty() && it.any { l -> l.words.isNotEmpty() } }
    }

    /**
     * One YAML scalar, single or double quoted.
     *
     * A block scalar would need a different reader, so seeing one returns null
     * and the caller uses the LRC rather than guessing.
     */
    private fun scalar(value: String): String? {
        if (value.isEmpty()) return ""
        if (value.startsWith("|") || value.startsWith(">")) return null
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.length - 1)
                .replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return value
    }
}
