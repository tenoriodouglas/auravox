package com.auravox.separation

import android.content.Context
import android.net.Uri
import com.auravox.audio.PcmSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Turns a song into a backing track and an isolated vocal.
 *
 * This replaces subtracting the centre of the stereo image, which takes the
 * kick and the snare out with the voice and leaves the voice wherever it was
 * not dead centre. A model trained on what a voice looks like does better, at
 * the cost of being an offline job: around half a minute for a four minute
 * song, with a progress bar, once per song ever.
 *
 * Memory is what shapes this code. Four minutes of stereo is eighty megabytes
 * as floats, and holding the input, a resampled copy and two outputs at once
 * would be most of a phone's heap. So the decode is spooled to a scratch file
 * and the song is then walked eleven seconds at a time, which bounds what is
 * resident to a few megabytes whatever the song's length.
 *
 * The decode and the rate conversion are separate passes on purpose: the
 * decoder may only report the real sample rate after its first chunk, so a
 * converter set up before that can be set up for the wrong rate.
 */
class VocalSeparator(private val context: Context) {

    enum class Phase { MODEL, DECODE, RESAMPLE, SEPARATE }

    data class Stems(
        val instrumental: File,
        val vocal: File?,
        val sampleRate: Int,
        val frames: Long
    )

    sealed interface Result {
        data class Done(val stems: Stems) : Result
        data class Failed(val reason: String) : Result
        data object Cancelled : Result
    }

    /**
     * Separates [uri] into files named after [songId].
     *
     * [keepVocal] writes the vocal stem too. It is worth keeping for the guide
     * vocal and for melody analysis, and worth dropping afterwards if space
     * matters, because it is the same size as the instrumental.
     */
    suspend fun separate(
        uri: Uri,
        songId: String,
        keepVocal: Boolean,
        onProgress: (Phase, Float) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        ModelStore.ensure(context) { onProgress(Phase.MODEL, it) }
            ?.let { return@withContext Result.Failed(it) }

        val scratch = File(context.cacheDir, "separation").apply { mkdirs() }
        val decodedFile = File(scratch, "$songId.decoded")
        val resampledFile = File(scratch, "$songId.resampled")
        val outDir = File(context.filesDir, "stems/$songId").apply { mkdirs() }
        val instrumentalFile = File(outDir, "instrumental.wav")
        val vocalFile = File(outDir, "vocals.wav")

        try {
            val decoded = decode(uri, decodedFile) { onProgress(Phase.DECODE, it) }
                ?: return@withContext Result.Failed("Não foi possível ler o áudio.")

            val source: File
            val frames: Long
            if (decoded.rate == NativeSeparator.MODEL_RATE) {
                source = decodedFile
                frames = decoded.frames
                onProgress(Phase.RESAMPLE, 1f)
            } else {
                frames = convert(
                    decodedFile, resampledFile, decoded.frames, decoded.rate
                ) { onProgress(Phase.RESAMPLE, it) }
                source = resampledFile
            }
            if (frames <= 0L) return@withContext Result.Failed("O áudio ficou vazio.")

            val session = SpleeterSession.open(
                ModelStore.fileFor(context, ModelStore.VOCALS),
                ModelStore.fileFor(context, ModelStore.ACCOMPANIMENT),
                threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
            ) ?: return@withContext Result.Failed("Os modelos não carregaram.")

            session.use {
                NativeSeparator.prepare()
                try {
                    run(source, frames, it, instrumentalFile, if (keepVocal) vocalFile else null) { p ->
                        onProgress(Phase.SEPARATE, p)
                    }
                } finally {
                    NativeSeparator.release()
                }
            }

            Result.Done(
                Stems(
                    instrumental = instrumentalFile,
                    vocal = if (keepVocal) vocalFile else null,
                    sampleRate = NativeSeparator.MODEL_RATE,
                    frames = frames
                )
            )
        } catch (e: CancellationException) {
            clean(instrumentalFile, vocalFile)
            Result.Cancelled
        } catch (e: Exception) {
            clean(instrumentalFile, vocalFile)
            Result.Failed(e.message ?: "A separação falhou.")
        } finally {
            decodedFile.delete()
            resampledFile.delete()
        }
    }

    /** Removes the stems a song owns. */
    fun discard(songId: String) {
        File(context.filesDir, "stems/$songId").deleteRecursively()
    }

    fun stemsFor(songId: String): Stems? {
        val dir = File(context.filesDir, "stems/$songId")
        val instrumental = File(dir, "instrumental.wav")
        if (!instrumental.exists() || instrumental.length() <= 44L) return null
        val vocal = File(dir, "vocals.wav").takeIf { it.exists() && it.length() > 44L }
        return Stems(
            instrumental, vocal, NativeSeparator.MODEL_RATE,
            (instrumental.length() - 44L) / 4L
        )
    }

    private fun clean(vararg files: File) = files.forEach { it.delete() }

    private data class Decoded(val frames: Long, val rate: Int)

    /** Decodes to interleaved stereo floats at the file's own rate. */
    private suspend fun decode(uri: Uri, target: File, onProgress: (Float) -> Unit): Decoded? {
        val source = PcmSource.open(context, uri) ?: return null
        var written = 0L
        val totalUs = source.durationUs

        try {
            BufferedOutputStream(target.outputStream(), 1 shl 16).use { sink ->
                val staging = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
                var keepReading = true
                while (keepReading) {
                    coroutineContext.ensureActive()
                    keepReading = source.read { buffer, samples ->
                        val channels = source.channels.coerceAtLeast(1)
                        val frames = samples / channels
                        for (f in 0 until frames) {
                            if (staging.remaining() < 8) {
                                sink.write(staging.array(), 0, staging.position())
                                staging.clear()
                            }
                            val base = f * channels
                            staging.putFloat(buffer[base])
                            staging.putFloat(if (channels > 1) buffer[base + 1] else buffer[base])
                        }
                        written += frames
                    }
                    if (totalUs > 0 && source.sampleRate > 0) {
                        val total = totalUs * source.sampleRate / 1_000_000L
                        if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
                if (staging.position() > 0) sink.write(staging.array(), 0, staging.position())
            }
        } finally {
            source.release()
        }
        onProgress(1f)
        return if (written > 0L) Decoded(written, source.sampleRate) else null
    }

    /**
     * Converts the scratch file to the rate the net was trained at.
     *
     * Done in overlapping chunks, with the kernel's reach carried across each
     * boundary, so the conversion does not see a cliff every buffer.
     */
    private suspend fun convert(
        from: File, to: File, frames: Long, inRate: Int, onProgress: (Float) -> Unit
    ): Long {
        val outRate = NativeSeparator.MODEL_RATE.toDouble()
        val chunk = 1 shl 18   // about six seconds at 44.1 kHz
        var written = 0L

        RandomAccessFile(from, "r").use { input ->
            BufferedOutputStream(to.outputStream(), 1 shl 16).use { sink ->
                val staging = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
                var offset = 0L
                while (offset < frames) {
                    coroutineContext.ensureActive()

                    // Read the chunk plus the kernel's reach on either side
                    val head = minOf(offset, kContext.toLong()).toInt()
                    val start = offset - head
                    val wanted = (chunk + head + kContext).toInt()
                    val left = FloatArray(wanted)
                    val right = FloatArray(wanted)
                    val got = readFrames(input, start, left, right)
                    if (got <= 0) break

                    val outTotal = NativeSeparator.resampleFrames(got, inRate.toDouble(), outRate)
                    if (outTotal <= 0) break
                    val outL = FloatArray(outTotal)
                    val outR = FloatArray(outTotal)
                    NativeSeparator.resample(left, inRate.toDouble(), outRate, outL)
                    NativeSeparator.resample(right, inRate.toDouble(), outRate, outR)

                    // Drop what the leading context already covered, and the
                    // trailing context, which the next chunk will produce
                    val skip = NativeSeparator.resampleFrames(head, inRate.toDouble(), outRate)
                    val body = NativeSeparator.resampleFrames(
                        minOf(chunk.toLong(), frames - offset).toInt(), inRate.toDouble(), outRate
                    )
                    val end = minOf(skip + body, outTotal)

                    for (i in skip until end) {
                        if (staging.remaining() < 8) {
                            sink.write(staging.array(), 0, staging.position())
                            staging.clear()
                        }
                        staging.putFloat(outL[i])
                        staging.putFloat(outR[i])
                        ++written
                    }

                    offset += chunk
                    onProgress((offset.toFloat() / frames).coerceIn(0f, 1f))
                }
                if (staging.position() > 0) sink.write(staging.array(), 0, staging.position())
            }
        }
        onProgress(1f)
        return written
    }

    /**
     * Walks the song one split at a time.
     *
     * Splits overlap by a short crossfade. The transform reconstructs exactly
     * in the middle of a run of windows and less so at the very ends, where
     * fewer of them contribute, so butting splits together would leave a seam
     * every eleven seconds — twenty ticks in a song.
     */
    private suspend fun run(
        source: File,
        totalFrames: Long,
        session: SpleeterSession,
        instrumentalFile: File,
        vocalFile: File?,
        onProgress: (Float) -> Unit
    ) {
        val frames = NativeSeparator.FRAMES_PER_SPLIT
        val bins = NativeSeparator.MODEL_BINS
        val plane = frames * bins
        // One fewer hop than frames, so the transform yields exactly the 512
        // the net's input shape fixes
        val splitSamples = (frames - 1) * NativeSeparator.HOP
        val advance = splitSamples - kCrossfade

        val magnitudes = FloatArray(2 * plane)
        val channelMag = FloatArray(plane)
        val left = FloatArray(splitSamples)
        val right = FloatArray(splitSamples)

        val instrumental = Stitcher(WavSink(instrumentalFile, NativeSeparator.MODEL_RATE))
        val vocal = vocalFile?.let { Stitcher(WavSink(it, NativeSeparator.MODEL_RATE)) }
        val instL = FloatArray(splitSamples)
        val instR = FloatArray(splitSamples)
        val vocL = FloatArray(splitSamples)
        val vocR = FloatArray(splitSamples)

        try {
            RandomAccessFile(source, "r").use { file ->
                var offset = 0L
                while (offset < totalFrames) {
                    coroutineContext.ensureActive()

                    val got = readFrames(file, offset, left, right)
                    if (got <= 0) break

                    for (channel in 0..1) {
                        NativeSeparator.analyze(
                            if (channel == 0) left else right, channel, channelMag
                        )
                        channelMag.copyInto(magnitudes, channel * plane)
                    }

                    val (vocalMag, accompMag) = session.run(magnitudes, frames, bins)
                        ?: throw IllegalStateException("A rede não respondeu.")

                    for (channel in 0..1) {
                        val v = vocalMag.copyOfRange(channel * plane, (channel + 1) * plane)
                        val a = accompMag.copyOfRange(channel * plane, (channel + 1) * plane)
                        NativeSeparator.synthesize(
                            v, a, channel,
                            if (vocal != null) (if (channel == 0) vocL else vocR) else null,
                            if (channel == 0) instL else instR
                        )
                    }

                    val last = offset + splitSamples.toLong() >= totalFrames
                    val emit = if (last) got else advance
                    instrumental.push(instL, instR, emit, kCrossfade, last)
                    vocal?.push(vocL, vocR, emit, kCrossfade, last)

                    offset += advance.toLong()
                    if (totalFrames > 0) {
                        onProgress((offset.toFloat() / totalFrames).coerceIn(0f, 1f))
                    }
                    if (last) break
                }
            }
        } finally {
            instrumental.close()
            vocal?.close()
        }
        onProgress(1f)
    }

    /** Reads interleaved stereo floats, zero-filling past the end. */
    private fun readFrames(
        file: RandomAccessFile, offset: Long, left: FloatArray, right: FloatArray
    ): Int {
        val wanted = left.size
        file.seek(offset * 8L)
        val raw = ByteArray(wanted * 8)
        var filled = 0
        while (filled < raw.size) {
            val read = file.read(raw, filled, raw.size - filled)
            if (read <= 0) break
            filled += read
        }
        val got = filled / 8
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until got) {
            left[i] = buffer.getFloat(i * 8)
            right[i] = buffer.getFloat(i * 8 + 4)
        }
        for (i in got until wanted) { left[i] = 0f; right[i] = 0f }
        return got
    }

    /**
     * Joins consecutive splits without a seam.
     *
     * Each split holds back its tail; the next one's head is faded into it.
     * The held region is written as part of the following split, so nothing is
     * duplicated and nothing is dropped.
     */
    private class Stitcher(private val sink: WavSink) {
        private var heldL = FloatArray(0)
        private var heldR = FloatArray(0)

        fun push(
            left: FloatArray, right: FloatArray, emit: Int, overlap: Int, last: Boolean
        ) {
            val n = minOf(emit, left.size)
            val fade = minOf(heldL.size, n)
            for (i in 0 until fade) {
                val t = (i + 1).toFloat() / (fade + 1).toFloat()
                left[i] = heldL[i] * (1f - t) + left[i] * t
                right[i] = heldR[i] * (1f - t) + right[i] * t
            }

            if (last) {
                sink.write(left, right, n)
                heldL = FloatArray(0)
                heldR = FloatArray(0)
                return
            }

            sink.write(left, right, n)
            val to = minOf(n + overlap, left.size)
            heldL = left.copyOfRange(n, to)
            heldR = right.copyOfRange(n, to)
        }

        fun close() = sink.close()
    }

    private companion object {
        /** Window consecutive splits share, so a seam is faded not butted. */
        const val kCrossfade = 2048

        /** Input samples the resampler's kernel reaches over. */
        const val kContext = 64
    }
}
