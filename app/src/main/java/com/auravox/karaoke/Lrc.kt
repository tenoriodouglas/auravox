package com.auravox.karaoke

/** One word with its own timestamp, from an enhanced LRC file. */
data class LyricWord(val timeMs: Long, val text: String)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
) {
    /** Index of the word being sung at [positionMs], or -1 before the first. */
    fun wordAt(positionMs: Long): Int {
        if (words.isEmpty()) return -1
        var index = -1
        for (i in words.indices) {
            if (positionMs >= words[i].timeMs) index = i else break
        }
        return index
    }
}

/**
 * LRC parser, plain and enhanced.
 *
 * Plain files time a whole line; enhanced files carry a timestamp per word in
 * angle brackets. Word timings are what let the highlight crawl across the
 * line instead of jumping, which is the difference between following the lyric
 * and reading it.
 */
object Lrc {

    private val LINE_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META_TAG = Regex("""\[(ti|ar|al|by|offset):([^]]*)]""", RegexOption.IGNORE_CASE)

    data class Parsed(
        val lines: List<LyricLine>,
        val title: String? = null,
        val artist: String? = null
    )

    fun parse(content: String): Parsed {
        val lines = mutableListOf<LyricLine>()
        var title: String? = null
        var artist: String? = null
        var offsetMs = 0L

        for (raw in content.lineSequence()) {
            val meta = META_TAG.find(raw)
            if (meta != null && LINE_TAG.find(raw) == null) {
                val value = meta.groupValues[2].trim()
                when (meta.groupValues[1].lowercase()) {
                    "ti" -> title = value
                    "ar" -> artist = value
                    "offset" -> offsetMs = value.toLongOrNull() ?: 0L
                }
                continue
            }

            val stamps = LINE_TAG.findAll(raw).toList()
            if (stamps.isEmpty()) continue

            val body = raw.substring(stamps.last().range.last + 1)
            val words = parseWords(body)
            val text = WORD_TAG.replace(body, "").trim()
            if (text.isEmpty() && words.isEmpty()) continue

            // A line can carry several timestamps when a refrain repeats, so
            // word times are stored relative to the line and re-based per copy
            val base = words.firstOrNull()?.timeMs ?: 0L
            for (stamp in stamps) {
                val start = toMillis(stamp.groupValues) - offsetMs
                lines += LyricLine(
                    timeMs = start,
                    text = text,
                    words = words.map { LyricWord(it.timeMs - base + start, it.text) }
                )
            }
        }

        return Parsed(lines.sortedBy { it.timeMs }, title, artist)
    }

    private fun parseWords(body: String): List<LyricWord> {
        val matches = WORD_TAG.findAll(body).toList()
        if (matches.isEmpty()) return emptyList()

        val words = mutableListOf<LyricWord>()
        for ((i, m) in matches.withIndex()) {
            val from = m.range.last + 1
            val to = if (i + 1 < matches.size) matches[i + 1].range.first else body.length
            val text = body.substring(from, to).trim()
            if (text.isNotEmpty()) words += LyricWord(toMillis(m.groupValues), text)
        }
        return words
    }

    /** Hundredths and thousandths both appear in the wild; pad to milliseconds. */
    private fun toMillis(groups: List<String>): Long {
        val minutes = groups[1].toLongOrNull() ?: 0L
        val seconds = groups[2].toLongOrNull() ?: 0L
        val fraction = groups.getOrNull(3).orEmpty()
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> (fraction.toLongOrNull() ?: 0L) * 100
            2 -> (fraction.toLongOrNull() ?: 0L) * 10
            else -> fraction.take(3).toLongOrNull() ?: 0L
        }
        return minutes * 60_000 + seconds * 1_000 + millis
    }

    fun serialize(lines: List<LyricLine>): String = buildString {
        for (line in lines) {
            val m = line.timeMs / 60_000
            val s = (line.timeMs % 60_000) / 1000
            val cs = (line.timeMs % 1000) / 10
            append(String.format("[%02d:%02d.%02d]", m, s, cs))
            if (line.words.isEmpty()) {
                append(line.text)
            } else {
                for (w in line.words) {
                    val wm = w.timeMs / 60_000
                    val ws = (w.timeMs % 60_000) / 1000
                    val wcs = (w.timeMs % 1000) / 10
                    append(String.format("<%02d:%02d.%02d>%s ", wm, ws, wcs, w.text))
                }
            }
            append('\n')
        }
    }
}
