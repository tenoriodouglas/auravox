package com.auravox.ui.widget

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auravox.karaoke.LyricLine
import com.auravox.ui.Aura
import com.auravox.ui.AuraMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.yield

/**
 * The lyric, with the highlight crawling across the words as they are sung.
 *
 * The sweep runs in the draw phase against a position read through a lambda,
 * so a song playing recomposes nothing: only a change of line does, and that
 * happens a few dozen times in a song rather than sixty times a second.
 * Progress comes from the timestamps, never from how long a word is, so a held
 * syllable crawls and a quick one snaps.
 *
 * Nothing here draws into an offscreen layer. That is deliberate: a
 * compositing layer clips tall stacked marks, and in Portuguese that would
 * take the tops off ã, õ and â on every other line.
 */
@Composable
fun LyricsView(
    lines: List<LyricLine>,
    positionMs: () -> Long,
    modifier: Modifier = Modifier
) {
    if (lines.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "Sem letra sincronizada.",
                style = MaterialTheme.typography.bodyMedium,
                color = Aura.Dim,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val timing = remember(lines) { LyricTiming(lines) }
    val listState = rememberLazyListState()
    var activeLine by remember(lines) { mutableIntStateOf(-1) }

    // Sampled every frame, published only on a change, so the list recomposes
    // on a new line and not on a new millisecond
    LaunchedEffect(timing) {
        while (true) {
            withFrameNanos { }
            val next = timing.lineAt(positionMs())
            if (next != activeLine) activeLine = next
        }
    }

    // Scrolling is driven from a flow, not from composition: layout info is
    // documented as unsafe to read while composing
    LaunchedEffect(listState, timing) {
        snapshotFlow { activeLine }
            .distinctUntilChanged()
            .collect { index ->
                if (index >= 0) {
                    yield()  // let layout settle before measuring it
                    listState.centerItem(index)
                }
            }
    }

    val instant = rememberReducedMotion()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 96.dp)
    ) {
        itemsIndexed(lines, key = { i, line -> "$i:${line.timeMs}" }) { index, line ->
            LyricLineRow(
                text = line.text.ifBlank { "♪" },
                isActive = index == activeLine,
                sweep = { timing.sweepOf(index, positionMs()) },
                instant = instant
            )
        }
    }
}

/**
 * One line: a dim pass, then the sung part painted over it.
 *
 * The lit pass is clipped to the visual row the sweep has reached, so a lyric
 * that wraps lights up in reading order instead of all its rows at once.
 */
@Composable
private fun LyricLineRow(
    text: String,
    isActive: Boolean,
    sweep: () -> Float,
    instant: Boolean
) {
    val measurer = rememberTextMeasurer()
    // Size and weight move together, so a line coming alive grows into focus
    val lit by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = AuraMotion.defaultEffects(),
        label = "lyricLit"
    )

    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .drawWithCache {
                val style = if (isActive) kActiveStyle else kIdleStyle
                val measured = measurer.measure(
                    AnnotatedString(text),
                    style = style,
                    constraints = Constraints(maxWidth = size.width.toInt().coerceAtLeast(1))
                )
                val width = size.width

                onDrawBehind {
                    val base = Color.White.copy(alpha = 0.26f + 0.12f * lit)
                    drawText(measured, color = base)
                    if (!isActive) return@onDrawBehind

                    val p = sweep().coerceIn(0f, 1f)
                    if (p <= 0f) return@onDrawBehind

                    val rows = measured.lineCount
                    val cursor = p * rows
                    val onRow = cursor.toInt().coerceAtMost(rows - 1)
                    val within = cursor - onRow

                    for (row in 0..onRow) {
                        val top = measured.getLineTop(row)
                        val bottom = measured.getLineBottom(row)
                        val left = measured.getLineLeft(row)
                        val right = measured.getLineRight(row)
                        val edge = if (row < onRow) right else left + (right - left) * within

                        if (instant) {
                            // Reduced motion: a hard cut, no gradient
                            clipRect(0f, top, edge, bottom) {
                                drawText(measured, color = Aura.Teal)
                            }
                        } else {
                            clipRect(0f, top, right, bottom) {
                                drawText(measured, brush = sweepBrush(edge, width))
                            }
                        }
                    }
                }
            }
    )
}

/** Soft leading edge, so a word lights up rather than switching on. */
private fun sweepBrush(edge: Float, width: Float): Brush {
    val w = width.coerceAtLeast(1f)
    val lo = ((edge - w * 0.05f) / w).coerceIn(0f, 1f)
    val hi = ((edge + w * 0.02f) / w).coerceIn(lo + 0.0001f, 1f)
    return Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to Aura.Teal,
            lo to Aura.Teal,
            hi to Color.Transparent,
            1f to Color.Transparent
        ),
        startX = 0f,
        endX = w
    )
}

/** True when the system asks for no animation, in which case the sweep snaps. */
@Composable
private fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
        }.getOrDefault(1.0f) == 0.0f
    }
}

private val kActiveStyle = TextStyle(
    fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp,
    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
)

private val kIdleStyle = TextStyle(
    fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp,
    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
)

/**
 * Line and word end times, worked out once.
 *
 * A word ends where the next begins; the last word of a line ends at the next
 * line, capped, so a final syllable with nothing after it does not sweep on
 * forever.
 */
private class LyricTiming(private val lines: List<LyricLine>) {

    private val lineEnd = LongArray(lines.size)

    init {
        for (i in lines.indices) {
            val own = lines[i].timeMs
            val next = lines.getOrNull(i + 1)?.timeMs
            lineEnd[i] = if (next != null && next > own) minOf(next, own + kMaxLine)
                         else own + kHold
        }
    }

    /** Last line whose timestamp has passed, or -1 before the song sings. */
    fun lineAt(positionMs: Long): Int {
        var index = -1
        for (i in lines.indices) {
            if (positionMs >= lines[i].timeMs) index = i else break
        }
        return index
    }

    /** How far through line [index] the song is, 0..1. */
    fun sweepOf(index: Int, positionMs: Long): Float {
        val line = lines.getOrNull(index) ?: return 0f
        if (positionMs < line.timeMs) return 0f

        val words = line.words
        if (words.isNotEmpty()) {
            val last = words.size - 1
            var i = last
            for (j in words.indices) {
                if (positionMs < words[j].timeMs) { i = j - 1; break }
                i = j
            }
            if (i < 0) return 0f
            val start = words[i].timeMs
            val end = if (i < last) words[i + 1].timeMs else lineEnd[index]
            val within =
                if (end > start) ((positionMs - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                else 1f
            return ((i + within) / words.size).coerceIn(0f, 1f)
        }

        val span = lineEnd[index] - line.timeMs
        if (span <= 0L) return 1f
        return ((positionMs - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
    }

    private companion object {
        const val kHold = 2600L     // a trailing line with nothing after it
        const val kMaxLine = 9000L  // an instrumental gap is not a long word
    }
}

/** Centres an item: there is no overload for it, and negative offsets are undocumented. */
private suspend fun LazyListState.centerItem(index: Int) {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (info == null) {
        scrollToItem(index)
        return
    }
    val viewport = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    animateScrollBy((info.offset + info.size / 2f) - viewport, AuraMotion.slowSpatial())
}
