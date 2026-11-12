package com.auravox.ui.widget

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.auravox.ui.Aura
import com.auravox.ui.PitchTrail
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Scrolling piano roll: the melody to sing, and the line the singer is
 * actually producing, on the same time axis.
 *
 * Both are drawn against the moment the singer *heard*, not the playhead, so a
 * performance that is on time looks on time. This is the screen the whole app
 * exists for, and it is the thing a vocal effects app does not have.
 *
 * Everything that changes while singing arrives as a lambda and is read inside
 * the draw block. Taking those as plain values instead made the whole lane
 * recompose on every detector frame, which is layout and composition work for
 * a picture that only needed redrawing. For the same reason the path, the
 * strokes and the brushes are built in the cache block and reused: a Path
 * allocated inside a draw call is sixty allocations a second feeding the
 * collector exactly where a stutter is least welcome.
 */
@Composable
fun PitchLane(
    notes: FloatArray,
    midiRange: IntRange,
    trail: PitchTrail,
    heardMs: () -> Double,
    liveMidi: () -> Float,
    targetMidi: () -> Float,
    accuracy: () -> Float,
    trailVersion: () -> Int,
    windowMs: Float = 4500f,
    modifier: Modifier = Modifier
) {
    val path = remember { Path() }
    val pulse = remember { HitPulse() }

    Spacer(
        modifier.drawWithCache {
            // Size- and theme-dependent work, run once per layout rather than
            // once per frame
            val lo = midiRange.first.toFloat()
            val hi = midiRange.last.toFloat()
            val span = (hi - lo).coerceAtLeast(1f)
            val nowX = size.width * 0.32f
            val pxPerMs = size.width / windowMs

            val trailWidth = 4.dp.toPx()
            val trailStroke = Stroke(
                width = trailWidth, cap = StrokeCap.Round, join = StrokeJoin.Round
            )
            val glowStroke = Stroke(
                width = trailWidth * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round
            )
            // History fades out to the left instead of ending on a hard edge
            val trailBrush = Brush.horizontalGradient(
                0.0f to Color.Transparent,
                0.35f to Aura.Amber.copy(alpha = 0.55f),
                (nowX / size.width).coerceIn(0.1f, 0.95f) to Aura.Amber,
                1.0f to Aura.Amber
            )
            val staffWidth = 1.dp.toPx()
            val cWidth = 1.5.dp.toPx()
            val dotRadius = 7.dp.toPx()
            val haloRadius = 20.dp.toPx()
            val ringMax = 34.dp.toPx()
            val ringWidth = 2.dp.toPx()
            val noteHeight = (size.height / span * 0.85f)
                .coerceIn(7.dp.toPx(), 26.dp.toPx())

            onDrawBehind {
                trailVersion()  // subscribes the draw phase, not composition
                val now = heardMs()
                val live = liveMidi()
                val target = targetMidi()
                val acc = accuracy()

                fun xFor(ms: Double) = nowX + ((ms - now) * pxPerMs).toFloat()
                fun yFor(midi: Float) = size.height * (1f - (midi - lo) / span)

                drawStaff(lo, hi, span, live, staffWidth, cWidth)

                // --- melody ---
                val fromMs = now - nowX / pxPerMs
                val toMs = now + (size.width - nowX) / pxPerMs

                for (i in 0 until notes.size / 3) {
                    val start = notes[i * 3].toDouble()
                    val length = notes[i * 3 + 1]
                    val end = start + length
                    if (end < fromMs || start > toMs) continue

                    val midi = notes[i * 3 + 2]
                    val x0 = xFor(start)
                    val x1 = xFor(end)
                    val w = (x1 - x0).coerceAtLeast(3.dp.toPx())
                    val y = yFor(midi)
                    val top = y - noteHeight / 2f
                    val radius = CornerRadius(noteHeight / 2f)
                    val past = end < now
                    val active = start <= now && end >= now

                    drawRoundRect(
                        color = if (past) Aura.Violet.copy(alpha = 0.20f)
                                else Aura.Violet.copy(alpha = 0.50f),
                        topLeft = Offset(x0, top),
                        size = Size(w, noteHeight),
                        cornerRadius = radius
                    )

                    if (active) {
                        // A note that visibly fills as it is held reads as
                        // progress; recolouring the whole pill does not
                        val filled = (((now - start) / length).toFloat()).coerceIn(0f, 1f)
                        drawRoundRect(
                            color = Aura.Teal.copy(alpha = 0.22f),
                            topLeft = Offset(x0, y - noteHeight),
                            size = Size(w, noteHeight * 2f),
                            cornerRadius = CornerRadius(noteHeight)
                        )
                        drawRoundRect(
                            color = Aura.Teal,
                            topLeft = Offset(x0, top),
                            size = Size((w * filled).coerceAtLeast(2.dp.toPx()), noteHeight),
                            cornerRadius = radius
                        )
                    }
                }

                // --- what the singer produced ---
                path.rewind()
                var open = false
                var lastX = 0f
                val breakWidth = size.width * 0.08f
                for (i in 0 until trail.count) {
                    val m = trail.midiAt(i)
                    val t = trail.timeAt(i)
                    if (m <= 0f || t < fromMs) { open = false; continue }
                    val x = xFor(t)
                    val y = yFor(m.coerceIn(lo, hi))
                    // A gap in time means a breath: do not draw a line across it
                    if (!open || x - lastX > breakWidth) {
                        path.moveTo(x, y)
                        open = true
                    } else {
                        path.lineTo(x, y)
                    }
                    lastX = x
                }
                // Twice over: a wide translucent pass under a narrow solid one
                // is real bloom for the price of one extra draw call
                drawPath(path, trailBrush, alpha = 0.22f, style = glowStroke)
                drawPath(path, trailBrush, style = trailStroke)

                // --- now marker ---
                drawLine(
                    Color.White.copy(alpha = 0.22f),
                    Offset(nowX, 0f), Offset(nowX, size.height),
                    strokeWidth = staffWidth
                )

                if (live > 0f) {
                    val y = yFor(live.coerceIn(lo, hi))
                    val onNote = target > 0f && abs(live - target) < 1f
                    val colour = if (onNote) Aura.accuracy(acc) else Aura.Amber

                    val ring = pulse.advance(now, onNote)
                    if (ring > 0f) {
                        drawCircle(
                            colour.copy(alpha = 0.45f * (1f - ring)),
                            radius = dotRadius + ringMax * ring,
                            center = Offset(nowX, y),
                            style = Stroke(width = ringWidth)
                        )
                    }
                    drawCircle(colour.copy(alpha = 0.18f), haloRadius, Offset(nowX, y))
                    drawCircle(colour, dotRadius, Offset(nowX, y))
                }
            }
        }
    )
}

/**
 * One expanding ring per note landed.
 *
 * Kept as a plain object written from the draw phase: it is a visual flourish
 * with no bearing on layout, so putting it in snapshot state would buy a
 * recomposition per frame and nothing else. Time comes from the heard clock,
 * which already advances with the song.
 */
private class HitPulse {
    private var startedAt = -1.0
    private var wasOn = false

    /** 0 when nothing is firing, otherwise 0..1 across the ring's life. */
    fun advance(now: Double, onNote: Boolean): Float {
        if (onNote && !wasOn) startedAt = now
        wasOn = onNote
        if (startedAt < 0.0) return 0f
        val age = ((now - startedAt) / kLifeMs).toFloat()
        if (age < 0f || age > 1f) { startedAt = -1.0; return 0f }
        return age
    }

    private companion object { const val kLifeMs = 420.0 }
}

/**
 * A row per octave, and semitone rows only around where the voice is.
 *
 * Thirty faint lines across the lane read as noise, and at the alphas this
 * started with they measured about 1.1:1 against the background, so they were
 * nearly invisible noise. Marking the octaves clearly and detailing only the
 * neighbourhood of the current note says the same thing with less ink.
 */
private fun DrawScope.drawStaff(
    lo: Float, hi: Float, span: Float, live: Float,
    thin: Float, thick: Float
) {
    var midi = ceil(lo).toInt()
    val near = if (live > 0f) live else (lo + hi) * 0.5f
    while (midi <= hi) {
        val y = size.height * (1f - (midi - lo) / span)
        val isC = midi % 12 == 0
        if (isC) {
            drawLine(
                Color.White.copy(alpha = 0.18f),
                Offset(0f, y), Offset(size.width, y), strokeWidth = thick
            )
        } else if (abs(midi - near) <= 7f) {
            drawLine(
                Color.White.copy(alpha = 0.07f),
                Offset(0f, y), Offset(size.width, y), strokeWidth = thin
            )
        }
        ++midi
    }
}
