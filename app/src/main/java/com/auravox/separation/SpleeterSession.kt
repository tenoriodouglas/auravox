package com.auravox.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * The two neural nets, one per stem.
 *
 * Spleeter's graph is a plain convolutional U-Net over magnitude
 * spectrograms — no transform, no complex arithmetic — which is exactly why it
 * can run here while the signal work stays in C++. Each net ends in a sigmoid
 * multiplied back into its input, so what comes out is a masked magnitude and
 * not a mask; turning the pair into masks that add up to the mixture is the
 * native side's job.
 *
 * Sessions are created once and reused for every split of a song. Creating one
 * parses twenty megabytes of weights, which is most of a second, and a song is
 * twenty splits.
 */
class SpleeterSession private constructor(
    private val environment: OrtEnvironment,
    private val vocals: OrtSession,
    private val accompaniment: OrtSession
) : Closeable {

    companion object {
        private const val INPUT = "x"
        private const val OUTPUT = "y"

        /** Null when either model is missing or will not load. */
        fun open(vocalsFile: File, accompanimentFile: File, threads: Int): SpleeterSession? {
            if (!vocalsFile.exists() || !accompanimentFile.exists()) return null
            return runCatching {
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(threads.coerceIn(1, 8))
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                SpleeterSession(
                    env,
                    env.createSession(vocalsFile.absolutePath, options),
                    env.createSession(accompanimentFile.absolutePath, options)
                )
            }.getOrNull()
        }
    }

    /**
     * Runs both nets over one split.
     *
     * [magnitudes] holds both channels laid out channel-major, which is the
     * order the net's leading dimension expects: channel, split, frame, bin.
     * The two results come back in the same layout.
     */
    fun run(magnitudes: FloatArray, frames: Int, bins: Int): Pair<FloatArray, FloatArray>? =
        runCatching {
            val shape = longArrayOf(2, 1, frames.toLong(), bins.toLong())
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(magnitudes), shape).use { input ->
                val feed = mapOf(INPUT to input)
                val v = infer(vocals, feed, magnitudes.size)
                val a = infer(accompaniment, feed, magnitudes.size)
                v to a
            }
        }.getOrNull()

    private fun infer(
        session: OrtSession, feed: Map<String, OnnxTensor>, expected: Int
    ): FloatArray {
        session.run(feed, setOf(OUTPUT)).use { result ->
            val tensor = result[0] as OnnxTensor
            val buffer = tensor.floatBuffer
            val out = FloatArray(minOf(expected, buffer.remaining()))
            buffer.get(out)
            return out
        }
    }

    override fun close() {
        runCatching { vocals.close() }
        runCatching { accompaniment.close() }
    }
}
