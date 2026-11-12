package com.auravox.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sample is a real record from LRCLIB, trimmed.
 *
 * Both the field names and the units were taken from the live service rather
 * than from documentation: the keys end in _ms and the metadata reports a
 * duration of 200000 for a track of three minutes twenty, so they are
 * milliseconds. Guessing between seconds and milliseconds here would have put
 * every lyric in the app out by a factor of a thousand.
 */
class LyricsFileTest {

    private val sample = """
        version: '1.0'
        metadata:
          title: Blinding Lights
          artist: The Weeknd
          instrumental: false
          album: After Hours
          duration_ms: 200000
          language: en
        lines:
        - text: Yeah
          start_ms: 13120
          end_ms: 14551
          words:
          - text: Yeah
            start_ms: 13159
        - text: I've been tryna call
          start_ms: 27170
          end_ms: 28268
          words:
          - text: 'I''ve '
            start_ms: 27209
          - text: 'been '
            start_ms: 27598
          - text: 'tryna '
            start_ms: 27889
          - text: call
            start_ms: 28200
    """.trimIndent()

    @Test
    fun readsLinesAndWords() {
        val lines = LyricsFile.parse(sample)
        assertTrue("nao leu nada", lines != null)
        requireNotNull(lines)
        assertEquals(2, lines.size)

        assertEquals("Yeah", lines[0].text)
        assertEquals(13120L, lines[0].timeMs)
        assertEquals(1, lines[0].words.size)
        assertEquals(13159L, lines[0].words[0].timeMs)

        assertEquals("I've been tryna call", lines[1].text)
        assertEquals(27170L, lines[1].timeMs)
        assertEquals(4, lines[1].words.size)
    }

    /** The quoted form escapes an apostrophe by doubling it. */
    @Test
    fun unescapesQuotedText() {
        val lines = requireNotNull(LyricsFile.parse(sample))
        assertEquals("I've ", lines[1].words[0].text)
        assertEquals("been ", lines[1].words[1].text)
        assertEquals("call", lines[1].words[3].text)
    }

    /** Words keep their trailing space, so joining them rebuilds the line. */
    @Test
    fun wordsRebuildTheLine() {
        val lines = requireNotNull(LyricsFile.parse(sample))
        assertEquals("I've been tryna call", lines[1].words.joinToString("") { it.text })
    }

    /** The sweep needs each word to start after the one before it. */
    @Test
    fun wordTimesAscend() {
        val lines = requireNotNull(LyricsFile.parse(sample))
        for (line in lines) {
            var previous = -1L
            for (w in line.words) {
                assertTrue("palavra em ${w.timeMs} vem antes de $previous", w.timeMs > previous)
                previous = w.timeMs
            }
        }
    }

    /** A record with no word timings has nothing the LRC does not already give. */
    @Test
    fun rejectsLineOnlyDocument() {
        val lineOnly = """
            version: '1.0'
            lines:
            - text: Is this the real life?
              start_ms: 150
              end_ms: 7130
        """.trimIndent()
        assertNull(LyricsFile.parse(lineOnly))
    }

    @Test
    fun rejectsRubbish() {
        assertNull(LyricsFile.parse(""))
        assertNull(LyricsFile.parse("isto nao e um lyricsfile"))
        assertNull(LyricsFile.parse("{\"json\": true}"))
    }

    /**
     * The whole thing, against a document taken from the service unchanged.
     *
     * A trimmed sample proves the shape; only the real file proves it survives
     * 35 lines of quoting, punctuation and repeated refrains.
     */
    @Test
    fun readsARealRecord() {
        val yaml = javaClass.classLoader
            ?.getResourceAsStream("lyricsfile_wordsync.yaml")
            ?.bufferedReader()?.readText()
        requireNotNull(yaml) { "recurso de teste nao encontrado" }

        val lines = LyricsFile.parse(yaml)
        requireNotNull(lines) { "nao conseguiu ler o documento real" }
        assertEquals("linhas lidas", 35, lines.size)

        // Every line has words, every word has a time, and the whole thing
        // runs forward
        var previous = -1L
        for (line in lines) {
            assertTrue("linha sem palavras: ${line.text}", line.words.isNotEmpty())
            assertTrue("linha sem texto", line.text.isNotBlank())
            assertTrue("linha fora de ordem em ${line.timeMs}", line.timeMs > previous)
            previous = line.timeMs
            var w = line.timeMs - 200L  // a word may start a hair before its line
            for (word in line.words) {
                assertTrue("palavra fora de ordem: '${word.text}' em ${word.timeMs}",
                           word.timeMs >= w)
                assertTrue("palavra vazia na linha '${line.text}'", word.text.isNotEmpty())
                w = word.timeMs
            }
            assertEquals("palavras nao reconstroem a linha",
                         line.text, line.words.joinToString("") { it.text }.trim())
        }

        // Milliseconds, not seconds: the track is three minutes twenty
        assertTrue("o ultimo tempo foi ${previous}", previous in 60_000L..400_000L)
    }

    /**
     * Out of order in the file, in order out of the parser.
     *
     * The view finds the current line by walking forward and stopping at the
     * first time that has not arrived, which is right for an ordered list and
     * wrong for any other. One line out of order in a community database froze
     * the highlight there for the rest of the song.
     */
    @Test
    fun sortsLinesByTime() {
        val shuffled = """
            version: '1.0'
            lines:
            - text: terceira
              start_ms: 3000
              words:
              - text: terceira
                start_ms: 3000
            - text: primeira
              start_ms: 1000
              words:
              - text: primeira
                start_ms: 1000
            - text: segunda
              start_ms: 2000
              words:
              - text: segunda
                start_ms: 2000
        """.trimIndent()
        val lines = requireNotNull(LyricsFile.parse(shuffled))
        assertEquals(listOf("primeira", "segunda", "terceira"), lines.map { it.text })
        assertEquals(listOf(1000L, 2000L, 3000L), lines.map { it.timeMs })
    }

    /**
     * A line with no time at all makes the whole document unusable.
     *
     * Dropping it quietly handed back a lyric with a hole in it, still
     * presented as word-synced, and the highlight skipped a line. The record
     * always carries a whole LRC as well, so declining is the better answer.
     */
    @Test
    fun declinesWhenALineHasNoTime() {
        val holed = """
            version: '1.0'
            lines:
            - text: com tempo
              start_ms: 1000
              words:
              - text: com
                start_ms: 1000
            - text: sem tempo nenhum
              words:
              - text: sem
                start_ms: 2000
            - text: com tempo de novo
              start_ms: 3000
              words:
              - text: com
                start_ms: 3000
        """.trimIndent()
        assertNull(LyricsFile.parse(holed))
    }

    /** A block scalar is a different reader, so it declines instead of guessing. */
    @Test
    fun declinesBlockScalar() {
        val block = """
            lines:
            - text: |
                duas linhas
                no mesmo campo
              start_ms: 100
              words:
              - text: duas
                start_ms: 100
        """.trimIndent()
        assertNull(LyricsFile.parse(block))
    }
}
