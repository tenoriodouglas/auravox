package com.auravox.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Stage palette: near-black so a lit phone does not wash out in a dark room,
 * with three saturated accents that stay apart from each other under the
 * pitch lane's translucency.
 */
object Aura {
    val Background = Color(0xFF07060F)
    val Surface = Color(0xFF13112A)
    val SurfaceHigh = Color(0xFF1D1A3D)
    val Violet = Color(0xFF7C5CFF)
    val Teal = Color(0xFF00E5C7)
    val Pink = Color(0xFFFF4D8D)
    val Amber = Color(0xFFFFC857)
    val Dim = Color(0xFF8A86A8)

    /** Colour for a note's accuracy, from missed to nailed. */
    fun accuracy(value: Float): Color = when {
        value >= 0.9f -> Teal
        value >= 0.7f -> Color(0xFF7DE8A0)
        value >= 0.45f -> Amber
        else -> Pink
    }
}

private val scheme = darkColorScheme(
    primary = Aura.Violet,
    onPrimary = Color.White,
    secondary = Aura.Teal,
    onSecondary = Color(0xFF00261F),
    tertiary = Aura.Pink,
    background = Aura.Background,
    onBackground = Color(0xFFEDEBFF),
    surface = Aura.Surface,
    onSurface = Color(0xFFEDEBFF),
    surfaceVariant = Aura.SurfaceHigh,
    onSurfaceVariant = Aura.Dim,
    outline = Color(0xFF34305C)
)

private val typography = Typography(
    displayLarge = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Black),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun AuraVoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
