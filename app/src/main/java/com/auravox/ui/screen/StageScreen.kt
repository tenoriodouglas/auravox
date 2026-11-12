package com.auravox.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auravox.audio.Param
import com.auravox.ui.Aura
import com.auravox.ui.AuraMotion
import com.auravox.ui.KaraokeViewModel
import com.auravox.ui.widget.Aurora
import com.auravox.ui.widget.ComboBadge
import com.auravox.ui.widget.LevelMeter
import com.auravox.ui.widget.LyricsView
import com.auravox.ui.widget.PitchLane
import com.auravox.ui.widget.StatChip
import com.auravox.ui.widget.Tuner
import kotlin.math.roundToInt

/**
 * The screen the app exists for.
 *
 * Three planes, each moving at its own rate, which is where the sense of depth
 * comes from: the light behind, lit by the song's own cover and breathing with
 * the voice; the pitch lane and the lyric, which are the only things carrying
 * information; and the transport floating over both.
 *
 * The lane, the lyric and the light read everything that moves inside their
 * draw blocks, so none of them recomposes while a song plays. The transport
 * cannot do that — a slider's position and a clock's digits are composition,
 * not drawing — so each thing in it that reads a moving value sits in its own
 * small composable instead. Compose invalidates the function that read the
 * state, so the buttons, the loop bar and the labels around them stay put
 * while only the two or three readers refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StageScreen(vm: KaraokeViewModel) {
    val song = vm.current ?: return
    val range = remember(song.id) { song.midiRange() }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val listening = vm.stageMode == KaraokeViewModel.StageMode.PLAYBACK

    // Colour crossfades when a new song's cover is read, so the stage settles
    // into the song rather than snapping to it
    val primary by animateColorAsState(
        Color(vm.palette.primary), AuraMotion.slowSpatial(), label = "auraPrimary"
    )
    val secondary by animateColorAsState(
        Color(vm.palette.secondary), AuraMotion.slowSpatial(), label = "auraSecondary"
    )

    Box(Modifier.fillMaxSize().background(Aura.Background)) {

        // --- plane 0: the light ---
        Aurora(
            level = { vm.voiceLevel },
            primary = primary,
            secondary = secondary,
            intensity = if (listening) 0.45f else 1f,
            modifier = Modifier.fillMaxSize()
        )

        Scaffold(
            containerColor = Color.Transparent,
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
                                    if (song.separated) append(" · base separada")
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->

            // --- plane 1: what carries information ---
            Column(Modifier.padding(padding).fillMaxSize()) {

                ScoreHeader(vm)

                Box(Modifier.weight(1.15f).fillMaxWidth()) {
                    PitchLane(
                        notes = song.notes,
                        midiRange = range,
                        trail = vm.trail,
                        heardMs = { vm.heardMs },
                        liveMidi = { if (listening) -1f else vm.pitchMidi },
                        targetMidi = { vm.targetMidi },
                        accuracy = { vm.liveAccuracy },
                        trailVersion = { vm.trailVersion },
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
                                fontSize = 86.sp,
                                color = Aura.Teal,
                                style = MaterialTheme.typography.displayLarge
                            )
                        }
                    }
                }

                LyricsView(
                    lines = song.lyrics,
                    positionMs = { vm.heardMs.toLong() },
                    modifier = Modifier.weight(1f)
                )

                // --- plane 2: the transport, over the other two ---
                GlassPanel {
                    Transport(vm, listening, scrubbing) { scrubbing = it }
                }
            }
        }
    }
}

/**
 * A panel that reads as glass without blurring anything.
 *
 * Modifier.blur does nothing at all below API 31 and is expensive where it
 * works, so this is the fallback the research called for, built as the only
 * version: a translucent fill, a lit hairline along the top edge, and a
 * gradient that makes the surface look like it has thickness. On a near-black
 * background it is indistinguishable from the real thing, and it costs two
 * fills on every device.
 */
@Composable
private fun GlassPanel(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(
                Brush.verticalGradient(
                    0f to Aura.SurfaceHigh.copy(alpha = 0.92f),
                    0.35f to Aura.Surface.copy(alpha = 0.96f),
                    1f to Aura.Surface
                )
            )
    ) {
        // The lit edge is what sells it: a surface with no highlight reads as
        // a hole rather than as a pane sitting on top
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = 0.22f),
                        1f to Color.Transparent
                    )
                )
        )
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { content() }
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
                fontSize = 38.sp,
                color = Color.White,
                style = MaterialTheme.typography.displayLarge
            )
            Text(
                " pts",
                style = MaterialTheme.typography.labelSmall,
                color = Aura.Dim,
                modifier = Modifier.padding(bottom = 7.dp)
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

/**
 * The position: a slider and two clocks.
 *
 * Its own composable because it is the only part of the transport that reads
 * the playhead. Reading it where the buttons are would recompose them, the
 * loop bar and every label beside them sixty times a second for a thumb that
 * moved a pixel.
 */
@Composable
private fun Scrubber(
    vm: KaraokeViewModel,
    scrubbing: Float?,
    onScrub: (Float?) -> Unit
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
}

/** The input level, on its own for the same reason as the scrubber. */
@Composable
private fun VoiceMeter(vm: KaraokeViewModel) {
    LevelMeter(vm.voiceLevel, Modifier.fillMaxWidth())
}

/** Latency and dropout count, which change rarely but are read from the same poll. */
@Composable
private fun Health(vm: KaraokeViewModel) {
    val glitches = vm.xruns + vm.underruns
    StatChip("latência", "${vm.latencyMs.roundToInt()} ms")
    StatChip("falhas", "$glitches", if (glitches > 0) Aura.Pink else Aura.Dim)
}

@Composable
private fun Transport(
    vm: KaraokeViewModel,
    listening: Boolean,
    scrubbing: Float?,
    onScrub: (Float?) -> Unit
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Scrubber(vm, scrubbing, onScrub)

        LoopBar(vm)

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Voz", style = MaterialTheme.typography.labelSmall, color = Aura.Dim)
                VoiceMeter(vm)
            }
            Health(vm)
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
                Modifier.size(68.dp).clip(CircleShape).background(
                    Brush.verticalGradient(
                        0f to Aura.Violet,
                        1f to Aura.Violet.copy(alpha = 0.78f)
                    )
                ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = { vm.togglePlay() }, modifier = Modifier.size(68.dp)) {
                    Icon(
                        if (vm.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (vm.playing) "Pausar" else "Tocar",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            if (listening) {
                // Nothing to record while listening back; the slot stays so the
                // play button does not jump sideways between modes
                Box(Modifier.size(54.dp))
            } else {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (vm.recording) Aura.Pink else Aura.SurfaceHigh),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = { vm.toggleRecording() }, modifier = Modifier.size(54.dp)) {
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
