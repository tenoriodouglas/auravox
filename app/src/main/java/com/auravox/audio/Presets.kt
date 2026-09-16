package com.auravox.audio

/**
 * Presets are plain parameter maps rather than native code, so adding one
 * needs no rebuild of the .so.
 *
 * Anything a preset does not name keeps its current value on purpose: the
 * singer's input gain and the track balance are theirs, not the preset's.
 */
data class Preset(val label: String, val emoji: String, val values: Map<Int, Float>)

object Presets {

    val all = listOf(
        Preset(
            "Natural", "🎤", mapOf(
                Param.EQ_LOW to 0f, Param.EQ_MID to 2f, Param.EQ_HIGH to 2f,
                Param.COMP_THRESHOLD to -18f, Param.COMP_RATIO to 2.5f, Param.COMP_MAKEUP to 3f,
                Param.DE_ESS to 0.3f,
                Param.PITCH_GUIDE to 1f,
                Param.PITCH_AMOUNT to 0f, Param.FORMANT to 1f,
                Param.HARMONY_MIX to 0f, Param.DOUBLER_MIX to 0f,
                Param.VOCODER_MIX to 0f,
                Param.REVERB_MIX to 0.12f, Param.REVERB_SIZE to 0.5f,
                Param.DELAY_MIX to 0f, Param.VOCAL_WIDTH to 1f
            )
        ),
        Preset(
            "Karaokê", "✨", mapOf(
                Param.EQ_LOW to 1f, Param.EQ_MID to 3f, Param.EQ_HIGH to 3f,
                Param.COMP_THRESHOLD to -22f, Param.COMP_RATIO to 4f, Param.COMP_MAKEUP to 5f,
                Param.DE_ESS to 0.4f,
                Param.PITCH_AMOUNT to 0.85f, Param.RETUNE_MS to 25f,
                Param.PITCH_GUIDE to 1f,
                Param.FORMANT to 1f, Param.HARMONY_MIX to 0f,
                Param.DOUBLER_MIX to 0.15f, Param.DOUBLER_SPREAD to 0.8f,
                Param.VOCODER_MIX to 0f,
                Param.REVERB_MIX to 0.28f, Param.REVERB_SIZE to 0.75f,
                Param.REVERB_PRE_DELAY to 25f,
                Param.DELAY_MIX to 0.12f, Param.DELAY_TIME_MS to 90f,
                Param.DELAY_FEEDBACK to 0.2f, Param.VOCAL_WIDTH to 1.1f
            )
        ),
        Preset(
            "Perfeito", "🎯", mapOf(
                // Full correction onto the melody note the song is on, so the
                // voice lands where the singer was reaching instead of on the
                // nearest note of a scale
                Param.EQ_LOW to 1f, Param.EQ_MID to 3f, Param.EQ_HIGH to 3f,
                Param.COMP_THRESHOLD to -22f, Param.COMP_RATIO to 4f, Param.COMP_MAKEUP to 5f,
                Param.DE_ESS to 0.4f,
                Param.PITCH_AMOUNT to 1f, Param.RETUNE_MS to 8f,
                Param.PITCH_GUIDE to 1f,
                Param.FORMANT to 1f, Param.HARMONY_MIX to 0f,
                Param.DOUBLER_MIX to 0.12f,
                Param.VOCODER_MIX to 0f,
                Param.REVERB_MIX to 0.25f, Param.REVERB_SIZE to 0.7f,
                Param.REVERB_PRE_DELAY to 20f,
                Param.DELAY_MIX to 0.1f, Param.DELAY_TIME_MS to 100f,
                Param.VOCAL_WIDTH to 1.1f
            )
        ),
        Preset(
            "Hard tune", "🤖", mapOf(
                Param.EQ_LOW to -1f, Param.EQ_MID to 4f, Param.EQ_HIGH to 4f,
                Param.COMP_THRESHOLD to -24f, Param.COMP_RATIO to 6f, Param.COMP_MAKEUP to 6f,
                Param.DE_ESS to 0.5f,
                Param.PITCH_GUIDE to 1f,
                Param.PITCH_AMOUNT to 1f, Param.RETUNE_MS to 1.5f,
                Param.FORMANT to 1f, Param.HARMONY_MIX to 0f, Param.DOUBLER_MIX to 0f,
                Param.VOCODER_MIX to 0f,
                Param.REVERB_MIX to 0.18f,
                Param.DELAY_MIX to 0.2f, Param.DELAY_TIME_MS to 120f,
                Param.DELAY_FEEDBACK to 0.25f, Param.VOCAL_WIDTH to 1f
            )
        ),
        Preset(
            "Coro", "👥", mapOf(
                Param.EQ_LOW to 0f, Param.EQ_MID to 2f, Param.EQ_HIGH to 3f,
                Param.COMP_THRESHOLD to -20f, Param.COMP_RATIO to 3f, Param.COMP_MAKEUP to 4f,
                Param.DE_ESS to 0.35f,
                Param.PITCH_GUIDE to 1f,
                Param.PITCH_AMOUNT to 0.8f, Param.RETUNE_MS to 30f,
                Param.HARMONY_MIX to 0.5f, Param.HARMONY_VOICES to 3f,
                Param.HARMONY_1 to 2f, Param.HARMONY_2 to 4f, Param.HARMONY_3 to -3f,
                Param.HARMONY_SPREAD to 0.9f,
                Param.DOUBLER_MIX to 0f,
                Param.VOCODER_MIX to 0f,
                Param.REVERB_MIX to 0.35f, Param.REVERB_SIZE to 0.8f,
                Param.DELAY_MIX to 0.1f, Param.VOCAL_WIDTH to 1.2f
            )
        ),
        Preset(
            "Dupla", "🎸", mapOf(
                // Two voices a third apart, the way a sertanejo duo sings it
                Param.EQ_LOW to 0f, Param.EQ_MID to 3f, Param.EQ_HIGH to 3f,
                Param.COMP_THRESHOLD to -20f, Param.COMP_RATIO to 3.5f, Param.COMP_MAKEUP to 4f,
                Param.DE_ESS to 0.35f,
                Param.PITCH_GUIDE to 1f,
                Param.PITCH_AMOUNT to 0.6f, Param.RETUNE_MS to 40f,
                Param.HARMONY_MIX to 0.6f, Param.HARMONY_VOICES to 1f,
                Param.HARMONY_1 to 2f, Param.HARMONY_SPREAD to 0.5f,
                Param.DOUBLER_MIX to 0.1f,
                Param.VOCODER_MIX to 0f,
                Param.REVERB_MIX to 0.22f, Param.REVERB_SIZE to 0.65f,
                Param.DELAY_MIX to 0.08f, Param.VOCAL_WIDTH to 1.1f
            )
        ),
        Preset(
            "Balada", "🕯️", mapOf(
                Param.EQ_LOW to 1f, Param.EQ_MID to 1f, Param.EQ_HIGH to 2f,
                Param.COMP_THRESHOLD to -16f, Param.COMP_RATIO to 2.5f, Param.COMP_MAKEUP to 3f,
                Param.DE_ESS to 0.3f,
                Param.PITCH_GUIDE to 1f,
                Param.PITCH_AMOUNT to 0.3f, Param.RETUNE_MS to 90f,
                Param.HARMONY_MIX to 0f,
                Param.DOUBLER_MIX to 0.2f, Param.DOUBLER_DETUNE to 0.4f,
                Param.VOCODER_MIX to 0f,
                Param.REVERB_MIX to 0.45f, Param.REVERB_SIZE to 0.9f,
                Param.REVERB_DAMP to 0.3f, Param.REVERB_PRE_DELAY to 40f,
                Param.DELAY_MIX to 0.18f, Param.DELAY_TIME_MS to 380f,
                Param.DELAY_FEEDBACK to 0.35f, Param.VOCAL_WIDTH to 1.3f
            )
        ),
        Preset(
            "Vocoder", "🛸", mapOf(
                Param.EQ_LOW to -2f, Param.EQ_MID to 4f, Param.EQ_HIGH to 2f,
                Param.COMP_THRESHOLD to -26f, Param.COMP_RATIO to 6f, Param.COMP_MAKEUP to 6f,
                Param.DE_ESS to 0.1f,
                Param.PITCH_GUIDE to 1f,
                Param.PITCH_AMOUNT to 1f, Param.RETUNE_MS to 3f,
                Param.HARMONY_MIX to 0f, Param.DOUBLER_MIX to 0f,
                Param.VOCODER_MIX to 0.85f, Param.VOCODER_CARRIER to 0f,
                Param.VOCODER_SIBILANCE to 0.6f,
                Param.REVERB_MIX to 0.2f, Param.REVERB_SIZE to 0.6f,
                Param.DELAY_MIX to 0.15f, Param.DELAY_TIME_MS to 160f,
                Param.VOCAL_WIDTH to 1.2f
            )
        ),
        Preset(
            "Talkbox", "🎛️", mapOf(
                // Carrier is the backing track itself: the music speaks the words
                Param.EQ_LOW to 0f, Param.EQ_MID to 3f, Param.EQ_HIGH to 3f,
                Param.COMP_THRESHOLD to -24f, Param.COMP_RATIO to 5f, Param.COMP_MAKEUP to 5f,
                Param.DE_ESS to 0.1f,
                Param.PITCH_GUIDE to 1f,
                Param.PITCH_AMOUNT to 0f,
                Param.HARMONY_MIX to 0f, Param.DOUBLER_MIX to 0f,
                Param.VOCODER_MIX to 0.8f, Param.VOCODER_CARRIER to 1f,
                Param.VOCODER_SIBILANCE to 0.75f,
                Param.REVERB_MIX to 0.15f, Param.DELAY_MIX to 0.1f,
                Param.VOCAL_WIDTH to 1f
            )
        ),
        Preset(
            "Rádio", "📻", mapOf(
                Param.EQ_LOW to -6f, Param.EQ_MID to 6f, Param.EQ_HIGH to -4f,
                Param.COMP_THRESHOLD to -28f, Param.COMP_RATIO to 8f, Param.COMP_MAKEUP to 8f,
                Param.DE_ESS to 0.2f,
                Param.PITCH_GUIDE to 1f,
                Param.PITCH_AMOUNT to 0f, Param.FORMANT to 1f,
                Param.HARMONY_MIX to 0f, Param.DOUBLER_MIX to 0f,
                Param.VOCODER_MIX to 0f,
                Param.REVERB_MIX to 0f, Param.DELAY_MIX to 0f, Param.VOCAL_WIDTH to 0f
            )
        ),
        Preset(
            "Grave", "🐻", mapOf(
                Param.EQ_LOW to 3f, Param.EQ_MID to 1f, Param.EQ_HIGH to -2f,
                Param.COMP_THRESHOLD to -20f, Param.COMP_RATIO to 4f, Param.COMP_MAKEUP to 4f,
                Param.DE_ESS to 0.2f,
                Param.PITCH_GUIDE to 1f,
                Param.PITCH_AMOUNT to 0f, Param.TRANSPOSE to -5f, Param.FORMANT to 0.82f,
                Param.HARMONY_MIX to 0f, Param.DOUBLER_MIX to 0f,
                Param.VOCODER_MIX to 0f,
                Param.REVERB_MIX to 0.15f, Param.DELAY_MIX to 0f, Param.VOCAL_WIDTH to 1f
            )
        ),
        Preset(
            "Agudo", "🐿️", mapOf(
                Param.EQ_LOW to -3f, Param.EQ_MID to 2f, Param.EQ_HIGH to 3f,
                Param.COMP_THRESHOLD to -20f, Param.COMP_RATIO to 3f, Param.COMP_MAKEUP to 3f,
                Param.DE_ESS to 0.5f,
                Param.PITCH_GUIDE to 1f,
                Param.PITCH_AMOUNT to 0.5f, Param.TRANSPOSE to 4f, Param.FORMANT to 1.25f,
                Param.HARMONY_MIX to 0f, Param.DOUBLER_MIX to 0f,
                Param.VOCODER_MIX to 0f,
                Param.REVERB_MIX to 0.2f, Param.DELAY_MIX to 0.1f, Param.VOCAL_WIDTH to 1f
            )
        )
    )

    val default: Preset get() = all[1]
}
