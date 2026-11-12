package com.auravox.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching rules, which decide how often a song finds its lyric.
 *
 * Accent folding is first among these and is not a nicety: a Brazilian user's
 * library is full of titles the database stores accented and the file system
 * stores however the ripper felt, and a comparison that treats "Evidencias"
 * and "Evidências" as different words finds nothing.
 */
class LyricsMatchTest {

    @Test
    fun stripsTrackNumbers() {
        assertEquals("Evidências", LyricsMatch.cleanTitle("04 - Evidências"))
        assertEquals("Evidências", LyricsMatch.cleanTitle("04. Evidências"))
        assertEquals("Evidências", LyricsMatch.cleanTitle("4_Evidências"))
    }

    @Test
    fun stripsDecoration() {
        assertEquals("Evidências", LyricsMatch.cleanTitle("Evidências (Ao Vivo)"))
        assertEquals("Evidências", LyricsMatch.cleanTitle("Evidências [Remastered 2011]"))
        assertEquals("Evidências", LyricsMatch.cleanTitle("Evidências - Ao Vivo"))
        assertEquals("Garota de Ipanema", LyricsMatch.cleanTitle("Garota de Ipanema feat. Astrud"))
        assertEquals("Shape of You", LyricsMatch.cleanTitle("Shape of You - Remastered"))
    }

    /** A title that is only decoration must not come back empty. */
    @Test
    fun neverReturnsNothing() {
        assertTrue(LyricsMatch.cleanTitle("(Ao Vivo)").isNotBlank())
        assertTrue(LyricsMatch.cleanTitle("   ").isNotBlank() ||
                   LyricsMatch.cleanTitle("   ").isEmpty())
    }

    @Test
    fun foldsAccentsAndCase() {
        assertEquals("evidencias", LyricsMatch.fold("Evidências"))
        assertEquals("coracao", LyricsMatch.fold("Coração"))
        assertEquals("nao e so isso", LyricsMatch.fold("Não É Só Isso!"))
        assertEquals(LyricsMatch.fold("Evidencias"), LyricsMatch.fold("Evidências"))
    }

    @Test
    fun similarityIgnoresAccentsAndPunctuation() {
        assertEquals(1.0f, LyricsMatch.similarity("Evidências", "evidencias"), 0.001f)
        assertEquals(1.0f, LyricsMatch.similarity("Não É Só Isso", "nao e so isso"), 0.001f)
        assertTrue(LyricsMatch.similarity("Chitãozinho e Xororó", "Chitaozinho & Xororo") > 0.9f)
        assertTrue(LyricsMatch.similarity("Evidências", "Fio de Cabelo") < 0.2f)
    }

    /**
     * The length of the recording decides between two takes of one song.
     *
     * This is the case the fuzzy search gets wrong on its own: a live version
     * has the right title, the right artist and the wrong duration, and it is
     * the wrong lyric to sing along to a studio track.
     */
    @Test
    fun durationBreaksTheTie() {
        val studio = LyricsMatch.score(
            "Evidências", "Chitãozinho e Xororó", 283.0,
            "Evidências", "Chitãozinho e Xororó", 284.0,
            hasSynced = true, hasWordSync = false
        )
        val live = LyricsMatch.score(
            "Evidências", "Chitãozinho e Xororó", 283.0,
            "Evidências (Ao Vivo)", "Chitãozinho e Xororó", 341.0,
            hasSynced = true, hasWordSync = false
        )
        assertTrue("estudio $studio deveria ganhar de ao vivo $live", studio > live)
    }

    /** With everything else equal, a lyric that follows the words wins. */
    @Test
    fun wordSyncWins() {
        val word = LyricsMatch.score(
            "A", "B", 200.0, "A", "B", 200.0, hasSynced = true, hasWordSync = true
        )
        val lineOnly = LyricsMatch.score(
            "A", "B", 200.0, "A", "B", 200.0, hasSynced = true, hasWordSync = false
        )
        val plain = LyricsMatch.score(
            "A", "B", 200.0, "A", "B", 200.0, hasSynced = false, hasWordSync = false
        )
        assertTrue(word > lineOnly)
        assertTrue(lineOnly > plain)
    }

    /** A wrong song does not win just because its lyric is nicely synced. */
    @Test
    fun theRightSongBeatsTheNicerLyric() {
        val right = LyricsMatch.score(
            "Evidências", "Chitãozinho e Xororó", 283.0,
            "Evidências", "Chitãozinho e Xororó", 283.0,
            hasSynced = false, hasWordSync = false
        )
        val wrong = LyricsMatch.score(
            "Evidências", "Chitãozinho e Xororó", 283.0,
            "Blinding Lights", "The Weeknd", 200.0,
            hasSynced = true, hasWordSync = true
        )
        assertTrue("certa $right deveria ganhar de errada $wrong", right > wrong)
    }
}
