package com.auravox.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auravox.karaoke.LyricLine
import com.auravox.ui.Aura

/**
 * Three lines of lyric: the one being sung, and one either side for context.
 *
 * When the file carries word timings the highlight crawls across the line
 * instead of jumping at the start of it, which is the difference between
 * following a lyric and reading one.
 */
@Composable
fun LyricsView(
    lines: List<LyricLine>,
    positionMs: Long,
    modifier: Modifier = Modifier
) {
    if (lines.isEmpty()) {
        Text(
            text = "Sem letra sincronizada. Importe um .lrc para acompanhar.",
            style = MaterialTheme.typography.bodyMedium,
            color = Aura.Dim,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(16.dp)
        )
        return
    }

    val index = currentLineIndex(lines, positionMs)
    val current = lines.getOrNull(index)
    val previous = lines.getOrNull(index - 1)
    val next = lines.getOrNull(index + 1)

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = previous?.text ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = Aura.Dim.copy(alpha = 0.5f),
            maxLines = 1,
            textAlign = TextAlign.Center
        )

        if (current != null) {
            Text(
                text = highlighted(current, positionMs),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = "♪",
                fontSize = 24.sp,
                color = Aura.Dim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            text = next?.text ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = Aura.Dim.copy(alpha = 0.7f),
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/** Last line whose timestamp has passed, or -1 before the song starts singing. */
fun currentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var index = -1
    for (i in lines.indices) {
        if (positionMs >= lines[i].timeMs) index = i else break
    }
    return index
}

private fun highlighted(line: LyricLine, positionMs: Long) = buildAnnotatedString {
    if (line.words.isEmpty()) {
        append(line.text)
        return@buildAnnotatedString
    }
    val sung = line.wordAt(positionMs)
    for ((i, word) in line.words.withIndex()) {
        val style = if (i <= sung) {
            SpanStyle(color = Aura.Teal, fontWeight = FontWeight.Bold)
        } else {
            SpanStyle(color = Color.White.copy(alpha = 0.75f))
        }
        withStyle(style) { append(word.text) }
        if (i < line.words.lastIndex) append(" ")
    }
}
