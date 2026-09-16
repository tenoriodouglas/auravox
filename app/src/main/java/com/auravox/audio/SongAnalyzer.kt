package com.auravox.audio

import android.content.Context
import android.net.Uri

/**
 * Import-time analysis: melody guide, key and tempo for any file the user owns.
 *
 * This is what separates a karaoke app from a vocal effect. Without a
 * reference melody there is nothing to score against, and asking the user to
 * supply one per song is asking them not to use the app.
 *
 * The heavy work is native; this side only decodes and downsamples. Analysis
 * runs at 11025 Hz, which still reaches 5.5 kHz and cuts the pitch tracker's
 * cost by sixteen against the source rate.
 */
object SongAnalyzer {

    const val ANALYSIS_RATE = 11025
    private const val MAX_SECONDS = 600

    data class Result(
        val keyRoot: Int,
        val keyMode: Int,
        val keyConfidence: Float,
        val bpm: Float,
        val bpmConfidence: Float,
        val notes: FloatArray,
        val durationMs: Long
    ) {
        val noteCount: Int get() = notes.size / 3
    }

    /** Blocking. Call from a worker thread. Returns null if the file cannot be read. */
    fun analyze(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): Result? {
        val source = PcmSource.open(context, uri) ?: return null
        try {
            val mono = downsampleToMono(source, onProgress) ?: return null
            if (mono.second < ANALYSIS_RATE) return null  // under a second of audio

            onProgress(0.9f)
            val raw = NativeAudio.analyze(mono.first, mono.second)
            if (raw.size < 6) return null

            val noteCount = raw[5].toInt()
            val notes = FloatArray(noteCount * 3)
            System.arraycopy(raw, 6, notes, 0, minOf(notes.size, raw.size - 6))

            onProgress(1f)
            return Result(
                keyRoot = raw[0].toInt(),
                keyMode = raw[1].toInt(),
                keyConfidence = raw[2],
                bpm = raw[3],
                bpmConfidence = raw[4],
                notes = notes,
                durationMs = source.durationUs / 1000L
            )
        } finally {
            source.release()
        }
    }

    /**
     * Decodes the whole file into one mono buffer at [ANALYSIS_RATE].
     *
     * Decimation averages over the input window instead of picking every Nth
     * sample. That box filter is a crude low pass, but it is enough to keep
     * cymbals from aliasing down into the range the pitch tracker searches,
     * and aliased energy there reads as a wrong note.
     */
    private fun downsampleToMono(
        source: PcmSource, onProgress: (Float) -> Unit
    ): Pair<FloatArray, Int>? {
        val ratio = source.sampleRate.toDouble() / ANALYSIS_RATE
        if (ratio < 1.0) return null

        val limit = ANALYSIS_RATE * MAX_SECONDS
        var out = FloatArray(minOf(limit, ANALYSIS_RATE * 240))
        var count = 0

        var acc = 0.0
        var accCount = 0
        var phase = 0.0
        var lastProgress = 0f

        while (count < limit) {
            val more = source.read { data, samples ->
                val channels = source.channels
                val frames = samples / channels
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channels) sum += data[f * channels + c]
                    acc += (sum / channels).toDouble()
                    ++accCount
                    phase += 1.0

                    if (phase >= ratio) {
                        if (count == out.size) out = out.copyOf(out.size * 2)
                        out[count++] = (acc / accCount).toFloat()
                        acc = 0.0
                        accCount = 0
                        phase -= ratio
                    }
                }
            }
            if (!more) break

            if (source.durationUs > 0) {
                val p = 0.85f * (source.positionFrames.toFloat() /
                        (source.durationUs * source.sampleRate / 1_000_000L).toFloat())
                if (p - lastProgress > 0.02f) {
                    lastProgress = p
                    onProgress(p.coerceIn(0f, 0.85f))
                }
            }
        }
        return out to count
    }
}
