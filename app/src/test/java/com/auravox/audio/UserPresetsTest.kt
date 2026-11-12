package com.auravox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preset file format.
 *
 * It is the one thing in the app that leaves the device and comes back, so it
 * is the one thing that has to survive the app changing underneath it. These
 * tests are about that: a file keyed on names rather than numbers, tolerant of
 * names it has never seen, and refusing to carry the settings that belong to
 * the room rather than to the sound.
 */
class UserPresetsTest {

    private val sound = mapOf(
        Param.PITCH_AMOUNT to 0.85f,
        Param.RETUNE_MS to 25f,
        Param.REVERB_MIX to 0.3f,
        Param.HARMONY_MIX to 0.4f
    )

    @Test
    fun roundTrips() {
        val text = UserPresets.serialize("Meu som", sound)
        val back = UserPresets.parse(text)
        assertTrue("nao leu de volta", back != null)
        requireNotNull(back)
        assertEquals("Meu som", back.label)
        for ((id, value) in sound) {
            assertEquals("parametro $id", value, back.values[id]!!, 1e-4f)
        }
    }

    /** Names, not numbers, so appending a parameter does not break old files. */
    @Test
    fun isKeyedOnNames() {
        val text = UserPresets.serialize("x", sound)
        assertTrue("nao usou nomes: $text", text.contains("pitch_amount"))
        assertTrue(text.contains("reverb_mix"))
        assertTrue("usou numero como chave", !text.contains("\"13\""))
    }

    /**
     * A file from a newer build still applies what this one understands.
     *
     * Refusing the whole file over one unknown name would mean every preset
     * anyone shares stops working the first time a parameter is added.
     */
    @Test
    fun ignoresUnknownNames() {
        val text = """
            {
              "format": 1,
              "label": "Do futuro",
              "params": {
                "pitch_amount": 0.9,
                "um_parametro_que_nao_existe": 0.5,
                "reverb_mix": 0.2
              }
            }
        """.trimIndent()
        val preset = UserPresets.parse(text)
        requireNotNull(preset)
        assertEquals(0.9f, preset.values[Param.PITCH_AMOUNT]!!, 1e-4f)
        assertEquals(0.2f, preset.values[Param.REVERB_MIX]!!, 1e-4f)
        assertEquals("so os dois conhecidos", 2, preset.values.size)
    }

    /**
     * What belongs to the room never travels.
     *
     * Someone else's input gain would blow out or silence the singer's own
     * microphone, and their track volume has nothing to do with the sound they
     * were sharing.
     */
    @Test
    fun refusesWhatIsNotTheSound() {
        val text = UserPresets.serialize(
            "invasivo",
            sound + mapOf(
                Param.INPUT_GAIN to 4f,
                Param.MASTER_GAIN to 0.1f,
                Param.TRACK_GAIN to 0f,
                Param.MONITOR_VOICE to 1f
            )
        )
        assertTrue("vazou o ganho de entrada", !text.contains("input_gain"))
        assertTrue("vazou o volume geral", !text.contains("master_gain"))
        assertTrue("vazou o volume da base", !text.contains("track_gain"))

        // And refuses them even when a hand-written file names them
        val hostile = UserPresets.parse(
            """{"format":1,"label":"x","params":{"input_gain":9.0,"pitch_amount":0.5}}"""
        )
        requireNotNull(hostile)
        assertNull("aplicou o ganho de entrada", hostile.values[Param.INPUT_GAIN])
        assertEquals(0.5f, hostile.values[Param.PITCH_AMOUNT]!!, 1e-4f)
    }

    @Test
    fun refusesRubbish() {
        assertNull(UserPresets.parse(""))
        assertNull(UserPresets.parse("isto nao e json"))
        assertNull(UserPresets.parse("""{"format":1,"label":"x","params":{}}"""))
        assertNull(UserPresets.parse("""{"format":1,"params":{"reverb_mix":0.2}}"""))
        // A format from the future is not guessed at
        assertNull(UserPresets.parse("""{"format":99,"label":"x","params":{"reverb_mix":0.2}}"""))
    }

    /** Every built-in preset survives being written out and read back. */
    @Test
    fun theBuiltInsSurviveTheFormat() {
        for (preset in Presets.all) {
            val back = UserPresets.parse(UserPresets.serialize(preset.label, preset.values))
            assertTrue("perdeu ${preset.label}", back != null)
            requireNotNull(back)
            for ((id, value) in preset.values) {
                if (UserPresets.captured.contains(id)) {
                    assertEquals(
                        "${preset.label}, parametro ${Param.nameOf[id]}",
                        value, back.values[id]!!, 1e-4f
                    )
                }
            }
        }
    }

    /** The names are what the file hangs on, so every id must have one. */
    @Test
    fun everyParameterHasAName() {
        assertEquals(Param.byName.size, Param.nameOf.size)
        assertTrue(Param.byName.containsKey("pitch_amount"))
        assertTrue(Param.byName.containsKey("vibrato_depth"))
        assertEquals(Param.PITCH_AMOUNT, Param.byName["pitch_amount"])
        assertEquals("vibrato_depth", Param.nameOf[Param.VIBRATO_DEPTH])
    }
}
