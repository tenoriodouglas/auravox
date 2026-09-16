package com.auravox.ui.screen

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auravox.karaoke.gradeFor
import com.auravox.ui.Aura
import com.auravox.ui.KaraokeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakesScreen(vm: KaraokeViewModel) {
    val context = LocalContext.current
    val stamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    Scaffold(
        containerColor = Aura.Background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.backToLibrary() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                title = { Text("Minhas gravações") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Aura.Background)
            )
        }
    ) { padding ->
        if (vm.takes.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Nada gravado ainda", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Toque no botão vermelho durante a música. A voz sai " +
                        "alinhada com a base, sem atraso.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Aura.Dim,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(vm.takes, key = { it.id }) { take ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Aura.Surface)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        gradeFor(take.score),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Aura.accuracy(take.score)
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            take.songTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${(take.score * 100).roundToInt()} pts · " +
                                "sequência ${take.maxCombo} · " +
                                stamp.format(Date(take.recordedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = Aura.Dim
                        )
                    }
                    IconButton(onClick = {
                        context.startActivity(
                            Intent.createChooser(vm.shareIntent(take), "Compartilhar")
                        )
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Compartilhar", tint = Aura.Teal)
                    }
                    IconButton(onClick = { vm.deleteTake(take) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Apagar", tint = Aura.Dim)
                    }
                }
            }
        }
    }
}
