package com.auravox.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auravox.audio.NativeAudio
import com.auravox.audio.Param
import com.auravox.audio.Preset
import com.auravox.audio.Presets
import com.auravox.audio.ScaleType
import com.auravox.audio.SongAnalyzer
import com.auravox.audio.TrackDecoder
import com.auravox.karaoke.Song
import com.auravox.karaoke.SongRepository
import com.auravox.karaoke.Take
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Everything the screens talk to.
 *
 * The audio engine and the decoder live outside the composition; this class
 * owns their lifecycle, mirrors the native parameters so sliders have
 * something to read, and polls the meters at a rate that matches whichever
 * screen is on top.
 */
class KaraokeViewModel(app: Application) : AndroidViewModel(app) {

    enum class Screen { LIBRARY, STAGE, RESULT, TAKES }

    private val repo = SongRepository(app)
    private val decoder = TrackDecoder(app)

    private val transport = FloatArray(NativeAudio.T.SIZE)
    private val scoreBuf = FloatArray(NativeAudio.S.SIZE)

    val trail = PitchTrail()
    var trailVersion by mutableStateOf(0); private set

    // --- navigation ---
    var screen by mutableStateOf(Screen.LIBRARY); private set
    var showMixer by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    // --- library ---
    var songs by mutableStateOf<List<Song>>(emptyList()); private set
    var takes by mutableStateOf<List<Take>>(emptyList()); private set
    var current by mutableStateOf<Song?>(null); private set
    var analyzing by mutableStateOf<String?>(null); private set
    var analyzeProgress by mutableStateOf(0f); private set

    // --- engine ---
    var engineRunning by mutableStateOf(false); private set
    var recording by mutableStateOf(false); private set
    var sampleRate by mutableStateOf(0); private set

    // --- meters, polled off the audio thread ---
    var voiceLevel by mutableStateOf(0f); private set
    var outputLevel by mutableStateOf(0f); private set
    var pitchMidi by mutableStateOf(-1f); private set
    var centsOff by mutableStateOf(0f); private set
    var latencyMs by mutableStateOf(0f); private set
    var alignMs by mutableStateOf(0f); private set
    var outputLatencyMs by mutableStateOf(0f); private set
    var positionMs by mutableStateOf(0.0); private set
    var playing by mutableStateOf(false); private set
    var countInBeatsLeft by mutableStateOf(0); private set
    var xruns by mutableStateOf(0); private set
    var underruns by mutableStateOf(0); private set

    /**
     * Song time the listener is hearing right now. Lyrics and the pitch lane
     * are drawn against this, not against the playhead: what the mixer wrote
     * this instant is still queued in the output buffer.
     */
    val heardMs: Double get() = positionMs - outputLatencyMs

    /**
     * Song length in ms. The container knows it as soon as the file opens, so
     * the scrub bar works on a song that has not been analysed yet.
     */
    val trackDurationMs: Long
        get() {
            val fromDecoder =
                if (decoder.sampleRate > 0) decoder.totalFrames * 1000L / decoder.sampleRate else 0L
            return maxOf(fromDecoder, current?.durationMs ?: 0L)
        }

    // --- score ---
    var score by mutableStateOf(0f); private set
    var combo by mutableStateOf(0); private set
    var maxCombo by mutableStateOf(0); private set
    var perfect by mutableStateOf(0); private set
    var great by mutableStateOf(0); private set
    var good by mutableStateOf(0); private set
    var miss by mutableStateOf(0); private set
    var targetMidi by mutableStateOf(-1f); private set
    var liveAccuracy by mutableStateOf(0f); private set

    // --- settings ---
    var countInBeats by mutableStateOf(4)
    var preset by mutableStateOf(Presets.default.label)
    var lastTake by mutableStateOf<Take?>(null); private set

    /** Mirror of the native parameter values, so sliders have something to read. */
    private val params = mutableStateMapOf<Int, Float>()

    init {
        NativeAudio.create()
        syncParamsFromNative()
        applyPreset(Presets.default)

        songs = repo.loadSongs()
        takes = repo.loadTakes()

        viewModelScope.launch {
            while (true) {
                if (engineRunning) poll()
                // The pitch lane scrolls at screen rate; the library does not
                delay(if (screen == Screen.STAGE) 16L else 120L)
            }
        }
    }

    // ---------------------------------------------------------------- engine

    fun startEngine(): Boolean {
        if (engineRunning) return true
        if (!NativeAudio.start()) {
            message = "Não foi possível abrir o áudio. Conecte um fone com fio."
            return false
        }
        engineRunning = true
        sampleRate = NativeAudio.sampleRate()
        message = null
        return true
    }

    fun stopEngine() {
        if (recording) stopRecording()
        decoder.stop()
        NativeAudio.stop()
        engineRunning = false
        playing = false
    }

    private fun poll() {
        NativeAudio.transportState(transport)
        voiceLevel = transport[NativeAudio.T.VOICE_LEVEL]
        outputLevel = transport[NativeAudio.T.OUTPUT_LEVEL]
        pitchMidi = transport[NativeAudio.T.PITCH_MIDI]
        centsOff = transport[NativeAudio.T.CENTS_OFF]
        latencyMs = transport[NativeAudio.T.LATENCY_MS]
        alignMs = transport[NativeAudio.T.ALIGN_MS]
        outputLatencyMs = transport[NativeAudio.T.OUT_LATENCY_MS]
        positionMs = transport[NativeAudio.T.POSITION_MS].toDouble()
        countInBeatsLeft = transport[NativeAudio.T.COUNT_IN].toInt()
        xruns = transport[NativeAudio.T.XRUNS].toInt()
        underruns = transport[NativeAudio.T.UNDERRUNS].toInt()

        val nowPlaying = transport[NativeAudio.T.PLAYING] > 0.5f
        val finished = transport[NativeAudio.T.FINISHED] > 0.5f

        if (screen == Screen.STAGE) {
            NativeAudio.scoreState(scoreBuf)
            score = scoreBuf[NativeAudio.S.SCORE]
            combo = scoreBuf[NativeAudio.S.COMBO].toInt()
            maxCombo = scoreBuf[NativeAudio.S.MAX_COMBO].toInt()
            perfect = scoreBuf[NativeAudio.S.PERFECT].toInt()
            great = scoreBuf[NativeAudio.S.GREAT].toInt()
            good = scoreBuf[NativeAudio.S.GOOD].toInt()
            miss = scoreBuf[NativeAudio.S.MISS].toInt()
            targetMidi = scoreBuf[NativeAudio.S.TARGET_MIDI]
            liveAccuracy = scoreBuf[NativeAudio.S.LIVE_ACCURACY]

            // The singer is answering what they heard, so their pitch belongs
            // where the playhead was a round trip ago, not where it is now
            if (nowPlaying) {
                trail.push(positionMs - alignMs, pitchMidi)
                trailVersion = trail.version
            }
        }

        if (playing && !nowPlaying && finished) onSongFinished()
        playing = nowPlaying
    }

    // ------------------------------------------------------------- transport

    fun openSong(song: Song) {
        if (!startEngine()) return
        current = song
        screen = Screen.STAGE
        trail.clear()
        trailVersion = trail.version

        if (!decoder.start(Uri.parse(song.uri))) {
            message = "Formato não suportado."
            screen = Screen.LIBRARY
            return
        }

        NativeAudio.setMelody(if (song.hasMelody) song.notes else null)
        NativeAudio.setScoringEnabled(song.hasMelody)
        NativeAudio.resetScore()

        // Lock the autotune to the key the analysis found, so snapping helps
        // instead of fighting the song
        if (song.analyzed && song.keyConfidence > 0.1f) {
            set(Param.KEY_ROOT, song.keyRoot.toFloat())
            set(
                Param.SCALE,
                (if (song.keyMode == 1) ScaleType.MINOR else ScaleType.MAJOR).ordinal.toFloat()
            )
        }
        resetMeters()
    }

    fun leaveStage() {
        if (recording) stopRecording()
        NativeAudio.trackPause()
        decoder.stop()
        screen = Screen.LIBRARY
        current = null
    }

    fun togglePlay() {
        if (playing || countInBeatsLeft > 0) {
            NativeAudio.trackPause()
            playing = false
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        val song = current ?: return
        viewModelScope.launch {
            // Starting on an empty ring costs the first bar of the song
            var waited = 0
            while (NativeAudio.trackBuffered() < sampleRate / 4 && waited < 2000) {
                delay(20)
                waited += 20
            }
            val bpm = if (song.bpm > 40f) song.bpm else 100f
            NativeAudio.trackPlay(bpm, countInBeats)
        }
    }

    fun restart() {
        decoder.seek(0)
        NativeAudio.resetScore()
        trail.clear()
        trailVersion = trail.version
        resetMeters()
        startPlayback()
    }

    fun seekTo(ms: Long) {
        if (current == null || decoder.sampleRate <= 0) return
        val last = maxOf(0L, decoder.totalFrames - 1)
        val frame = (ms * decoder.sampleRate / 1000L).coerceIn(0L, last)
        decoder.seek(frame)
        NativeAudio.resetScore()
        trail.clear()
        trailVersion = trail.version
    }

    private fun resetMeters() {
        score = 0f; combo = 0; maxCombo = 0
        perfect = 0; great = 0; good = 0; miss = 0
        positionMs = 0.0
    }

    private fun onSongFinished() {
        if (recording) stopRecording()
        screen = Screen.RESULT
        current?.let { song ->
            val better = maxOf(song.bestScore, score)
            updateSong(song.copyWith(bestScore = better, timesPlayed = song.timesPlayed + 1))
        }
    }

    // ------------------------------------------------------------- recording

    fun toggleRecording() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (!engineRunning) return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(repo.takesDir, "auravox-$stamp.wav")
        if (NativeAudio.startRecording(file.absolutePath)) {
            pendingTakePath = file.absolutePath
            recording = true
        } else {
            message = "Falha ao iniciar a gravação."
        }
    }

    private var pendingTakePath: String? = null

    private fun stopRecording() {
        NativeAudio.stopRecording()
        recording = false
        val path = pendingTakePath ?: return
        pendingTakePath = null

        val song = current ?: return
        val take = Take(
            id = UUID.randomUUID().toString(),
            songId = song.id,
            songTitle = song.title,
            path = path,
            score = score,
            maxCombo = maxCombo,
            durationMs = positionMs.toLong()
        )
        takes = listOf(take) + takes
        repo.saveTakes(takes)
        lastTake = take
    }

    fun shareIntent(take: Take): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, Uri.parse("file://${take.path}"))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun deleteTake(take: Take) {
        repo.deleteTake(take)
        takes = takes.filterNot { it.id == take.id }
        repo.saveTakes(takes)
    }

    // --------------------------------------------------------------- library

    fun importSong(uri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val name = displayName(uri)
        val song = Song(
            id = UUID.randomUUID().toString(),
            title = name.first,
            artist = name.second,
            uri = uri.toString(),
            durationMs = 0L
        )
        songs = songs + song
        repo.saveSongs(songs)
        analyze(song)
    }

    /** Re-runs the melody, key and tempo pass for a song already in the library. */
    fun analyze(song: Song) {
        if (analyzing != null) {
            message = "Uma análise já está em andamento."
            return
        }
        analyzing = song.id
        analyzeProgress = 0f

        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                SongAnalyzer.analyze(getApplication(), Uri.parse(song.uri)) { p ->
                    // Progress arrives on the analysis thread; state belongs to main
                    viewModelScope.launch(Dispatchers.Main) { analyzeProgress = p }
                }
            }
            analyzing = null

            if (result == null) {
                message = "Não foi possível analisar \"${song.title}\"."
                return@launch
            }
            updateSong(
                Song(
                    id = song.id, title = song.title, artist = song.artist, uri = song.uri,
                    durationMs = result.durationMs,
                    keyRoot = result.keyRoot, keyMode = result.keyMode,
                    keyConfidence = result.keyConfidence, bpm = result.bpm,
                    notes = result.notes, lyrics = song.lyrics, addedAt = song.addedAt,
                    bestScore = song.bestScore, timesPlayed = song.timesPlayed,
                    analyzed = true
                )
            )
            message = "${song.title}: ${result.noteCount} notas, ${
                "%.0f".format(result.bpm)
            } BPM"
        }
    }

    fun attachLyrics(song: Song, uri: Uri) {
        val lines = repo.readLyrics(uri)
        if (lines.isEmpty()) {
            message = "Nenhuma linha com marcação de tempo nesse arquivo."
            return
        }
        updateSong(song.copyWith(lyrics = lines))
        message = "${lines.size} linhas de letra carregadas."
    }

    fun deleteSong(song: Song) {
        songs = songs.filterNot { it.id == song.id }
        repo.saveSongs(songs)
    }

    private fun updateSong(updated: Song) {
        songs = songs.map { if (it.id == updated.id) updated else it }
        repo.saveSongs(songs)
        if (current?.id == updated.id) current = updated
    }

    private fun displayName(uri: Uri): Pair<String, String> {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "Sem nome"
        val raw = runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: fallback

        val base = raw.substringBeforeLast('.')
        // "Artista - Título" is how most downloaded files are named
        val parts = base.split(" - ", limit = 2)
        return if (parts.size == 2) parts[1].trim() to parts[0].trim()
        else base.trim() to ""
    }

    fun showTakes() { screen = Screen.TAKES }
    fun backToLibrary() { screen = Screen.LIBRARY }
    fun backToStage() { screen = Screen.STAGE }
    fun clearMessage() { message = null }

    // ------------------------------------------------------------ parameters

    fun get(id: Int): Float = params[id] ?: 0f

    fun set(id: Int, value: Float) {
        params[id] = value
        NativeAudio.setParam(id, value)
    }

    fun applyPreset(p: Preset) {
        preset = p.label
        p.values.forEach { (id, v) -> set(id, v) }
    }

    fun scale(): ScaleType =
        ScaleType.entries[get(Param.SCALE).toInt().coerceIn(0, ScaleType.entries.size - 1)]

    private fun syncParamsFromNative() {
        val ids = listOf(
            Param.BYPASS, Param.INPUT_GAIN, Param.VOCAL_GAIN,
            Param.EQ_LOW, Param.EQ_MID, Param.EQ_HIGH, Param.GATE_THRESHOLD,
            Param.COMP_THRESHOLD, Param.COMP_RATIO, Param.COMP_MAKEUP,
            Param.COMP_ATTACK, Param.COMP_RELEASE, Param.DE_ESS,
            Param.PITCH_AMOUNT, Param.RETUNE_MS, Param.KEY_ROOT, Param.SCALE,
            Param.TRANSPOSE, Param.FORMANT,
            Param.HARMONY_MIX, Param.HARMONY_1, Param.HARMONY_2, Param.HARMONY_3,
            Param.HARMONY_VOICES, Param.HARMONY_SPREAD,
            Param.DOUBLER_MIX, Param.DOUBLER_SPREAD, Param.DOUBLER_DETUNE,
            Param.DELAY_MIX, Param.DELAY_TIME_MS, Param.DELAY_FEEDBACK, Param.DELAY_PING_PONG,
            Param.REVERB_MIX, Param.REVERB_SIZE, Param.REVERB_DAMP, Param.REVERB_PRE_DELAY,
            Param.VOCAL_WIDTH,
            Param.TRACK_GAIN, Param.TRACK_KEY_SHIFT, Param.TRACK_TEMPO,
            Param.TRACK_VOCAL_REMOVE, Param.TRACK_DUCK, Param.TRACK_WIDTH,
            Param.MASTER_GAIN, Param.LATENCY_TRIM_MS, Param.METRONOME_GAIN
        )
        ids.forEach { params[it] = NativeAudio.getParam(it) }
    }

    override fun onCleared() {
        stopEngine()
        NativeAudio.destroy()
        super.onCleared()
    }
}
