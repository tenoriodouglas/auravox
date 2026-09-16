package com.auravox.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auravox.audio.Param
import com.auravox.ui.Aura
import com.auravox.ui.KaraokeViewModel
import com.auravox.ui.widget.ComboBadge
import com.auravox.ui.widget.LevelMeter
import com.auravox.ui.widget.LyricsView
import com.auravox.ui.widget.PitchLane
import com.auravox.ui.widget.StatChip
import com.auravox.ui.widget.Tuner
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StageScreen(vm: KaraokeViewModel) {
    val song = vm.current ?: return
    val range = remember(song.id) { song.midiRange() }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val listening = vm.stageMode == KaraokeViewModel.StageMode.PLAYBACK

    Scaffold(
        containerColor = Aura.Background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.leaveStage() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                title = {
                    Column {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            buildString {
                                append(
                                    when (vm.stageMode) {
                                        KaraokeViewModel.StageMode.OVERDUB ->
                                            "camada ${(vm.activeTake?.layerCount ?: 0) + 1} · "
                                        KaraokeViewModel.StageMode.PLAYBACK -> "ouvindo · "
                                        else -> ""
                                    }
                                )
                                append(song.keyLabel)
                                val shift = vm.get(Param.TRACK_KEY_SHIFT).roundToInt()
                                if (shift != 0) append("  ${if (shift > 0) "+" else ""}$shift")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (vm.stageMode == KaraokeViewModel.StageMode.SING)
                                Aura.Dim else Aura.Teal
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.toggleMonitor() }) {
                        Icon(
                            if (vm.monitorOn) Icons.Filled.Headphones else Icons.Filled.HeadsetOff,
                            contentDescription = "Monitor",
                            tint = if (vm.monitorOn) Aura.Teal else Aura.Dim
                        )
                    }
                    IconButton(onClick = { vm.showMixer = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Mixer", tint = Aura.Teal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Aura.Background)
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            ScoreHeader(vm)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                PitchLane(
                    notes = song.notes,
                    midiRange = range,
                    heardMs = vm.heardMs,
                    trail = vm.trail,
                    trailVersion = vm.trailVersion,
                    liveMidi = if (listening) -1f else vm.pitchMidi,
                    targetMidi = vm.targetMidi,
                    accuracy = vm.liveAccuracy,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)
                )

                if (!song.hasMelody) {
                    Text(
                        "Sem guia de melodia. Analise a música para pontuar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Aura.Dim,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                if (vm.countInBeatsLeft > 0) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${vm.countInBeatsLeft}",
                            fontSize = 72.sp,
                            color = Aura.Teal,
                            style = MaterialTheme.typography.displayLarge
                        )
                    }
                }
            }

            LyricsView(
                lines = song.lyrics,
                positionMs = vm.heardMs.toLong(),
                modifier = Modifier.padding(vertical = 10.dp)
            )

            Transport(vm, listening, scrubbing) { scrubbing = it }
        }
    }
}

@Composable
private fun ScoreHeader(vm: KaraokeViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${(vm.score * 100).roundToInt()}",
                fontSize = 34.sp,
                color = Color.White,
                style = MaterialTheme.typography.displayLarge
            )
            Text(
                " pts",
                style = MaterialTheme.typography.labelSmall,
                color = Aura.Dim,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        ComboBadge(vm.combo)
        Tuner(vm.pitchMidi, vm.centsOff)
    }
}

/**
 * Practice loop.
 *
 * A and B mark the passage; the transport jumps back to A every time the
 * playhead reaches B. Combined with the tempo control in the mixer, this is
 * how a hard run gets learned, and it is the part a vocal effects app has no
 * reason to have.
 */
@Composable
private fun LoopBar(vm: KaraokeViewModel) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextButton(onClick = { vm.markLoopStart() }) {
            Text("A", color = Aura.Teal, style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = { vm.markLoopEnd() }) {
            Text("B", color = Aura.Teal, style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = { vm.toggleLoop() }) {
            Icon(
                Icons.Filled.Repeat,
                contentDescription = "Repetir trecho",
                tint = if (vm.loopEnabled) Aura.Amber else Aura.Dim
            )
        }
        Text(
            if (vm.loopEndMs > vm.loopStartMs)
                "${clock(vm.loopStartMs)} – ${clock(vm.loopEndMs)}" else "trecho",
            style = MaterialTheme.typography.labelSmall,
            color = if (vm.loopEnabled) Aura.Amber else Aura.Dim,
            modifier = Modifier.weight(1f)
        )
        if (vm.loopEndMs > vm.loopStartMs) {
            TextButton(onClick = { vm.clearLoop() }) {
                Text("limpar", style = MaterialTheme.typography.labelSmall, color = Aura.Dim)
            }
        }
    }
}

@Composable
private fun Transport(
    vm: KaraokeViewModel,
    listening: Boolean,
    scrubbing: Float?,
    onScrub: (Float?) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(Aura.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val total = vm.trackDurationMs.coerceAtLeast(1L)
        val elapsed = (vm.heardMs - vm.sourceStartMs).coerceAtLeast(0.0)
        val fraction = scrubbing ?: (elapsed / total).toFloat().coerceIn(0f, 1f)

        Slider(
            value = fraction,
            onValueChange = { onScrub(it) },
            onValueChangeFinished = {
                scrubbing?.let { vm.seekTo((it * total + vm.sourceStartMs).toLong()) }
                onScrub(null)
            },
            colors = SliderDefaults.colors(
                thumbColor = Aura.Teal,
                activeTrackColor = Aura.Teal,
                inactiveTrackColor = Aura.SurfaceHigh
            )
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(elapsed.toLong()), style = MaterialTheme.typography.labelSmall, color = Aura.Dim)
            Text(clock(total), style = MaterialTheme.typography.labelSmall, color = Aura.Dim)
        }

        LoopBar(vm)

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Voz", style = MaterialTheme.typography.labelSmall, color = Aura.Dim)
                LevelMeter(vm.voiceLevel, Modifier.fillMaxWidth())
            }
            StatChip("latência", "${vm.latencyMs.roundToInt()} ms")
            StatChip(
                "falhas",
                "${vm.xruns + vm.underruns}",
                if (vm.xruns + vm.underruns > 0) Aura.Pink else Aura.Dim
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = { vm.restart() }) {
                Icon(Icons.Filled.Replay, contentDescription = "Recomeçar", tint = Aura.Dim)
            }

            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Aura.Violet),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = { vm.togglePlay() }, modifier = Modifier.size(64.dp)) {
                    Icon(
                        if (vm.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (vm.playing) "Pausar" else "Tocar",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            if (listening) {
                // Nothing to record while listening back; the slot stays so the
                // play button does not jump sideways between modes
                Box(Modifier.size(52.dp))
            } else {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (vm.recording) Aura.Pink else Aura.SurfaceHigh),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = { vm.toggleRecording() }, modifier = Modifier.size(52.dp)) {
                        Icon(
                            if (vm.recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                            contentDescription = "Gravar",
                            tint = if (vm.recording) Color.White else Aura.Pink
                        )
                    }
                }
            }
        }
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
