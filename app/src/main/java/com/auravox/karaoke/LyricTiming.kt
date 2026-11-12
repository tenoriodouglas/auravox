package com.auravox.karaoke

/**
 * Line and word end times, worked out once.
 *
 * A word ends where the next begins; the last word of a line ends at the next
 * line, capped, so a final syllable with nothing after it does not sweep on
 * forever.
 */
class LyricTiming(private val lines: List<LyricLine>) {

    private val lineEnd = LongArray(lines.size)

    init {
        for (i in lines.indices) {
            val own = lines[i].timeMs
            val next = lines.getOrNull(i + 1)?.timeMs
            lineEnd[i] = if (next != null && next > own) minOf(next, own + kMaxLine)
                         else own + kHold
        }
    }

    /** Last line whose timestamp has passed, or -1 before the song sings. */
    fun lineAt(positionMs: Long): Int {
        var index = -1
        for (i in lines.indices) {
            if (positionMs >= lines[i].timeMs) index = i else break
        }
        return index
    }

    /** How far through line [index] the song is, 0..1. */
    fun sweepOf(index: Int, positionMs: Long): Float {
        val line = lines.getOrNull(index) ?: return 0f
        if (positionMs < line.timeMs) return 0f

        val words = line.words
        if (words.isNotEmpty()) {
            val last = words.size - 1
            var i = last
            for (j in words.indices) {
                if (positionMs < words[j].timeMs) { i = j - 1; break }
                i = j
            }
            if (i < 0) return 0f
            val start = words[i].timeMs
            val end = if (i < last) words[i + 1].timeMs else lineEnd[index]
            val within =
                if (end > start) ((positionMs - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                else 1f
            return ((i + within) / words.size).coerceIn(0f, 1f)
        }

        val span = lineEnd[index] - line.timeMs
        if (span <= 0L) return 1f
        return ((positionMs - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
    }

    private companion object {
        const val kHold = 2600L     // a trailing line with nothing after it
        const val kMaxLine = 9000L  // an instrumental gap is not a long word
    }
}
