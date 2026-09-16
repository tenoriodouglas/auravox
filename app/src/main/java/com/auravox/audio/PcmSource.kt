package com.auravox.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any audio file the platform understands into float PCM.
 *
 * Both the streaming player and the import-time analysis read through this, so
 * whatever the device can play, the app can score and transpose.
 */
class PcmSource private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    var sampleRate: Int,
    var channels: Int,
    val durationUs: Long
) {
    companion object {
        private const val TIMEOUT_US = 10_000L

        /** Returns null when the file holds no audio track this device can decode. */
        fun open(context: Context, uri: Uri): PcmSource? {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                extractor.release()
                return null
            }

            var index = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    index = i
                    format = f
                    break
                }
            }
            if (index < 0 || format == null) {
                extractor.release()
                return null
            }

            extractor.selectTrack(index)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                extractor.release()
                return null
            }

            return try {
                codec.configure(format, null, null, 0)
                codec.start()
                PcmSource(
                    extractor, codec,
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    if (format.containsKey(MediaFormat.KEY_DURATION))
                        format.getLong(MediaFormat.KEY_DURATION) else 0L
                )
            } catch (e: Exception) {
                codec.release()
                extractor.release()
                null
            }
        }
    }

    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var pcmEncoding = 2  // AudioFormat.ENCODING_PCM_16BIT
    private var scratch = FloatArray(8192)

    val finished: Boolean get() = outputDone

    /** Frame the next [read] will deliver, in source frames. */
    var positionFrames: Long = 0L
        private set

    fun seekTo(frame: Long) {
        val us = frame * 1_000_000L / sampleRate
        extractor.seekTo(us, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        // The extractor lands on the nearest sync sample, which is where
        // playback actually resumes; reporting the requested frame instead
        // would drift the lyrics against the audio
        positionFrames = extractor.sampleTime.coerceAtLeast(0L) * sampleRate / 1_000_000L
    }

    /**
     * Hands one decoded chunk to [sink] as interleaved floats, together with
     * the sample count. The array is reused between calls, so the sink must
     * consume it before returning. False means end of stream.
     */
    fun read(sink: (FloatArray, Int) -> Unit): Boolean {
        if (outputDone) return false

        while (true) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)
                    val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex >= 0 -> {
                    val produced = convert(codec.getOutputBuffer(outIndex))
                    codec.releaseOutputBuffer(outIndex, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (produced > 0) {
                        positionFrames += produced / channels
                        sink(scratch, produced)
                        return true
                    }
                    if (outputDone) return false
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        f.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ) {
                        pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }

                else -> if (outputDone) return false
            }
        }
    }

    /** Returns the number of samples written into [scratch]. */
    private fun convert(buffer: ByteBuffer?): Int {
        if (buffer == null || info.size <= 0) return 0
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.order(ByteOrder.nativeOrder())

        return when (pcmEncoding) {
            4 -> {  // ENCODING_PCM_FLOAT
                val src = buffer.asFloatBuffer()
                val n = src.remaining()
                ensure(n)
                src.get(scratch, 0, n)
                n
            }
            21 -> {  // ENCODING_PCM_24BIT_PACKED
                val n = info.size / 3
                ensure(n)
                for (i in 0 until n) {
                    val b0 = buffer.get().toInt() and 0xFF
                    val b1 = buffer.get().toInt() and 0xFF
                    val b2 = buffer.get().toInt()
                    scratch[i] = ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                }
                n
            }
            22 -> {  // ENCODING_PCM_32BIT
                val src = buffer.asIntBuffer()
                val n = src.remaining()
                ensure(n)
                for (i in 0 until n) scratch[i] = src.get() / 2147483648f
                n
            }
            else -> {  // ENCODING_PCM_16BIT
                val src = buffer.asShortBuffer()
                val n = src.remaining()
                ensure(n)
                for (i in 0 until n) scratch[i] = src.get() / 32768f
                n
            }
        }
    }

    private fun ensure(samples: Int) {
        if (scratch.size < samples) scratch = FloatArray(samples)
    }

    fun release() {
        try {
            codec.stop()
        } catch (ignored: Exception) {
        }
        codec.release()
        extractor.release()
    }
}
