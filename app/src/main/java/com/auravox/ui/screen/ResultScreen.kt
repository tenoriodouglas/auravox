package com.auravox.ui.screen

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auravox.karaoke.gradeFor
import com.auravox.ui.Aura
import com.auravox.ui.KaraokeViewModel
import com.auravox.ui.widget.ScoreRing

@Composable
fun ResultScreen(vm: KaraokeViewModel) {
    val context = LocalContext.current
    val song = vm.current
    val grade = gradeFor(vm.score)

    Column(
        Modifier
            .fillMaxSize()
            .background(Aura.Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            song?.title ?: "",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            song?.artist.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = Aura.Dim
        )

        Text(
            grade,
            fontSize = 86.sp,
            color = Aura.accuracy(vm.score),
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(top = 12.dp)
        )

        ScoreRing(vm.score, "pontos", Modifier.padding(vertical = 16.dp), size = 150)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Tally("Perfeito", vm.perfect, Aura.Teal)
            Tally("Ótimo", vm.great, Color(0xFF7DE8A0))
            Tally("Bom", vm.good, Aura.Amber)
            Tally("Errou", vm.miss, Aura.Pink)
        }

        Text(
            "Maior sequência: ${vm.maxCombo}",
            style = MaterialTheme.typography.bodyMedium,
            color = Aura.Dim,
            modifier = Modifier.padding(top = 16.dp)
        )

        vm.lastTake?.let { take ->
            Text(
                if (take.layerCount > 1) "Gravação salva, ${take.layerCount} camadas"
                else "Gravação salva",
                style = MaterialTheme.typography.labelSmall,
                color = Aura.Teal,
                modifier = Modifier.padding(top = 20.dp)
            )
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { vm.playTake(take) }) { Text("Ouvir") }
                OutlinedButton(onClick = { vm.overdub(take) }) { Text("Nova camada") }
                OutlinedButton(onClick = { vm.exportTake(take) }) { Text("Exportar") }
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent.createChooser(vm.shareIntent(take), "Compartilhar")
                    )
                },
                modifier = Modifier.padding(top = 6.dp)
            ) { Text("Compartilhar") }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { vm.backToLibrary() },
                modifier = Modifier.weight(1f)
            ) { Text("Biblioteca") }

            Button(
                onClick = { vm.backToStage(); vm.restart() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Aura.Violet)
            ) { Text("Cantar de novo") }
        }
    }
}

@Composable
private fun Tally(label: String, value: Int, tint: Color) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Aura.Surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$value", style = MaterialTheme.typography.titleLarge, color = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Aura.Dim)
    }
}
