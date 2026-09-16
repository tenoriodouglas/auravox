package com.auravox.karaoke

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File-backed library. One JSON index for songs, one for takes.
 *
 * Melodies live in the same JSON: a four minute song segments into a few
 * hundred notes, which is a few kilobytes, and keeping it in one file means an
 * import is atomic.
 */
class SongRepository(private val context: Context) {

    private val dir = File(context.filesDir, "library").apply { mkdirs() }
    private val songsFile = File(dir, "songs.json")
    private val takesFile = File(dir, "takes.json")

    val takesDir: File
        get() = (context.getExternalFilesDir("takes") ?: File(context.filesDir, "takes"))
            .apply { mkdirs() }

    // --- songs ---

    fun loadSongs(): List<Song> {
        if (!songsFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(songsFile.readText())
            (0 until array.length()).map { songFromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveSongs(songs: List<Song>) {
        val array = JSONArray()
        songs.forEach { array.put(songToJson(it)) }
        songsFile.writeText(array.toString())
    }

    // --- takes ---

    fun loadTakes(): List<Take> {
        if (!takesFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(takesFile.readText())
            (0 until array.length())
                .map { takeFromJson(array.getJSONObject(it)) }
                .filter { File(it.path).exists() }
        }.getOrDefault(emptyList())
    }

    fun saveTakes(takes: List<Take>) {
        val array = JSONArray()
        takes.forEach { array.put(takeToJson(it)) }
        takesFile.writeText(array.toString())
    }

    fun deleteTake(take: Take) {
        File(take.path).delete()
    }

    /**
     * Reads an .lrc the user picked. Lyrics files are still routinely saved in
     * a legacy encoding, so a decode that produced replacement characters is
     * retried as Latin-1 rather than shown with boxes in it.
     */
    fun readLyrics(uri: Uri): List<LyricLine> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            emptyList()
        } else {
            val text = String(bytes, Charsets.UTF_8)
            val decoded = if (text.contains('\uFFFD')) String(bytes, Charsets.ISO_8859_1) else text
            Lrc.parse(decoded).lines
        }
    }.getOrDefault(emptyList())

    // --- serialization ---

    private fun songToJson(s: Song) = JSONObject().apply {
        put("id", s.id)
        put("title", s.title)
        put("artist", s.artist)
        put("uri", s.uri)
        put("durationMs", s.durationMs)
        put("keyRoot", s.keyRoot)
        put("keyMode", s.keyMode)
        put("keyConfidence", s.keyConfidence.toDouble())
        put("bpm", s.bpm.toDouble())
        put("addedAt", s.addedAt)
        put("bestScore", s.bestScore.toDouble())
        put("timesPlayed", s.timesPlayed)
        put("analyzed", s.analyzed)
        put("notes", JSONArray().apply { s.notes.forEach { put(it.toDouble()) } })
        put("lyrics", JSONArray().apply {
            s.lyrics.forEach { line ->
                put(JSONObject().apply {
                    put("t", line.timeMs)
                    put("x", line.text)
                    if (line.words.isNotEmpty()) {
                        put("w", JSONArray().apply {
                            line.words.forEach { w ->
                                put(JSONObject().apply { put("t", w.timeMs); put("x", w.text) })
                            }
                        })
                    }
                })
            }
        })
    }

    private fun songFromJson(o: JSONObject): Song {
        val rawNotes = o.optJSONArray("notes")
        val notes = FloatArray(rawNotes?.length() ?: 0) { rawNotes!!.getDouble(it).toFloat() }

        val rawLyrics = o.optJSONArray("lyrics")
        val lyrics = (0 until (rawLyrics?.length() ?: 0)).map { i ->
            val line = rawLyrics!!.getJSONObject(i)
            val rawWords = line.optJSONArray("w")
            LyricLine(
                timeMs = line.optLong("t"),
                text = line.optString("x"),
                words = (0 until (rawWords?.length() ?: 0)).map { j ->
                    val w = rawWords!!.getJSONObject(j)
                    LyricWord(w.optLong("t"), w.optString("x"))
                }
            )
        }

        return Song(
            id = o.getString("id"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            uri = o.optString("uri"),
            durationMs = o.optLong("durationMs"),
            keyRoot = o.optInt("keyRoot"),
            keyMode = o.optInt("keyMode"),
            keyConfidence = o.optDouble("keyConfidence", 0.0).toFloat(),
            bpm = o.optDouble("bpm", 0.0).toFloat(),
            notes = notes,
            lyrics = lyrics,
            addedAt = o.optLong("addedAt", System.currentTimeMillis()),
            bestScore = o.optDouble("bestScore", 0.0).toFloat(),
            timesPlayed = o.optInt("timesPlayed"),
            analyzed = o.optBoolean("analyzed")
        )
    }

    private fun takeToJson(t: Take) = JSONObject().apply {
        put("id", t.id)
        put("songId", t.songId)
        put("songTitle", t.songTitle)
        put("path", t.path)
        put("score", t.score.toDouble())
        put("maxCombo", t.maxCombo)
        put("durationMs", t.durationMs)
        put("recordedAt", t.recordedAt)
    }

    private fun takeFromJson(o: JSONObject) = Take(
        id = o.getString("id"),
        songId = o.optString("songId"),
        songTitle = o.optString("songTitle"),
        path = o.optString("path"),
        score = o.optDouble("score", 0.0).toFloat(),
        maxCombo = o.optInt("maxCombo"),
        durationMs = o.optLong("durationMs"),
        recordedAt = o.optLong("recordedAt")
    )
}
