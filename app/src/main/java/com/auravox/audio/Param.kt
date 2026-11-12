package com.auravox.audio

/**
 * Parameter ids. Must stay in sync with ParamId in Params.h — the numbers are
 * the contract between the two sides. Each section reserves a gap so a new
 * parameter can be appended without shifting the ones after it.
 */
object Param {
    // vocal chain
    const val BYPASS = 0
    const val INPUT_GAIN = 1
    const val VOCAL_GAIN = 2
    const val EQ_LOW = 3
    const val EQ_MID = 4
    const val EQ_HIGH = 5
    const val GATE_THRESHOLD = 6
    const val COMP_THRESHOLD = 7
    const val COMP_RATIO = 8
    const val COMP_MAKEUP = 9
    const val COMP_ATTACK = 10
    const val COMP_RELEASE = 11
    const val DE_ESS = 12
    const val PITCH_AMOUNT = 13
    const val RETUNE_MS = 14
    const val KEY_ROOT = 15
    const val SCALE = 16
    const val TRANSPOSE = 17
    const val FORMANT = 18
    const val HARMONY_MIX = 19
    const val HARMONY_1 = 20
    const val HARMONY_2 = 21
    const val HARMONY_3 = 22
    const val HARMONY_VOICES = 23
    const val HARMONY_SPREAD = 24
    const val DOUBLER_MIX = 25
    const val DOUBLER_SPREAD = 26
    const val DOUBLER_DETUNE = 27
    const val DELAY_MIX = 28
    const val DELAY_TIME_MS = 29
    const val DELAY_FEEDBACK = 30
    const val DELAY_PING_PONG = 31
    const val REVERB_MIX = 32
    const val REVERB_SIZE = 33
    const val REVERB_DAMP = 34
    const val REVERB_PRE_DELAY = 35
    const val VOCAL_WIDTH = 36
    const val VOCODER_MIX = 37
    const val VOCODER_CARRIER = 38
    const val VOCODER_SIBILANCE = 39
    const val PITCH_GUIDE = 40
    const val VIBRATO_DEPTH = 41
    const val VIBRATO_RATE = 42
    const val ONSET_SNAP_MS = 43

    // backing track
    const val TRACK_GAIN = 48
    const val TRACK_KEY_SHIFT = 49
    const val TRACK_TEMPO = 50
    const val TRACK_VOCAL_REMOVE = 51
    const val TRACK_DUCK = 52
    const val TRACK_WIDTH = 53
    const val LOOP_START_MS = 54
    const val LOOP_END_MS = 55
    const val LOOP_ENABLED = 56

    // master and transport
    const val MASTER_GAIN = 64
    const val LATENCY_TRIM_MS = 65
    const val METRONOME_GAIN = 66
    const val MONITOR_VOICE = 67

    /**
     * Every parameter by a stable text name.
     *
     * Preset files are keyed on these rather than on the numbers, so a file
     * someone shared keeps working after an id is appended, and so the file is
     * readable by whoever receives it. Written out rather than derived by
     * reflection because reflection would need the field names to survive
     * minification, and a preset format that breaks only in release builds is
     * the worst kind of bug. tools/check_params.py asserts this covers every
     * id.
     */
    val byName: Map<String, Int> = mapOf(
        "bypass" to BYPASS,
        "input_gain" to INPUT_GAIN,
        "vocal_gain" to VOCAL_GAIN,
        "eq_low" to EQ_LOW,
        "eq_mid" to EQ_MID,
        "eq_high" to EQ_HIGH,
        "gate_threshold" to GATE_THRESHOLD,
        "comp_threshold" to COMP_THRESHOLD,
        "comp_ratio" to COMP_RATIO,
        "comp_makeup" to COMP_MAKEUP,
        "comp_attack" to COMP_ATTACK,
        "comp_release" to COMP_RELEASE,
        "de_ess" to DE_ESS,
        "pitch_amount" to PITCH_AMOUNT,
        "retune_ms" to RETUNE_MS,
        "key_root" to KEY_ROOT,
        "scale" to SCALE,
        "transpose" to TRANSPOSE,
        "formant" to FORMANT,
        "harmony_mix" to HARMONY_MIX,
        "harmony_1" to HARMONY_1,
        "harmony_2" to HARMONY_2,
        "harmony_3" to HARMONY_3,
        "harmony_voices" to HARMONY_VOICES,
        "harmony_spread" to HARMONY_SPREAD,
        "doubler_mix" to DOUBLER_MIX,
        "doubler_spread" to DOUBLER_SPREAD,
        "doubler_detune" to DOUBLER_DETUNE,
        "delay_mix" to DELAY_MIX,
        "delay_time_ms" to DELAY_TIME_MS,
        "delay_feedback" to DELAY_FEEDBACK,
        "delay_ping_pong" to DELAY_PING_PONG,
        "reverb_mix" to REVERB_MIX,
        "reverb_size" to REVERB_SIZE,
        "reverb_damp" to REVERB_DAMP,
        "reverb_pre_delay" to REVERB_PRE_DELAY,
        "vocal_width" to VOCAL_WIDTH,
        "vocoder_mix" to VOCODER_MIX,
        "vocoder_carrier" to VOCODER_CARRIER,
        "vocoder_sibilance" to VOCODER_SIBILANCE,
        "pitch_guide" to PITCH_GUIDE,
        "vibrato_depth" to VIBRATO_DEPTH,
        "vibrato_rate" to VIBRATO_RATE,
        "onset_snap_ms" to ONSET_SNAP_MS,
        "track_gain" to TRACK_GAIN,
        "track_key_shift" to TRACK_KEY_SHIFT,
        "track_tempo" to TRACK_TEMPO,
        "track_vocal_remove" to TRACK_VOCAL_REMOVE,
        "track_duck" to TRACK_DUCK,
        "track_width" to TRACK_WIDTH,
        "loop_start_ms" to LOOP_START_MS,
        "loop_end_ms" to LOOP_END_MS,
        "loop_enabled" to LOOP_ENABLED,
        "master_gain" to MASTER_GAIN,
        "latency_trim_ms" to LATENCY_TRIM_MS,
        "metronome_gain" to METRONOME_GAIN,
        "monitor_voice" to MONITOR_VOICE,
    )

    val nameOf: Map<Int, String> = byName.entries.associate { it.value to it.key }
}

enum class ScaleType(val label: String) {
    CHROMATIC("Cromática"),
    MAJOR("Maior"),
    MINOR("Menor"),
    MINOR_HARMONIC("Menor harmônica"),
    PENTATONIC("Pentatônica"),
    BLUES("Blues"),
    DORIAN("Dórica"),
    MIXOLYDIAN("Mixolídia")
}

val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun noteName(pitchClass: Int): String =
    if (pitchClass < 0) "--" else NOTE_NAMES[((pitchClass % 12) + 12) % 12]

/** Midi note to a display name with its octave, e.g. 60 becomes C4. */
fun midiName(midi: Int): String =
    if (midi < 0) "--" else "${noteName(midi % 12)}${midi / 12 - 1}"
