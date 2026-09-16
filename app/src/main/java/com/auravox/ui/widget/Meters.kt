package com.auravox.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auravox.audio.midiName
import com.auravox.ui.Aura
import kotlin.math.abs
import kotlin.math.roundToInt

/** Horizontal input meter. Turns pink as it approaches clipping. */
@Composable
fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(targetValue = level.coerceIn(0f, 1f), label = "level")
    Box(
        modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Aura.SurfaceHigh)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(shown)
                .background(
                    Brush.horizontalGradient(
                        listOf(Aura.Teal, Aura.Amber, Aura.Pink)
                    )
                )
        )
    }
}

/**
 * Tuner: the note being sung and how far off it is, in cents.
 *
 * Shown even when scoring is off, because it is the fastest way for a singer
 * to find out the app is hearing them at all.
 */
@Composable
fun Tuner(midi: Float, cents: Float, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = if (midi > 0f) midiName(midi.roundToInt()) else "--",
            fontSize = 22.sp,
            color = if (abs(cents) < 15f && midi > 0f) Aura.Teal else Color.White
        )
        Canvas(Modifier.width(96.dp).height(14.dp)) {
            val mid = size.width / 2f
            drawLine(
                Color.White.copy(alpha = 0.15f),
                Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f),
                strokeWidth = 2f
            )
            drawLine(
                Color.White.copy(alpha = 0.4f),
                Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 2f
            )
            if (midi > 0f) {
                val x = mid + (cents.coerceIn(-50f, 50f) / 50f) * mid
                drawCircle(
                    if (abs(cents) < 15f) Aura.Teal else Aura.Amber,
                    radius = 5f, center = Offset(x, size.height / 2f)
                )
            }
        }
    }
}

/** Circular score readout, 0 to 100. */
@Composable
fun ScoreRing(score: Float, label: String, modifier: Modifier = Modifier, size: Int = 120) {
    val shown by animateFloatAsState(targetValue = score.coerceIn(0f, 1f), label = "score")
    Box(modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.09f
            val inset = stroke / 2f
            drawArc(
                color = Aura.SurfaceHigh,
                startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(Aura.Violet, Aura.Teal, Aura.Amber, Aura.Violet)),
                startAngle = 135f, sweepAngle = 270f * shown, useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = (shown * 100f).roundToInt().toString(),
                fontSize = (size * 0.28f).sp,
                color = Color.White,
                style = MaterialTheme.typography.displayLarge
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = Aura.Dim)
        }
    }
}

/** Small key-value chip used along the status strip. */
@Composable
fun StatChip(label: String, value: String, tint: Color = Aura.Dim) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Aura.Surface)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Aura.Dim)
    }
}

/** Combo counter. Hidden until a streak is actually worth showing. */
@Composable
fun ComboBadge(combo: Int, modifier: Modifier = Modifier) {
    if (combo < 3) return
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Aura.Violet.copy(alpha = 0.22f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("🔥", fontSize = 14.sp)
        Text(
            "$combo",
            style = MaterialTheme.typography.titleMedium,
            color = Aura.Amber
        )
    }
}
