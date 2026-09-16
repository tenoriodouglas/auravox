package com.auravox.karaoke

import com.auravox.audio.noteName

/**
 * A song the user imported, plus everything the app worked out about it.
 *
 * `notes` holds the reference melody as triples of (startMs, durationMs,
 * midi) — the same layout the native scorer takes, so it crosses the JNI
 * boundary without a conversion.
 */
class Song(
    val id: String,
    val title: String,
    val artist: String,
    val uri: String,
    val durationMs: Long,
    val keyRoot: Int = 0,
    val keyMode: Int = 0,
    val keyConfidence: Float = 0f,
    val bpm: Float = 0f,
    val notes: FloatArray = FloatArray(0),
    val lyrics: List<LyricLine> = emptyList(),
    val addedAt: Long = System.currentTimeMillis(),
    val bestScore: Float = 0f,
    val timesPlayed: Int = 0,
    val analyzed: Boolean = false
) {
    val noteCount: Int get() = notes.size / 3
    val hasLyrics: Boolean get() = lyrics.isNotEmpty()
    val hasMelody: Boolean get() = noteCount > 0

    /** Display name of the detected key, e.g. "G menor". */
    val keyLabel: String
        get() = if (!analyzed) "--" else "${noteName(keyRoot)} ${if (keyMode == 1) "menor" else "maior"}"

    /** Lowest and highest note of the melody, for sizing the pitch lane. */
    fun midiRange(): IntRange {
        if (noteCount == 0) return 55..79
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (i in 0 until noteCount) {
            val m = notes[i * 3 + 2].toInt()
            if (m < lo) lo = m
            if (m > hi) hi = m
        }
        // Keep at least two octaves on screen so a lane never looks like a
        // single flat line on a song with a narrow melody
        if (hi - lo < 18) {
            val pad = (18 - (hi - lo)) / 2
            lo -= pad
            hi += pad
        }
        return (lo - 3)..(hi + 3)
    }

    fun copyWith(
        title: String = this.title,
        artist: String = this.artist,
        keyRoot: Int = this.keyRoot,
        keyMode: Int = this.keyMode,
        keyConfidence: Float = this.keyConfidence,
        bpm: Float = this.bpm,
        notes: FloatArray = this.notes,
        lyrics: List<LyricLine> = this.lyrics,
        bestScore: Float = this.bestScore,
        timesPlayed: Int = this.timesPlayed,
        analyzed: Boolean = this.analyzed
    ) = Song(
        id, title, artist, uri, durationMs, keyRoot, keyMode, keyConfidence,
        bpm, notes, lyrics, addedAt, bestScore, timesPlayed, analyzed
    )

    override fun equals(other: Any?) = other is Song && other.id == id
    override fun hashCode() = id.hashCode()
}

/** A finished performance, on disk as a stereo WAV. */
class Take(
    val id: String,
    val songId: String,
    val songTitle: String,
    val path: String,
    val score: Float,
    val maxCombo: Int,
    val durationMs: Long,
    val recordedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?) = other is Take && other.id == id
    override fun hashCode() = id.hashCode()
}

/** Letter grade for a 0..1 score. */
fun gradeFor(score: Float): String = when {
    score >= 0.95f -> "S"
    score >= 0.88f -> "A"
    score >= 0.75f -> "B"
    score >= 0.60f -> "C"
    score >= 0.40f -> "D"
    else -> "E"
}
