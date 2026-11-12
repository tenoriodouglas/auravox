package com.auravox.audio

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Presets the singer made, saved and shareable.
 *
 * Before this, dialling in a sound and then touching another preset lost it
 * for good: the built-in list is code. A saved preset is a small readable JSON
 * file keyed on parameter names, which means one can be sent to someone else
 * and still work — and that a file made today survives a parameter being
 * appended tomorrow.
 *
 * A preset only names the parameters it means to set. Input gain, master
 * volume and the track balance are the singer's and the room's, not the
 * sound's, so they are never written and never applied.
 */
object UserPresets {

    private const val FORMAT = 1

    /** Parameters a preset must never carry, whoever made the file. */
    private val excluded = setOf(
        Param.INPUT_GAIN, Param.MASTER_GAIN, Param.MONITOR_VOICE,
        Param.TRACK_GAIN, Param.TRACK_KEY_SHIFT, Param.TRACK_TEMPO,
        Param.TRACK_VOCAL_REMOVE, Param.LOOP_START_MS, Param.LOOP_END_MS,
        Param.LOOP_ENABLED, Param.LATENCY_TRIM_MS, Param.METRONOME_GAIN,
        Param.BYPASS, Param.KEY_ROOT, Param.SCALE
    )

    /** Parameters a saved preset does carry, in a stable order. */
    val captured: List<Int> = Param.byName.values
        .filter { it !in excluded && it < Param.TRACK_GAIN }
        .sorted()

    private fun directory(context: Context) =
        File(context.filesDir, "presets").apply { mkdirs() }

    fun load(context: Context): List<Preset> =
        directory(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { parse(it.readText()) }
            ?.sortedBy { it.label.lowercase() }
            ?: emptyList()

    /** Overwrites a preset of the same name. Returns false if it cannot be written. */
    fun save(context: Context, label: String, values: Map<Int, Float>): Boolean {
        val clean = label.trim().take(40)
        if (clean.isEmpty()) return false
        return runCatching {
            File(directory(context), "${slug(clean)}.json")
                .writeText(serialize(clean, values))
            true
        }.getOrDefault(false)
    }

    fun delete(context: Context, label: String): Boolean =
        File(directory(context), "${slug(label)}.json").delete()

    fun fileFor(context: Context, label: String): File =
        File(directory(context), "${slug(label)}.json")

    /** Writes a preset somewhere it can be shared from, and returns the file. */
    fun export(context: Context, preset: Preset): File {
        val outside = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(outside, "${slug(preset.label)}.auravox.json")
        file.writeText(serialize(preset.label, preset.values))
        return file
    }

    fun serialize(label: String, values: Map<Int, Float>): String {
        val params = JSONObject()
        for ((id, value) in values.entries.sortedBy { it.key }) {
            if (id in excluded) continue
            val name = Param.nameOf[id] ?: continue
            params.put(name, value.toDouble())
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("app", "auravox")
            put("label", label)
            put("emoji", "🎚️")
            put("params", params)
        }.toString(2)
    }

    /**
     * Reads a preset file.
     *
     * Unknown names are skipped rather than refused: a file made by a newer
     * version mentioning a parameter this build has never heard of should
     * still set everything else it names.
     */
    fun parse(text: String): Preset? = runCatching {
        val root = JSONObject(text)
        if (root.optInt("format", 0) > FORMAT) return null
        val label = root.optString("label").trim().ifEmpty { return null }
        val params = root.optJSONObject("params") ?: return null

        val values = mutableMapOf<Int, Float>()
        for (name in params.keys()) {
            val id = Param.byName[name] ?: continue
            if (id in excluded) continue
            values[id] = params.optDouble(name, 0.0).toFloat()
        }
        if (values.isEmpty()) return null
        Preset(label, root.optString("emoji").ifEmpty { "🎚️" }, values)
    }.getOrNull()

    private fun slug(label: String): String =
        label.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .ifEmpty { "preset" }
            .take(48)
}
