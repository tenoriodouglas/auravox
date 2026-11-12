package com.auravox.karaoke

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/**
 * Pulls the cover out of a music file and names its colour.
 *
 * The stage is lit from the song rather than from one fixed palette, which is
 * most of what makes an app feel like it is about the music rather than about
 * itself. Most files carry a picture; the ones that do not fall back to the
 * app's own violet, and nothing about the layout depends on which happened.
 *
 * The colour picking is kept apart from the decoding so it can be tested
 * without a device: it is the half with the judgement in it.
 */
object SongArt {

    data class Palette(val primary: Int, val secondary: Int)

    /** Null when the file carries no picture, or it cannot be read. */
    fun cover(context: Context, uri: Uri, maxEdge: Int = 256): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = max(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = if (longest > maxEdge) longest / maxEdge else 1
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Reads a cover's pixels and names two colours to light the stage with. */
    fun paletteOf(bitmap: Bitmap): Palette {
        val width = min(bitmap.width, 128)
        val height = min(bitmap.height, 128)
        if (width <= 0 || height <= 0) return fallback
        val scaled = if (bitmap.width > width || bitmap.height > height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else bitmap

        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled !== bitmap) scaled.recycle()
        return paletteOf(pixels)
    }

    val fallback = Palette(0xFF7C5CFF.toInt(), 0xFF00E5C7.toInt())

    /**
     * Two colours from a set of pixels: a lead and something to sit against it.
     *
     * Pixels are binned by hue, and a bin's weight is its saturation times its
     * distance from both black and white, so a photograph that is mostly grey
     * sky does not decide the whole screen. The second colour is taken from
     * the best bin at least a sixth of the wheel away, which is what keeps the
     * pair from being two shades of one thing.
     *
     * Both come back lifted to a brightness that reads on a near-black
     * background. An album cover is often dark, and a dark glow is no glow.
     */
    fun paletteOf(pixels: IntArray): Palette {
        if (pixels.isEmpty()) return fallback

        val bins = 24
        val weight = FloatArray(bins)
        val sumSat = FloatArray(bins)
        val count = IntArray(bins)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val delta = maxC - minC
            if (delta < 0.08f) continue              // grey carries no hue

            val saturation = delta / maxC
            // Furthest from both ends of the value range counts most: a
            // near-black or blown-out pixel is not the song's colour
            val vividness = 1f - kotlin.math.abs(maxC - 0.62f) / 0.62f
            if (vividness <= 0f) continue

            val hue = hueOf(r, g, b, maxC, delta)
            val bin = ((hue / 360f) * bins).toInt().coerceIn(0, bins - 1)
            weight[bin] += saturation * vividness
            sumSat[bin] += saturation
            count[bin]++
        }

        var first = -1
        for (i in 0 until bins) if (first < 0 || weight[i] > weight[first]) first = i
        if (first < 0 || weight[first] <= 0f) return fallback

        val apart = bins / 6
        var second = -1
        for (i in 0 until bins) {
            val distance = min(
                kotlin.math.abs(i - first),
                bins - kotlin.math.abs(i - first)
            )
            if (distance < apart) continue
            if (second < 0 || weight[i] > weight[second]) second = i
        }

        val primaryHue = (first + 0.5f) / bins * 360f
        val primarySat = (sumSat[first] / max(1, count[first])).coerceIn(0.45f, 0.95f)
        val primary = hsvToArgb(primaryHue, primarySat, 0.92f)

        val secondary = if (second >= 0 && weight[second] > 0f) {
            val hue = (second + 0.5f) / bins * 360f
            val sat = (sumSat[second] / max(1, count[second])).coerceIn(0.45f, 0.95f)
            hsvToArgb(hue, sat, 0.88f)
        } else {
            // Nothing far enough away: take the complement, which always is
            hsvToArgb((primaryHue + 160f) % 360f, primarySat, 0.88f)
        }
        return Palette(primary, secondary)
    }

    private fun hueOf(r: Float, g: Float, b: Float, maxC: Float, delta: Float): Float {
        val hue = when (maxC) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }

    private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
        val c = value * saturation
        val h = hue / 60f
        val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = value - c
        fun channel(v: Float) = (((v + m) * 255f).toInt()).coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
