package com.auravox.audio

/**
 * Thin JNI facade. Holds no state; the engine lives on the native side.
 *
 * The two bulk getters exist because the performance screen polls at 60 Hz:
 * pulling a dozen values one method at a time is a dozen JNI transitions per
 * frame, and it shows up as jitter in the pitch lane.
 */
object NativeAudio {

    init {
        System.loadLibrary("auravox")
    }

    /** Indices into the array filled by [transportState]. */
    object T {
        const val VOICE_LEVEL = 0
        const val OUTPUT_LEVEL = 1
        const val PITCH_MIDI = 2
        const val CENTS_OFF = 3
        const val LATENCY_MS = 4
        const val ALIGN_MS = 5
        const val POSITION_MS = 6
        const val PLAYING = 7
        const val FINISHED = 8
        const val XRUNS = 9
        const val UNDERRUNS = 10
        const val COUNT_IN = 11
        const val OUT_LATENCY_MS = 12
        const val SONG_MS = 13
        const val LOOP_WRAP = 14
        const val SIZE = 15
    }

    /** Indices into the array filled by [scoreState]. */
    object S {
        const val SCORE = 0
        const val COMBO = 1
        const val MAX_COMBO = 2
        const val PERFECT = 3
        const val GREAT = 4
        const val GOOD = 5
        const val MISS = 6
        const val TARGET_MIDI = 7
        const val LIVE_ACCURACY = 8
        const val SIZE = 9
    }

    fun create(): Boolean = nativeCreate()
    fun destroy() = nativeDestroy()
    /**
     * @param deviceId Android AudioDeviceInfo id, or 0 to let the system pick.
     * @param communication gives up the fast capture path; a Bluetooth headset
     *   microphone runs over SCO, which has none.
     */
    fun start(deviceId: Int = 0, communication: Boolean = false): Boolean =
        nativeStart(deviceId, communication)

    /** Device the input actually opened on, which may not be the one asked for. */
    fun inputDeviceId(): Int = nativeInputDeviceId()
    fun inputIsLowLatency(): Boolean = nativeInputIsLowLatency()
    fun stop() = nativeStop()
    fun isRunning(): Boolean = nativeIsRunning()

    fun setParam(id: Int, value: Float) = nativeSetParam(id, value)
    fun getParam(id: Int): Float = nativeGetParam(id)

    fun sampleRate(): Int = nativeGetSampleRate()
    fun latencyMs(): Float = nativeGetLatencyMs()
    fun alignMs(): Float = nativeGetAlignMs()
    fun voiceLevel(): Float = nativeGetLevel()
    fun pitchMidi(): Float = nativeGetPitchMidi()

    fun transportState(out: FloatArray) = nativeGetTransportState(out)
    fun scoreState(out: FloatArray) = nativeGetScoreState(out)

    /** mixPath gets the full mix, stemPath the processed voice alone. */
    fun startRecording(mixPath: String, stemPath: String?): Boolean =
        nativeStartRecording(mixPath, stemPath)

    /** Song time the loaded source starts at. Non-zero for a bounced layer. */
    fun setSongOffsetMs(ms: Double) = nativeSetSongOffsetMs(ms)
    fun stopRecording() = nativeStopRecording()

    // --- backing track ---

    fun trackSetSource(sampleRate: Int, totalFrames: Long) =
        nativeTrackSetSource(sampleRate, totalFrames)

    /** Decoder thread. Returns how many frames the ring accepted. */
    fun trackPush(interleavedStereo: FloatArray, frames: Int): Int =
        nativeTrackPush(interleavedStereo, frames)

    fun trackRingSpace(): Int = nativeTrackRingSpace()

    /** Frames already decoded and waiting. Playback should not start on an empty ring. */
    fun trackBuffered(): Int = nativeTrackBuffered()
    fun trackSetEndOfStream(eos: Boolean) = nativeTrackSetEos(eos)
    fun trackSeek(frame: Long) = nativeTrackSeek(frame)

    /** Starts playback after `countInBeats` metronome clicks at `bpm`. */
    fun trackPlay(bpm: Float, countInBeats: Int) = nativeTrackPlay(bpm, countInBeats)
    fun trackPause() = nativeTrackPause()
    fun trackIsPlaying(): Boolean = nativeTrackIsPlaying()
    fun trackPositionMs(): Double = nativeTrackPositionMs()

    // --- scoring ---

    /** Triples of (startMs, durationMs, midi). Pass null to clear. */
    fun setMelody(triples: FloatArray?) =
        nativeSetMelody(triples, (triples?.size ?: 0) / 3)

    fun resetScore() = nativeResetScore()
    fun setScoringEnabled(on: Boolean) = nativeSetScoringEnabled(on)

    /**
     * Melody, key and tempo for an imported song. Blocking; call off the main
     * thread. `mono` is expected at 11025 Hz.
     *
     * Returns [keyRoot, keyMode, keyConfidence, bpm, bpmConfidence, noteCount,
     * then noteCount triples].
     */
    fun analyze(mono: FloatArray, frames: Int): FloatArray = nativeAnalyze(mono, frames)

    private external fun nativeCreate(): Boolean
    private external fun nativeDestroy()
    private external fun nativeStart(deviceId: Int, communication: Boolean): Boolean
    private external fun nativeInputDeviceId(): Int
    private external fun nativeInputIsLowLatency(): Boolean
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetParam(id: Int, value: Float)
    private external fun nativeGetParam(id: Int): Float
    private external fun nativeGetLevel(): Float
    private external fun nativeGetOutputLevel(): Float
    private external fun nativeGetPitchHz(): Float
    private external fun nativeGetPitchMidi(): Float
    private external fun nativeGetNote(): Int
    private external fun nativeGetCentsOff(): Float
    private external fun nativeGetGainReduction(): Float
    private external fun nativeGetLatencyMs(): Float
    private external fun nativeGetAlignMs(): Float
    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetXruns(): Int
    private external fun nativeStartRecording(mixPath: String, stemPath: String?): Boolean
    private external fun nativeSetSongOffsetMs(ms: Double)
    private external fun nativeStopRecording()
    private external fun nativeTrackSetSource(sampleRate: Int, totalFrames: Long)
    private external fun nativeTrackPush(data: FloatArray, frames: Int): Int
    private external fun nativeTrackRingSpace(): Int
    private external fun nativeTrackBuffered(): Int
    private external fun nativeTrackSetEos(eos: Boolean)
    private external fun nativeTrackSeek(frame: Long)
    private external fun nativeTrackPlay(bpm: Float, countInBeats: Int)
    private external fun nativeTrackPause()
    private external fun nativeTrackIsPlaying(): Boolean
    private external fun nativeTrackFinished(): Boolean
    private external fun nativeTrackPositionMs(): Double
    private external fun nativeTrackUnderruns(): Int
    private external fun nativeCountInBeatsLeft(): Int
    private external fun nativeSetMelody(triples: FloatArray?, noteCount: Int)
    private external fun nativeResetScore()
    private external fun nativeSetScoringEnabled(on: Boolean)
    private external fun nativeGetScoreState(out: FloatArray)
    private external fun nativeGetTransportState(out: FloatArray)
    private external fun nativeAnalyze(mono: FloatArray, frames: Int): FloatArray
}
