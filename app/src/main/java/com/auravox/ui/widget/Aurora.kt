package com.auravox.ui.widget

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import kotlin.math.sin

/**
 * The light behind the stage, breathing with the voice.
 *
 * It is the one thing on screen that carries no information, so it is also the
 * only thing allowed to move freely. Everything about how it is built is about
 * keeping that freedom from costing frames:
 *
 *  - The level and the clock live in plain float state written from a
 *    frame-synced loop and read only inside the draw block, so a value
 *    changing sixty times a second redraws a rectangle and recomposes nothing.
 *  - Brushes and the shader are built in the cache block, not per frame.
 *  - No blur anywhere. Modifier.blur does nothing at all below API 31, and
 *    animating its radius is expensive where it does work, so the glow comes
 *    from overlapping soft gradients instead — which looks the same and costs
 *    a fill.
 *
 * Three tiers, because the shader API is API 33 and not 31 as the blur it
 * arrived alongside: a real shader where there is one, and layered radial
 * gradients everywhere else. The fallback is designed to look deliberate
 * rather than degraded, since a third of installs will only ever see it.
 */
@Composable
fun Aurora(
    level: () -> Float,
    primary: Color,
    secondary: Color,
    intensity: Float = 1f,
    modifier: Modifier = Modifier
) {
    if (intensity <= 0.01f) return

    val smoothed = remember { mutableFloatStateOf(0f) }
    val clock = remember { mutableFloatStateOf(0f) }

    // Asymmetric smoothing: quick to rise, slow to fall, which is how a room
    // responds to a voice and reads as musical rather than as noise
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos ->
                clock.floatValue = (nanos % 100_000_000_000L) / 1_000_000_000f
                val target = level().coerceIn(0f, 1f)
                val current = smoothed.floatValue
                val coefficient = if (target > current) 0.45f else 0.08f
                smoothed.floatValue = current + (target - current) * coefficient
            }
        }
    }

    if (Build.VERSION.SDK_INT >= 33) {
        ShaderAurora(smoothed::floatValue, clock::floatValue, primary, secondary, intensity, modifier)
    } else {
        GradientAurora(smoothed::floatValue, clock::floatValue, primary, secondary, intensity, modifier)
    }
}

private const val AURORA_AGSL = """
    uniform float2 uSize;
    uniform float  uTime;
    uniform float  uLevel;
    uniform float  uGain;
    // layout(color) is not decoration: setColorUniform refuses a uniform
    // without it and throws, which crashed the stage on every device new
    // enough to have shaders at all
    layout(color) uniform half4 uPrimary;
    layout(color) uniform half4 uSecondary;

    half4 main(float2 coord) {
        float2 uv = coord / uSize;
        float aspect = uSize.x / uSize.y;

        // Two slow lobes drifting against each other, so the light never
        // settles into a shape the eye can memorise
        float2 a = uv - float2(0.32 + 0.06 * sin(uTime * 0.21),
                               0.30 + 0.05 * sin(uTime * 0.17));
        float2 b = uv - float2(0.72 + 0.05 * sin(uTime * 0.13 + 2.0),
                               0.66 + 0.06 * sin(uTime * 0.23 + 1.0));
        a.x *= aspect;
        b.x *= aspect;

        float pulse = 0.45 + 0.55 * uLevel;
        float ra = smoothstep(0.78 * pulse, 0.0, length(a));
        float rb = smoothstep(0.66 * pulse, 0.0, length(b));

        half3 colour = uPrimary.rgb * half(ra) + uSecondary.rgb * half(rb);
        half  alpha  = half(min(1.0, (ra * 0.55 + rb * 0.45) * uGain));
        return half4(colour * alpha, alpha);   // AGSL wants premultiplied
    }
"""

@RequiresApi(33)
@Composable
private fun ShaderAurora(
    level: () -> Float,
    time: () -> Float,
    primary: Color,
    secondary: Color,
    intensity: Float,
    modifier: Modifier
) {
    val shader = remember { RuntimeShader(AURORA_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }

    Spacer(
        modifier.drawWithCache {
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setColorUniform("uPrimary", primary.toArgb())
            shader.setColorUniform("uSecondary", secondary.toArgb())
            shader.setFloatUniform("uGain", intensity)
            onDrawBehind {
                shader.setFloatUniform("uTime", time())
                shader.setFloatUniform("uLevel", level())
                drawRect(brush)
            }
        }
    )
}

/**
 * The same idea with radial gradients, for everything below API 33.
 *
 * A gradient's geometry is fixed when the brush is built, so the lobes cannot
 * drift here without rebuilding brushes every frame. They pulse in opacity and
 * radius instead, which is most of the effect for none of the allocation.
 */
@Composable
private fun GradientAurora(
    level: () -> Float,
    time: () -> Float,
    primary: Color,
    secondary: Color,
    intensity: Float,
    modifier: Modifier
) {
    Spacer(
        modifier.drawWithCache {
            val centreA = Offset(size.width * 0.32f, size.height * 0.30f)
            val centreB = Offset(size.width * 0.72f, size.height * 0.66f)
            val span = maxOf(size.width, size.height)

            // Built once per layout, at a few radii, and picked between at
            // draw time. Rebuilding a gradient per frame is the allocation
            // this whole file exists to avoid.
            val steps = 5
            val lobesA = List(steps) { i ->
                Brush.radialGradient(
                    0f to primary.copy(alpha = 0.55f),
                    0.55f to primary.copy(alpha = 0.16f),
                    1f to Color.Transparent,
                    center = centreA,
                    radius = span * (0.55f + 0.10f * i / (steps - 1f))
                )
            }
            val lobesB = List(steps) { i ->
                Brush.radialGradient(
                    0f to secondary.copy(alpha = 0.45f),
                    0.55f to secondary.copy(alpha = 0.13f),
                    1f to Color.Transparent,
                    center = centreB,
                    radius = span * (0.45f + 0.10f * i / (steps - 1f))
                )
            }

            onDrawBehind {
                val t = time()
                val l = level()
                val driftA = 0.5f + 0.5f * sin(t * 0.21f)
                val driftB = 0.5f + 0.5f * sin(t * 0.13f + 2f)

                val indexA = ((driftA * 0.5f + l * 0.5f) * (steps - 1)).toInt().coerceIn(0, steps - 1)
                val indexB = ((driftB * 0.5f + l * 0.5f) * (steps - 1)).toInt().coerceIn(0, steps - 1)

                drawRect(lobesA[indexA], alpha = (0.5f + 0.5f * l) * intensity)
                drawRect(lobesB[indexB], alpha = (0.45f + 0.55f * l) * intensity)
            }
        }
    )
}
