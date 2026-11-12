package com.auravox.karaoke

import java.text.Normalizer

/**
 * Turning a filename into something a lyric database can match.
 *
 * This is the part that decides the hit rate, more than any amount of
 * retrying. A local file is called "04 - Evidências (Ao Vivo) [Remaster].mp3"
 * and the database holds "Evidências"; folding the accents and stripping the
 * decoration is what closes that gap, and for Portuguese the accent folding
 * is not optional.
 */
object LyricsMatch {

    private val BRACKETS = Regex("""[\(\[\{][^)\]\}]*[\)\]\}]""")
    private val TRAILING = Regex(
        """\s*[-–—]\s*(ao vivo|live|remaster(ed)?|remix|radio edit|single version|acoustic|ac[uú]stico|vers[aã]o)\b.*$""",
        RegexOption.IGNORE_CASE
    )
    private val FEATURING = Regex(
        """\s*(feat\.?|ft\.?|featuring|com participa[cç][aã]o de)\s+.*$""",
        RegexOption.IGNORE_CASE
    )
    private val TRACK_NUMBER = Regex("""^\s*\d{1,3}\s*[-._)]\s*""")
    private val NOISE = Regex("""[^\p{L}\p{N}\s]""")
    private val SPACES = Regex("""\s+""")
    // Only a known audio extension. Cutting at the last dot instead would eat
    // the end of any title that contains one, and "feat. Astrud" contains one
    private val EXTENSION = Regex(
        """\.(mp3|m4a|aac|wav|flac|ogg|opus|wma|mp4|3gp|amr)$""", RegexOption.IGNORE_CASE
    )

    /**
     * Words that carry no identity, so a comparison should not hinge on them.
     *
     * "Chitãozinho e Xororó" and "Chitaozinho & Xororo" are the same duo, and
     * the ampersand folds away to nothing while the "e" stays, which would
     * otherwise cost a fifth of the score for no reason.
     */
    private val CONNECTORS = setOf("e", "and", "y", "et", "the", "a", "o", "de", "da", "do")

    /** Strips decoration a file name carries but a database does not. */
    fun cleanTitle(raw: String): String {
        var s = raw.trim()
        s = EXTENSION.replace(s, "")
        s = TRACK_NUMBER.replace(s, "")
        s = BRACKETS.replace(s, " ")
        s = FEATURING.replace(s, "")
        s = TRAILING.replace(s, "")
        return SPACES.replace(s, " ").trim().ifBlank { raw.trim() }
    }

    fun cleanArtist(raw: String): String {
        var s = raw.trim()
        s = BRACKETS.replace(s, " ")
        s = FEATURING.replace(s, "")
        return SPACES.replace(s, " ").trim()
    }

    /** Lower case, no accents, no punctuation: the form used for comparing. */
    fun fold(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
        val stripped = decomposed.replace(Regex("""\p{Mn}+"""), "")
        return SPACES.replace(NOISE.replace(stripped.lowercase(), " "), " ").trim()
    }

    /** Share of meaningful tokens the two strings have in common, 0..1. */
    fun similarity(a: String, b: String): Float {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) {
            // Both were nothing but connectors: compare them as they came
            return if (fold(a) == fold(b)) 1f else 0f
        }
        val shared = ta.count { it in tb }
        return (2f * shared) / (ta.size + tb.size)
    }

    private fun tokens(raw: String): Set<String> =
        fold(raw).split(' ').filter { it.isNotEmpty() && it !in CONNECTORS }.toSet()

    /**
     * How good a candidate is for what was asked for. Higher wins.
     *
     * Duration carries the most weight because it is the one field that is
     * objective: a search for a studio track will happily return the live
     * version, which has the right title and the wrong length. Two seconds of
     * difference is nothing; thirty is a different recording.
     */
    fun score(
        wantTitle: String, wantArtist: String, wantDurationSec: Double,
        gotTitle: String, gotArtist: String, gotDurationSec: Double,
        hasSynced: Boolean, hasWordSync: Boolean
    ): Float {
        var s = 0f
        s += similarity(wantTitle, gotTitle) * 3.0f
        s += similarity(wantArtist, gotArtist) * 2.0f

        if (wantDurationSec > 0.0 && gotDurationSec > 0.0) {
            val delta = kotlin.math.abs(wantDurationSec - gotDurationSec)
            s += when {
                delta <= 2.0 -> 3.0f
                delta <= 5.0 -> 2.0f
                delta <= 15.0 -> 0.5f
                delta <= 30.0 -> -1.0f
                else -> -3.0f
            }
        }

        // A lyric that cannot follow the song is worth little here
        if (hasWordSync) s += 1.5f
        else if (hasSynced) s += 1.0f
        else s -= 2.0f

        return s
    }
}
