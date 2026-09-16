package com.auravox.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auravox.karaoke.Song
import com.auravox.ui.Aura
import com.auravox.ui.KaraokeViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: KaraokeViewModel, onNeedMic: (() -> Unit) -> Unit) {
    var lyricsTarget by remember { mutableStateOf<Song?>(null) }

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importSong(it) } }

    val pickLyrics = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val song = lyricsTarget
        if (uri != null && song != null) vm.attachLyrics(song, uri)
        lyricsTarget = null
    }

    Scaffold(
        containerColor = Aura.Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AuraVox", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Karaokê com afinação, letra e nota",
                            style = MaterialTheme.typography.labelSmall,
                            color = Aura.Dim
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.showTakes() }) {
                        Icon(Icons.Filled.LibraryMusic, contentDescription = "Gravações")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Aura.Background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickAudio.launch(arrayOf("audio/*")) },
                containerColor = Aura.Violet
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Importar música")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            UpdateBanner(vm)

            vm.analyzing?.let { id ->
                val name = vm.songs.firstOrNull { it.id == id }?.title ?: ""
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Analisando $name — melodia, tom e BPM",
                        style = MaterialTheme.typography.labelSmall,
                        color = Aura.Teal
                    )
                    LinearProgressIndicator(
                        progress = { vm.analyzeProgress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = Aura.Teal
                    )
                }
            }

            if (vm.songs.isEmpty()) {
                EmptyLibrary()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(vm.songs, key = { it.id }) { song ->
                        SongRow(
                            song = song,
                            onPlay = { onNeedMic { vm.openSong(song) } },
                            onAnalyze = { vm.analyze(song) },
                            onLyrics = {
                                lyricsTarget = song
                                pickLyrics.launch(arrayOf("*/*"))
                            },
                            onDelete = { vm.deleteSong(song) }
                        )
                    }
                }
            }
        }
    }
}

/** Offers the new build when CI has published one. */
@Composable
private fun UpdateBanner(vm: KaraokeViewModel) {
    val info = vm.update ?: return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Aura.Violet.copy(alpha = 0.22f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Versão ${info.versionName} disponível",
            style = MaterialTheme.typography.titleMedium,
            color = Aura.Teal
        )
        if (info.notes.isNotBlank()) {
            Text(
                info.notes,
                style = MaterialTheme.typography.labelSmall,
                color = Aura.Dim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (vm.updateBusy) {
            if (vm.updateProgress >= 0f) {
                LinearProgressIndicator(
                    progress = { vm.updateProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Aura.Teal
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Aura.Teal)
            }
            Text(
                "Baixando…",
                style = MaterialTheme.typography.labelSmall,
                color = Aura.Dim
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.installUpdate() },
                    colors = ButtonDefaults.buttonColors(containerColor = Aura.Violet)
                ) { Text("Atualizar") }
                TextButton(onClick = { vm.dismissUpdate() }) {
                    Text("Agora não", color = Aura.Dim)
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🎤", fontSize = 56.sp)
        Text(
            "Importe qualquer música do aparelho",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "O AuraVox extrai a melodia, descobre o tom e o andamento, " +
                "e monta o guia de afinação sozinho. Dá para tirar o vocal " +
                "original e cantar por cima.",
            style = MaterialTheme.typography.bodyMedium,
            color = Aura.Dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun SongRow(
    song: Song,
    onPlay: () -> Unit,
    onAnalyze: () -> Unit,
    onLyrics: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Aura.Surface)
            .clickable(onClick = onPlay)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (song.analyzed) Aura.Violet.copy(alpha = 0.25f) else Aura.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = if (song.analyzed) Aura.Teal else Aura.Dim
            )
        }

        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    if (song.artist.isNotEmpty()) append("${song.artist} · ")
                    if (song.analyzed) {
                        append(song.keyLabel)
                        if (song.bpm > 40f) append(" · ${song.bpm.roundToInt()} BPM")
                        append(" · ${song.noteCount} notas")
                        if (song.hasLyrics) append(" · letra")
                    } else {
                        append("aguardando análise")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = Aura.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (song.bestScore > 0f) {
                Text(
                    "Melhor: ${(song.bestScore * 100).roundToInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Aura.Amber
                )
            }
        }

        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Opções", tint = Aura.Dim)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Analisar de novo") },
                    leadingIcon = { Icon(Icons.Filled.GraphicEq, null) },
                    onClick = { menu = false; onAnalyze() }
                )
                DropdownMenuItem(
                    text = { Text("Carregar letra (.lrc)") },
                    leadingIcon = { Icon(Icons.Filled.Subtitles, null) },
                    onClick = { menu = false; onLyrics() }
                )
                DropdownMenuItem(
                    text = { Text("Remover") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
    }
}
