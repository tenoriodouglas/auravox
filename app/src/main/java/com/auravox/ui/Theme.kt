package com.auravox.ui

import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Stage palette: near-black so a lit phone does not wash out in a dark room,
 * with accents that stay apart from each other under the pitch lane's
 * translucency.
 *
 * Every pairing in here is measured rather than judged by eye, and the
 * ThemeContrastTest holds the numbers to the WCAG minimums. Two of the
 * original choices failed and are split in two as a result: the outline was
 * at 1.5:1, which is why cards looked edgeless, and the violet was under the
 * body-text threshold on a raised surface. Saturated colour on a dark surface
 * is the usual cause, so the saturated version stays for fills and a lighter
 * one carries text.
 */
object Aura {
    val Background = Color(0xFF07060F)
    val Surface = Color(0xFF13112A)
    val SurfaceHigh = Color(0xFF1D1A3D)

    /** Fills, strokes and large type only: 3.8:1 on the most raised surface. */
    val Violet = Color(0xFF7C5CFF)
    /** The text-bearing violet: 5.5:1 on the most raised surface. */
    val VioletText = Color(0xFF9B82FF)

    val Teal = Color(0xFF00E5C7)
    val Pink = Color(0xFFFF4D8D)
    val Amber = Color(0xFFFFC857)
    val Dim = Color(0xFF8A86A8)

    /** 3.2:1 on the most raised surface, so a card edge is actually visible. */
    val Outline = Color(0xFF6B63B5)

    val OnDark = Color(0xFFEDEBFF)

    /** Colour for a note's accuracy, from missed to nailed. */
    fun accuracy(value: Float): Color = when {
        value >= 0.9f -> Teal
        value >= 0.7f -> Color(0xFF7DE8A0)
        value >= 0.45f -> Amber
        else -> Pink
    }
}

/**
 * Spring specs, from the Material 3 expressive motion tokens.
 *
 * Spatial springs are deliberately underdamped: the overshoot is the point.
 * Effects springs are critically damped because colour and opacity should
 * never bounce.
 *
 * These animate fractions, not pixels. A spring settles once it is within
 * 0.01 of its target in the value's own unit, so the same spec that feels
 * quick on a 0..1 fraction crawls on an 800 px travel.
 */
object AuraMotion {
    fun <T> fastSpatial() = spring<T>(dampingRatio = 0.6f, stiffness = 800f)
    fun <T> defaultSpatial() = spring<T>(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> slowSpatial() = spring<T>(dampingRatio = 0.8f, stiffness = 200f)
    fun <T> fastEffects() = spring<T>(dampingRatio = 1f, stiffness = 3800f)
    fun <T> defaultEffects() = spring<T>(dampingRatio = 1f, stiffness = 1600f)
}

/** WCAG 2.x relative luminance and contrast, so the palette can be tested. */
object Contrast {
    fun luminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
               0.7152 * channel(color.green) +
               0.0722 * channel(color.blue)
    }

    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}

private val scheme = darkColorScheme(
    primary = Aura.Violet,
    onPrimary = Color.White,
    secondary = Aura.Teal,
    onSecondary = Color(0xFF00261F),
    tertiary = Aura.Pink,
    background = Aura.Background,
    onBackground = Aura.OnDark,
    surface = Aura.Surface,
    onSurface = Aura.OnDark,
    surfaceVariant = Aura.SurfaceHigh,
    onSurfaceVariant = Aura.Dim,
    outline = Aura.Outline,
    outlineVariant = Color(0xFF423C73)
)

/*
 * Built by copying the Material defaults rather than replacing them.
 *
 * Constructing a TextStyle with only a size and a weight throws away the
 * line height and tracking Material tuned for that role, which is most of
 * what separates type that was designed from type that was merely sized.
 * Negative tracking at display sizes and a generous line height on body text
 * are the rest of it.
 */
private val base = Typography()

private val typography = base.copy(
    displayLarge = base.displayLarge.copy(
        fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-1.2).sp,
        fontWeight = FontWeight.Black
    ),
    headlineMedium = base.headlineMedium.copy(
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp,
        fontWeight = FontWeight.Bold
    ),
    titleLarge = base.titleLarge.copy(
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = base.titleMedium.copy(
        fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.1).sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = base.labelSmall.copy(
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
fun AuraVoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
