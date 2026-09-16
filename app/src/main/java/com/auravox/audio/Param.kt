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

    // backing track
    const val TRACK_GAIN = 48
    const val TRACK_KEY_SHIFT = 49
    const val TRACK_TEMPO = 50
    const val TRACK_VOCAL_REMOVE = 51
    const val TRACK_DUCK = 52
    const val TRACK_WIDTH = 53

    // master and transport
    const val MASTER_GAIN = 64
    const val LATENCY_TRIM_MS = 65
    const val METRONOME_GAIN = 66
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
