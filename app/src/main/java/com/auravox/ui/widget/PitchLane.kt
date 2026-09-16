package com.auravox.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.auravox.ui.Aura
import com.auravox.ui.PitchTrail
import kotlin.math.abs

/**
 * Scrolling piano roll: the melody to sing, and the line the singer is
 * actually producing, on the same time axis.
 *
 * Both are drawn against the moment the singer *heard*, not the playhead, so a
 * performance that is on time looks on time. This is the screen the whole app
 * exists for, and it is the thing a vocal effects app does not have.
 */
@Composable
fun PitchLane(
    notes: FloatArray,
    midiRange: IntRange,
    heardMs: Double,
    trail: PitchTrail,
    trailVersion: Int,
    liveMidi: Float,
    targetMidi: Float,
    accuracy: Float,
    windowMs: Float = 4500f,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") trailVersion  // subscribes the canvas to new samples

        val lo = midiRange.first.toFloat()
        val hi = midiRange.last.toFloat()
        val span = (hi - lo).coerceAtLeast(1f)
        val nowX = size.width * 0.32f
        val pxPerMs = size.width / windowMs

        fun xFor(ms: Double) = nowX + ((ms - heardMs) * pxPerMs).toFloat()
        fun yFor(midi: Float) = size.height * (1f - (midi - lo) / span)

        drawStaffLines(lo, hi, span)

        // --- melody ---
        val noteHeight = (size.height / span * 0.85f).coerceIn(6f, 26f)
        val fromMs = heardMs - nowX / pxPerMs
        val toMs = heardMs + (size.width - nowX) / pxPerMs

        for (i in 0 until notes.size / 3) {
            val start = notes[i * 3].toDouble()
            val end = start + notes[i * 3 + 1]
            if (end < fromMs || start > toMs) continue

            val midi = notes[i * 3 + 2]
            val x0 = xFor(start)
            val x1 = xFor(end)
            val y = yFor(midi)
            val past = end < heardMs
            val active = start <= heardMs && end >= heardMs

            val color = when {
                active -> Aura.Teal
                past -> Aura.Violet.copy(alpha = 0.22f)
                else -> Aura.Violet.copy(alpha = 0.55f)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x0, y - noteHeight / 2f),
                size = Size((x1 - x0).coerceAtLeast(3f), noteHeight),
                cornerRadius = CornerRadius(noteHeight / 2f)
            )
            if (active) {
                drawRoundRect(
                    color = Aura.Teal.copy(alpha = 0.25f),
                    topLeft = Offset(x0, y - noteHeight),
                    size = Size((x1 - x0).coerceAtLeast(3f), noteHeight * 2f),
                    cornerRadius = CornerRadius(noteHeight)
                )
            }
        }

        // --- what the singer produced ---
        val path = Path()
        var open = false
        var lastX = 0f
        for (i in 0 until trail.count) {
            val m = trail.midiAt(i)
            val t = trail.timeAt(i)
            if (m <= 0f || t < fromMs) { open = false; continue }
            val x = xFor(t)
            val y = yFor(m)
            // A gap in time means a breath: do not draw a line across it
            if (!open || x - lastX > size.width * 0.08f) {
                path.moveTo(x, y)
                open = true
            } else {
                path.lineTo(x, y)
            }
            lastX = x
        }
        drawPath(path, Aura.Amber, style = Stroke(width = 4f))

        // --- now marker ---
        drawLine(
            Color.White.copy(alpha = 0.25f),
            Offset(nowX, 0f), Offset(nowX, size.height), strokeWidth = 2f
        )

        if (liveMidi > 0f) {
            val y = yFor(liveMidi.coerceIn(lo, hi))
            val hit = targetMidi > 0f && abs(liveMidi - targetMidi) < 1f
            val glow = if (hit) Aura.accuracy(accuracy) else Aura.Amber
            drawCircle(glow.copy(alpha = 0.22f), radius = 22f, center = Offset(nowX, y))
            drawCircle(glow, radius = 8f, center = Offset(nowX, y))
        }
    }
}

/** Faint row per semitone, brighter at every C, so height reads as pitch. */
private fun DrawScope.drawStaffLines(lo: Float, hi: Float, span: Float) {
    var midi = kotlin.math.ceil(lo).toInt()
    while (midi <= hi) {
        val y = size.height * (1f - (midi - lo) / span)
        val isC = midi % 12 == 0
        drawLine(
            Color.White.copy(alpha = if (isC) 0.10f else 0.035f),
            Offset(0f, y), Offset(size.width, y),
            strokeWidth = if (isC) 1.5f else 1f
        )
        ++midi
    }
}
