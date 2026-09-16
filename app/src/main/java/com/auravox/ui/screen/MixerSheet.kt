package com.auravox.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auravox.audio.NOTE_NAMES
import com.auravox.audio.Param
import com.auravox.audio.Presets
import com.auravox.audio.ScaleType
import com.auravox.ui.Aura
import com.auravox.ui.KaraokeViewModel
import kotlin.math.roundToInt

/**
 * Everything adjustable, four tabs deep.
 *
 * The first tab is the voice, the second is the song, because the two things a
 * singer reaches for mid-session are a different effect and a different key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixerSheet(vm: KaraokeViewModel, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Voz", "Playback", "Efeitos", "Ajustes")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Aura.Surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = tab == i,
                        onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Aura.Violet.copy(alpha = 0.3f),
                            activeContentColor = Aura.Teal
                        )
                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }

            when (tab) {
                0 -> VoiceTab(vm)
                1 -> PlaybackTab(vm)
                2 -> EffectsTab(vm)
                else -> SettingsTab(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceTab(vm: KaraokeViewModel) {
    Header("Preset")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Presets.all.forEach { preset ->
            FilterChip(
                selected = vm.preset == preset.label,
                onClick = { vm.applyPreset(preset) },
                label = { Text("${preset.emoji} ${preset.label}") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Aura.Violet.copy(alpha = 0.35f),
                    selectedLabelColor = Aura.Teal
                )
            )
        }
    }

    Header("Afinação")
    ParamSlider(vm, Param.PITCH_AMOUNT, "Autotune", 0f, 1f) { "${(it * 100).roundToInt()}%" }
    ParamSlider(vm, Param.RETUNE_MS, "Velocidade", 1f, 200f) { "${it.roundToInt()} ms" }

    Header("Tom")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NOTE_NAMES.forEachIndexed { i, name ->
            FilterChip(
                selected = vm.get(Param.KEY_ROOT).roundToInt() == i,
                onClick = { vm.set(Param.KEY_ROOT, i.toFloat()) },
                label = { Text(name) }
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ScaleType.entries.forEach { scale ->
            FilterChip(
                selected = vm.scale() == scale,
                onClick = { vm.set(Param.SCALE, scale.ordinal.toFloat()) },
                label = { Text(scale.label) }
            )
        }
    }

    ParamSlider(vm, Param.TRANSPOSE, "Transpor voz", -12f, 12f, steps = 23) {
        "${if (it > 0) "+" else ""}${it.roundToInt()}"
    }
    ParamSlider(vm, Param.FORMANT, "Timbre", 0.7f, 1.4f) { "%.2f".format(it) }

    Header("Harmonia")
    ParamSlider(vm, Param.HARMONY_MIX, "Nível", 0f, 1f) { "${(it * 100).roundToInt()}%" }
    ParamSlider(vm, Param.HARMONY_VOICES, "Vozes", 1f, 3f, steps = 1) { it.roundToInt().toString() }
    ParamSlider(vm, Param.HARMONY_1, "Voz 1", -7f, 7f, steps = 13) { degree(it) }
    ParamSlider(vm, Param.HARMONY_2, "Voz 2", -7f, 7f, steps = 13) { degree(it) }
    ParamSlider(vm, Param.HARMONY_3, "Voz 3", -7f, 7f, steps = 13) { degree(it) }
    ParamSlider(vm, Param.HARMONY_SPREAD, "Abertura", 0f, 1f) { "${(it * 100).roundToInt()}%" }

    Header("Vocoder")
    Note(
        "A voz vira o filtro e outra coisa vira o som. Com portadora na base, " +
            "é talkbox: a música fala a letra."
    )
    ParamSlider(vm, Param.VOCODER_MIX, "Quantidade", 0f, 1f) { "${(it * 100).roundToInt()}%" }
    ParamSlider(vm, Param.VOCODER_CARRIER, "Sintetizador ↔ base", 0f, 1f) {
        when {
            it < 0.15f -> "sintetizador"
            it > 0.85f -> "base"
            else -> "mistura"
        }
    }
    ParamSlider(vm, Param.VOCODER_SIBILANCE, "Consoantes", 0f, 1f) {
        "${(it * 100).roundToInt()}%"
    }

    Header("Doubler")
    ParamSlider(vm, Param.DOUBLER_MIX, "Nível", 0f, 1f) { "${(it * 100).roundToInt()}%" }
    ParamSlider(vm, Param.DOUBLER_DETUNE, "Variação", 0f, 1f) { "${(it * 100).roundToInt()}%" }
    ParamSlider(vm, Param.DOUBLER_SPREAD, "Abertura", 0f, 1f) { "${(it * 100).roundToInt()}%" }

    Header("Saída da voz")
    ParamSlider(vm, Param.VOCAL_GAIN, "Volume", 0f, 2f) { "%.2f".format(it) }
    ParamSlider(vm, Param.VOCAL_WIDTH, "Largura", 0f, 2f) { "%.2f".format(it) }
}

@Composable
private fun PlaybackTab(vm: KaraokeViewModel) {
    Header("Música")
    ParamSlider(vm, Param.TRACK_GAIN, "Volume", 0f, 1.5f) { "%.2f".format(it) }
    ParamSlider(vm, Param.TRACK_KEY_SHIFT, "Mudar o tom", -12f, 12f, steps = 23) {
        "${if (it > 0) "+" else ""}${it.roundToInt()} semitons"
    }
    ParamSlider(vm, Param.TRACK_TEMPO, "Andamento", 0.5f, 1.5f) { "${(it * 100).roundToInt()}%" }

    Header("Tirar o vocal original")
    Note(
        "Cancela o que estiver no centro da mixagem, só na faixa da voz. " +
            "Grave e pratos continuam inteiros."
    )
    ParamSlider(vm, Param.TRACK_VOCAL_REMOVE, "Remoção", 0f, 1f) {
        "${(it * 100).roundToInt()}%"
    }

    Header("Estudo")
    Note("Marque A e B no transporte para repetir um trecho. Baixe o andamento até acertar e volte subindo.")

    Header("Espaço")
    ParamSlider(vm, Param.TRACK_DUCK, "Abaixar quando eu cantar", 0f, 1f) {
        "${(it * 100).roundToInt()}%"
    }
    ParamSlider(vm, Param.TRACK_WIDTH, "Largura", 0f, 2f) { "%.2f".format(it) }
}

@Composable
private fun EffectsTab(vm: KaraokeViewModel) {
    Header("Reverb")
    ParamSlider(vm, Param.REVERB_MIX, "Nível", 0f, 0.8f) { "${(it * 100).roundToInt()}%" }
    ParamSlider(vm, Param.REVERB_SIZE, "Tamanho", 0f, 1f) { "${(it * 100).roundToInt()}%" }
    ParamSlider(vm, Param.REVERB_DAMP, "Abafamento", 0f, 1f) { "${(it * 100).roundToInt()}%" }
    ParamSlider(vm, Param.REVERB_PRE_DELAY, "Pre-delay", 0f, 100f) { "${it.roundToInt()} ms" }

    Header("Delay")
    ParamSlider(vm, Param.DELAY_MIX, "Nível", 0f, 0.8f) { "${(it * 100).roundToInt()}%" }
    ParamSlider(vm, Param.DELAY_TIME_MS, "Tempo", 40f, 800f) { "${it.roundToInt()} ms" }
    ParamSlider(vm, Param.DELAY_FEEDBACK, "Repetições", 0f, 0.85f) { "${(it * 100).roundToInt()}%" }
    ParamSlider(vm, Param.DELAY_PING_PONG, "Ping-pong", 0f, 1f) { "${(it * 100).roundToInt()}%" }

    Header("Equalizador")
    ParamSlider(vm, Param.EQ_LOW, "Graves", -12f, 12f) { "${it.roundToInt()} dB" }
    ParamSlider(vm, Param.EQ_MID, "Presença", -12f, 12f) { "${it.roundToInt()} dB" }
    ParamSlider(vm, Param.EQ_HIGH, "Brilho", -12f, 12f) { "${it.roundToInt()} dB" }

    Header("Dinâmica")
    ParamSlider(vm, Param.COMP_THRESHOLD, "Compressor", -48f, 0f) { "${it.roundToInt()} dB" }
    ParamSlider(vm, Param.COMP_RATIO, "Proporção", 1f, 12f) { "%.1f:1".format(it) }
    ParamSlider(vm, Param.COMP_MAKEUP, "Ganho", 0f, 18f) { "${it.roundToInt()} dB" }
    ParamSlider(vm, Param.DE_ESS, "De-esser", 0f, 1f) { "${(it * 100).roundToInt()}%" }
    ParamSlider(vm, Param.GATE_THRESHOLD, "Gate", -80f, -20f) { "${it.roundToInt()} dB" }
}

@Composable
private fun SettingsTab(vm: KaraokeViewModel) {
    Header("Sincronismo da gravação")
    Note(
        "O AuraVox já desconta a latência medida (${vm.alignMs.roundToInt()} ms) " +
            "ao gravar e ao pontuar. Use o ajuste fino se a sua voz ainda sair " +
            "adiantada ou atrasada na gravação."
    )
    ParamSlider(vm, Param.LATENCY_TRIM_MS, "Ajuste fino", -150f, 150f) {
        "${if (it > 0) "+" else ""}${it.roundToInt()} ms"
    }

    Header("Contagem")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(0, 2, 4, 8).forEach { beats ->
            FilterChip(
                selected = vm.countInBeats == beats,
                onClick = { vm.countInBeats = beats },
                label = { Text(if (beats == 0) "sem" else "$beats") }
            )
        }
    }
    ParamSlider(vm, Param.METRONOME_GAIN, "Volume do clique", 0f, 1f) {
        "${(it * 100).roundToInt()}%"
    }

    Header("Geral")
    ParamSlider(vm, Param.MONITOR_VOICE, "Ouvir a própria voz", 0f, 1f) {
        if (it > 0.5f) "ligado" else "desligado"
    }
    ParamSlider(vm, Param.MASTER_GAIN, "Volume geral", 0f, 1.5f) { "%.2f".format(it) }
    ParamSlider(vm, Param.INPUT_GAIN, "Ganho do microfone", 0f, 4f) { "%.2f".format(it) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Voz sem efeito", style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = vm.get(Param.BYPASS) > 0.5f,
            onCheckedChange = { vm.set(Param.BYPASS, if (it) 1f else 0f) },
            colors = SwitchDefaults.colors(checkedTrackColor = Aura.Violet)
        )
    }

    Note(
        "Taxa ${vm.sampleRate} Hz · latência ${vm.latencyMs.roundToInt()} ms · " +
            "falhas ${vm.xruns + vm.underruns}"
    )
}

// --------------------------------------------------------------- primitives

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Aura.Teal,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Aura.Dim)
}

@Composable
private fun ParamSlider(
    vm: KaraokeViewModel,
    id: Int,
    label: String,
    min: Float,
    max: Float,
    steps: Int = 0,
    format: (Float) -> String
) {
    val value = vm.get(id).coerceIn(min, max)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(format(value), style = MaterialTheme.typography.labelSmall, color = Aura.Teal)
        }
        Slider(
            value = value,
            onValueChange = { vm.set(id, it) },
            valueRange = min..max,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Aura.Teal,
                activeTrackColor = Aura.Violet,
                inactiveTrackColor = Aura.SurfaceHigh
            )
        )
    }
}

/** Harmony intervals read as scale degrees, not as raw step counts. */
private fun degree(steps: Float): String = when (steps.roundToInt()) {
    0 -> "uníssono"
    2 -> "3ª acima"
    4 -> "5ª acima"
    7 -> "8ª acima"
    -3 -> "5ª abaixo"
    -7 -> "8ª abaixo"
    else -> {
        val n = steps.roundToInt()
        if (n > 0) "+$n graus" else "$n graus"
    }
}
