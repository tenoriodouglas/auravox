package com.auravox.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams the backing track into the engine's ring buffer.
 *
 * Decoding runs on its own thread and blocks on ring space, never on the audio
 * thread. The ring holds six seconds, which is enough that a stall in the
 * platform decoder never reaches the speaker.
 */
class TrackDecoder(private val context: Context) {

    private var thread: Thread? = null
    private var guide: GuideMixer? = null

    /**
     * Level of the original voice played under the backing track, 0..1.
     *
     * Hearing the singer who recorded it, faintly, is how you learn a song you
     * do not know yet, and it is nearly free once the vocal has been separated
     * out. Mixed here rather than in the engine because it is one add per
     * frame on a thread that is already copying the audio, and putting it in
     * the realtime chain would mean a second ring buffer to keep in step.
     */
    @Volatile
    var guideLevel: Float = 0f
    private val running = AtomicBoolean(false)
    private val seekRequest = AtomicLong(-1L)

    var sampleRate: Int = 0
        private set
    var totalFrames: Long = 0L
        private set
    var error: String? = null
        private set

    /**
     * Opens the file and starts filling the ring.
     *
     * [guideUri] is the isolated vocal of the same song, played underneath at
     * [guideLevel]. It must be the same length and rate as the track, which it
     * is: both come out of one separation pass.
     */
    fun start(uri: Uri, guideUri: Uri? = null): Boolean {
        stop()
        val probe = PcmSource.open(context, uri) ?: run {
            error = "unsupported"
            return false
        }
        sampleRate = probe.sampleRate
        totalFrames = probe.durationUs * probe.sampleRate / 1_000_000L
        error = null

        NativeAudio.trackSetSource(sampleRate, totalFrames)
        NativeAudio.trackSetEndOfStream(false)

        guide = guideUri?.let { GuideMixer.open(context, it) }

        running.set(true)
        thread = Thread({ pump(probe) }, "auravox-decoder").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        return true
    }

    /** Restarts decoding at `frame`, dropping whatever is already buffered. */
    fun seek(frame: Long) {
        NativeAudio.trackSeek(frame)
        NativeAudio.trackSetEndOfStream(false)
        seekRequest.set(frame)
    }

    fun stop() {
        running.set(false)
        thread?.join(500)
        thread = null
        guide?.release()
        guide = null
    }

    private fun pump(source: PcmSource) {
        val stereo = FloatArray(MAX_FRAMES * 2)
        try {
            while (running.get()) {
                val target = seekRequest.getAndSet(-1L)
                if (target >= 0) {
                    source.seekTo(target)
                    guide?.seekTo(target)
                }

                // Back off while the ring is full; the audio thread drains it
                if (NativeAudio.trackRingSpace() < MAX_FRAMES) {
                    Thread.sleep(8)
                    continue
                }

                val more = source.read { data, samples ->
                    val channels = source.channels
                    val frames = samples / channels
                    var f = 0
                    while (f < frames) {
                        val chunk = minOf(MAX_FRAMES, frames - f)
                        interleaveStereo(data, (f * channels), chunk, channels, stereo)
                        // Always, even at zero. The guide's read position only
                        // moves when it is asked for audio, so skipping it
                        // while the level is down left it at the start of the
                        // file: raising the slider a minute into a song played
                        // the original singer from the first verse.
                        guide?.mixInto(stereo, chunk, guideLevel)
                        pushAll(stereo, chunk)
                        f += chunk
                    }
                }
                if (!more) {
                    NativeAudio.trackSetEndOfStream(true)
                    // Stay alive so a seek can restart the same source
                    while (running.get() && seekRequest.get() < 0) Thread.sleep(20)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "decoder stopped: ${e.message}")
            error = e.message
        } finally {
            source.release()
        }
    }

    /**
     * Blocks until the ring takes everything, so no frame is ever dropped.
     * A partial push is compacted in place rather than copied out: this runs
     * on every chunk and the decoder thread should not be making garbage.
     */
    private fun pushAll(stereo: FloatArray, frames: Int) {
        var remaining = frames
        while (remaining > 0 && running.get()) {
            val n = NativeAudio.trackPush(stereo, remaining)
            if (n <= 0) {
                Thread.sleep(4)
            } else if (n < remaining) {
                System.arraycopy(stereo, n * 2, stereo, 0, (remaining - n) * 2)
                remaining -= n
            } else {
                remaining = 0
            }
        }
    }

    /**
     * Reads the vocal stem in step with the track and adds it in.
     *
     * The two sources hand out chunks of whatever size their decoders feel
     * like, so what is read ahead is held and spent as the track asks for it.
     * Running dry is silence rather than a stall: a guide vocal is a courtesy,
     * and it must never be the reason the backing track stutters.
     */
    private class GuideMixer private constructor(private val source: PcmSource) {

        private var pending = FloatArray(0)
        private var held = 0
        private var exhausted = false

        companion object {
            fun open(context: Context, uri: Uri): GuideMixer? =
                PcmSource.open(context, uri)?.let { GuideMixer(it) }
        }

        fun mixInto(stereo: FloatArray, frames: Int, level: Float) {
            fill(frames * 2)
            val take = minOf(frames * 2, held)
            if (level > 0.001f) {
                for (i in 0 until take) stereo[i] += pending[i] * level
            }
            if (take < held) {
                System.arraycopy(pending, take, pending, 0, held - take)
            }
            held -= take
        }

        private fun fill(samples: Int) {
            while (held < samples && !exhausted) {
                val more = source.read { data, count ->
                    val channels = source.channels.coerceAtLeast(1)
                    val frames = count / channels
                    grow(held + frames * 2)
                    interleaveStereo(data, 0, frames, channels, scratch)
                    System.arraycopy(scratch, 0, pending, held, frames * 2)
                    held += frames * 2
                }
                if (!more) exhausted = true
            }
        }

        private var scratch = FloatArray(0)

        private fun grow(needed: Int) {
            if (pending.size < needed) pending = pending.copyOf(maxOf(needed, pending.size * 2, 8192))
            if (scratch.size < needed) scratch = FloatArray(maxOf(needed, 8192))
        }

        fun seekTo(frame: Long) {
            held = 0
            exhausted = false
            source.seekTo(frame)
        }

        fun release() = source.release()
    }

    companion object {
        private const val TAG = "AuraVox"
        private const val MAX_FRAMES = 4096

        /**
         * Folds any channel layout down to stereo. Mono is duplicated rather
         * than panned left, and anything above stereo keeps the front pair.
         */
        fun interleaveStereo(
            src: FloatArray, offset: Int, frames: Int, channels: Int, dst: FloatArray
        ) {
            when (channels) {
                1 -> for (i in 0 until frames) {
                    val v = src[offset + i]
                    dst[i * 2] = v
                    dst[i * 2 + 1] = v
                }
                2 -> System.arraycopy(src, offset, dst, 0, frames * 2)
                else -> for (i in 0 until frames) {
                    dst[i * 2] = src[offset + i * channels]
                    dst[i * 2 + 1] = src[offset + i * channels + 1]
                }
            }
        }
    }
}
