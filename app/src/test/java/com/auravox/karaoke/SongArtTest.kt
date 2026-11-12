package com.auravox.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * The judgement half of reading a colour off an album cover.
 *
 * Only the pixel maths is tested, which is the part that can be wrong in a way
 * nobody notices until a screen looks muddy: the decoding around it is the
 * platform's and has nothing to decide.
 */
class SongArtTest {

    private fun argb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun hueOf(colour: Int): Float {
        val r = ((colour shr 16) and 0xFF) / 255f
        val g = ((colour shr 8) and 0xFF) / 255f
        val b = (colour and 0xFF) / 255f
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val d = maxC - minC
        if (d < 1e-6f) return -1f
        val h = when (maxC) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b)
        return min(d, 360f - d)
    }

    private fun value(colour: Int): Float {
        val r = ((colour shr 16) and 0xFF)
        val g = ((colour shr 8) and 0xFF)
        val b = (colour and 0xFF)
        return maxOf(r, g, b) / 255f
    }

    /** A cover that is mostly one colour should be read as that colour. */
    @Test
    fun findsTheDominantHue() {
        // A warm orange cover with a little of everything else
        val pixels = IntArray(1000) { argb(220, 110, 40) } +
                     IntArray(60) { argb(40, 60, 200) }
        val palette = SongArt.paletteOf(pixels)
        val hue = hueOf(palette.primary)
        assertTrue("achou matiz $hue, esperado perto de 25", hueDistance(hue, 25f) < 30f)
    }

    /**
     * Grey must not win.
     *
     * Covers are full of grey: concrete, sky, black and white photographs. A
     * naive average lands on grey almost every time, and a grey glow is no
     * glow at all.
     */
    @Test
    fun greyDoesNotDecide() {
        val pixels = IntArray(4000) { argb(128, 130, 127) } +   // mostly grey
                     IntArray(200) { argb(30, 200, 120) }       // a green accent
        val palette = SongArt.paletteOf(pixels)
        val hue = hueOf(palette.primary)
        assertTrue("achou matiz $hue, esperado o verde perto de 150",
                   hueDistance(hue, 150f) < 40f)
    }

    /** The two colours have to be far enough apart to read as two. */
    @Test
    fun theTwoColoursDiffer() {
        val pixels = IntArray(800) { argb(200, 40, 60) } +
                     IntArray(400) { argb(40, 90, 210) }
        val palette = SongArt.paletteOf(pixels)
        val apart = hueDistance(hueOf(palette.primary), hueOf(palette.secondary))
        assertTrue("os dois ficaram a $apart graus", apart > 50f)
    }

    /** Even from a single hue, the second colour is still a different one. */
    @Test
    fun oneHueStillYieldsTwoColours() {
        val pixels = IntArray(1000) { argb(220, 60, 60) }
        val palette = SongArt.paletteOf(pixels)
        val apart = hueDistance(hueOf(palette.primary), hueOf(palette.secondary))
        assertTrue("os dois ficaram a $apart graus", apart > 50f)
    }

    /**
     * Both come back bright.
     *
     * An album cover is often nearly black, and the stage behind it is nearly
     * black too, so a colour taken at the cover's own brightness would not be
     * visible at all.
     */
    @Test
    fun coloursAreLifted() {
        val dark = IntArray(1000) { argb(40, 12, 18) }
        val palette = SongArt.paletteOf(dark)
        assertTrue("primaria em ${value(palette.primary)}", value(palette.primary) > 0.75f)
        assertTrue("secundaria em ${value(palette.secondary)}", value(palette.secondary) > 0.75f)
    }

    /** Nothing to read falls back rather than returning black. */
    @Test
    fun fallsBackWhenThereIsNoColour() {
        assertEquals(SongArt.fallback, SongArt.paletteOf(IntArray(0)))
        assertEquals(SongArt.fallback, SongArt.paletteOf(IntArray(500) { argb(10, 10, 10) }))
        assertEquals(SongArt.fallback, SongArt.paletteOf(IntArray(500) { argb(250, 250, 250) }))
    }
}
