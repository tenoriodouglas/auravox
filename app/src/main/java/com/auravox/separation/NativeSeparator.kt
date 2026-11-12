package com.auravox.separation

/**
 * The signal half of separation, in C++.
 *
 * The neural net runs on this side of the boundary because its runtime has a
 * Java API and no C one worth binding; the transform, the masks and the rate
 * conversion stay in native code where they are already covered by the host
 * tests. That costs two crossings per eleven seconds of audio.
 */
object NativeSeparator {

    /** Transform the net expects: 4096 window, 1024 hop, at 44.1 kHz. */
    const val N_FFT = 4096
    const val HOP = 1024

    /**
     * Bins the net covers, of the 2049 a 4096 window produces.
     *
     * At 44.1 kHz that is everything up to 11 kHz. Above it the net says
     * nothing and the native side carries the top of the mask upward, because
     * leaving that band at zero — which is what Spleeter does by default —
     * measured 39 dB down and takes every cymbal with it.
     */
    const val MODEL_BINS = 1024

    /** Frames the net takes per split: 512 at hop 1024 is 11.9 s of audio. */
    const val FRAMES_PER_SPLIT = 512
    const val SAMPLES_PER_SPLIT = FRAMES_PER_SPLIT * HOP

    /** Rate the net was trained at. Its own metadata says 41000, which is a typo. */
    const val MODEL_RATE = 44100

    init {
        System.loadLibrary("auravox")
    }

    fun prepare(): Boolean = nativePrepare(N_FFT, HOP, MODEL_BINS)
    fun release() = nativeRelease()
    fun bins(): Int = nativeBins()
    fun frameCount(samples: Int): Int = nativeFrameCount(samples)

    /** Returns the frame count written, or 0 when magOut was sized wrong. */
    fun analyze(samples: FloatArray, channel: Int, magOut: FloatArray): Int =
        nativeAnalyze(samples, channel, magOut)

    fun synthesize(
        vocalMag: FloatArray, accompMag: FloatArray, channel: Int,
        vocalOut: FloatArray?, accompOut: FloatArray?
    ) = nativeSynthesize(vocalMag, accompMag, channel, vocalOut, accompOut)

    fun resampleFrames(inFrames: Int, inRate: Double, outRate: Double): Int =
        nativeResampleFrames(inFrames, inRate, outRate)

    fun resample(input: FloatArray, inRate: Double, outRate: Double, out: FloatArray): Int =
        nativeResample(input, inRate, outRate, out)

    private external fun nativePrepare(nFft: Int, hop: Int, modelBins: Int): Boolean
    private external fun nativeRelease()
    private external fun nativeBins(): Int
    private external fun nativeFrameCount(samples: Int): Int
    private external fun nativeAnalyze(samples: FloatArray, channel: Int, magOut: FloatArray): Int
    private external fun nativeSynthesize(
        vocalMag: FloatArray, accompMag: FloatArray, channel: Int,
        vocalOut: FloatArray?, accompOut: FloatArray?
    )
    private external fun nativeResampleFrames(inFrames: Int, inRate: Double, outRate: Double): Int
    private external fun nativeResample(
        input: FloatArray, inRate: Double, outRate: Double, out: FloatArray
    ): Int
}
