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
    val analyzed: Boolean = false,
    /**
     * Backing track with the voice taken out by the separation model, or null
     * when the song has not been separated.
     *
     * When this is set it is what plays, and the mid/side canceller is held at
     * zero: there is nothing left for it to cancel and it would only take the
     * kick and the snare with it.
     */
    val instrumentalPath: String? = null,
    /** The isolated voice, kept for the melody guide and the guide vocal. */
    val vocalPath: String? = null,
    val separatedAt: Long = 0L,
    /** True when the melody came from the isolated voice, not the whole mix. */
    val melodyFromStem: Boolean = false
) {
    val noteCount: Int get() = notes.size / 3
    val separated: Boolean get() = !instrumentalPath.isNullOrBlank()
    /** What the stage should play: the separated backing track if there is one. */
    val playbackUri: String get() = instrumentalPath?.let { "file://$it" } ?: uri
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
        analyzed: Boolean = this.analyzed,
        instrumentalPath: String? = this.instrumentalPath,
        vocalPath: String? = this.vocalPath,
        separatedAt: Long = this.separatedAt,
        melodyFromStem: Boolean = this.melodyFromStem
    ) = Song(
        id, title, artist, uri, durationMs, keyRoot, keyMode, keyConfidence,
        bpm, notes, lyrics, addedAt, bestScore, timesPlayed, analyzed,
        instrumentalPath, vocalPath, separatedAt, melodyFromStem
    )

    override fun equals(other: Any?) = other is Song && other.id == id
    override fun hashCode() = id.hashCode()
}

/**
 * One recorded pass.
 *
 * `mixPath` holds everything that was playing plus the new voice, which is why
 * overdub costs nothing: the next layer just plays this file as its backing
 * track. `stemPath` keeps the processed voice alone, so a pass can be heard
 * on its own or kept when the mix is thrown away.
 *
 * `startSongMs` is the song time the file's first frame belongs to. A take is
 * recorded with the track already delayed by the round trip, so it starts
 * slightly before the song did, and the lyrics would run early without it.
 */
class TakeLayer(
    val id: String,
    val mixPath: String,
    val stemPath: String,
    val startSongMs: Double,
    val durationMs: Long,
    val recordedAt: Long = System.currentTimeMillis()
)

/** A performance: one layer, or a stack of them bounced down. */
class Take(
    val id: String,
    val songId: String,
    val songTitle: String,
    val layers: List<TakeLayer>,
    val score: Float,
    val maxCombo: Int,
    val recordedAt: Long = System.currentTimeMillis()
) {
    /** The mix as it stands: the newest layer already contains the older ones. */
    val path: String get() = layers.last().mixPath
    val startSongMs: Double get() = layers.last().startSongMs
    val durationMs: Long get() = layers.last().durationMs
    val layerCount: Int get() = layers.size

    fun withLayer(layer: TakeLayer, score: Float, maxCombo: Int) =
        Take(id, songId, songTitle, layers + layer, score, maxCombo, recordedAt)

    /** Drops the newest layer. Null when there is nothing left to undo. */
    fun withoutLastLayer(): Take? =
        if (layers.size <= 1) null
        else Take(id, songId, songTitle, layers.dropLast(1), score, maxCombo, recordedAt)

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
