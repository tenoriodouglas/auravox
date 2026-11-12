package com.auravox.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the highlight is at a given moment.
 *
 * Index arithmetic over two nested lists, read sixty times a second, driving
 * the one thing on the karaoke screen a singer is actually looking at. An
 * off-by-one here is a highlight one word ahead of the voice for a whole song.
 */
class LyricTimingTest {

    private fun line(at: Long, text: String, vararg words: Pair<Long, String>) =
        LyricLine(at, text, words.map { LyricWord(it.first, it.second) })

    private val lyric = listOf(
        line(1000, "primeira linha", 1000L to "primeira ", 1500L to "linha"),
        line(2000, "segunda linha", 2000L to "segunda ", 2600L to "linha"),
        line(4000, "ultima")
    )
    private val timing = LyricTiming(lyric)

    @Test
    fun findsTheLineBeingSung() {
        assertEquals("antes de comecar", -1, timing.lineAt(0))
        assertEquals(-1, timing.lineAt(999))
        assertEquals(0, timing.lineAt(1000))
        assertEquals(0, timing.lineAt(1999))
        assertEquals(1, timing.lineAt(2000))
        assertEquals(1, timing.lineAt(3999))
        assertEquals(2, timing.lineAt(4000))
        assertEquals("depois do fim fica na ultima", 2, timing.lineAt(900_000))
    }

    /** Before the line starts there is nothing lit. */
    @Test
    fun sweepStartsAtZero() {
        assertEquals(0f, timing.sweepOf(0, 0), 1e-4f)
        assertEquals(0f, timing.sweepOf(0, 999), 1e-4f)
        assertEquals(0f, timing.sweepOf(1, 1999), 1e-4f)
    }

    /**
     * The sweep follows the word timings, not the length of the words.
     *
     * With two words, reaching the second one means the sweep is at half: a
     * held syllable crawls and a quick one snaps, which is the whole reason
     * for carrying word timings at all.
     */
    @Test
    fun sweepFollowsTheWords() {
        assertEquals("inicio da primeira palavra", 0.0f, timing.sweepOf(0, 1000), 0.01f)
        assertEquals("meio da primeira palavra", 0.25f, timing.sweepOf(0, 1250), 0.01f)
        assertEquals("inicio da segunda palavra", 0.5f, timing.sweepOf(0, 1500), 0.01f)
        assertEquals("meio da segunda palavra", 0.75f, timing.sweepOf(0, 1750), 0.01f)
    }

    /** It never goes backwards and never exceeds the line. */
    @Test
    fun sweepIsMonotonicAndBounded() {
        for (index in lyric.indices) {
            var previous = -1f
            var t = 0L
            while (t < 8000L) {
                val p = timing.sweepOf(index, t)
                assertTrue("linha $index em $t deu $p", p in 0f..1f)
                assertTrue("linha $index andou para tras em $t", p >= previous - 1e-6f)
                previous = p
                t += 25L
            }
        }
    }

    /** A line with no word timings sweeps evenly across its own duration. */
    @Test
    fun aLineWithoutWordsStillSweeps() {
        val plain = LyricTiming(listOf(line(0, "uma"), line(1000, "outra")))
        assertEquals(0.0f, plain.sweepOf(0, 0), 0.01f)
        assertEquals(0.5f, plain.sweepOf(0, 500), 0.01f)
        assertEquals(1.0f, plain.sweepOf(0, 1000), 0.01f)
    }

    /**
     * An instrumental gap is not a very long word.
     *
     * Two lines a minute apart must not leave the first one creeping for the
     * whole minute, which is what an uncapped line duration would do.
     */
    @Test
    fun aLongGapDoesNotStretchTheLine() {
        val sparse = LyricTiming(listOf(line(0, "uma"), line(60_000, "outra")))
        assertEquals("ja cheia bem antes do intervalo acabar",
                     1.0f, sparse.sweepOf(0, 12_000), 0.01f)
    }

    /** The last line of a file has nothing after it and must still finish. */
    @Test
    fun theLastLineFinishes() {
        assertEquals(1.0f, timing.sweepOf(2, 4000 + 3000), 0.01f)
    }

    @Test
    fun outOfRangeIsHarmless() {
        assertEquals(0f, timing.sweepOf(-1, 5000), 1e-4f)
        assertEquals(0f, timing.sweepOf(99, 5000), 1e-4f)
        assertEquals(-1, LyricTiming(emptyList()).lineAt(5000))
    }
}
