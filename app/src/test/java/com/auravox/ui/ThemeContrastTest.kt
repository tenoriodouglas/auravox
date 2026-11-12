package com.auravox.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the palette to the WCAG minimums.
 *
 * Contrast is the one visual property that is not a matter of taste, and on a
 * dark surface it is easy to get wrong in a way that looks fine to whoever
 * picked the colour on a bright monitor. Two of the original choices failed:
 * the outline sat at 1.5:1, which is why the cards looked edgeless, and the
 * saturated violet was under the body-text threshold once a surface was
 * raised. Both are caught here now rather than in a store review.
 */
class ThemeContrastTest {

    private val surfaces = listOf(
        "Background" to Aura.Background,
        "Surface" to Aura.Surface,
        "SurfaceHigh" to Aura.SurfaceHigh
    )

    private fun assertAtLeast(
        label: String, fg: Color, minimum: Double
    ) {
        for ((name, bg) in surfaces) {
            val r = Contrast.ratio(fg, bg)
            assertTrue(
                "$label sobre $name: %.2f:1, minimo %.1f:1".format(r, minimum),
                r >= minimum
            )
        }
    }

    /** Body text needs 4.5:1. */
    @Test
    fun textRolesAreReadable() {
        assertAtLeast("OnDark", Aura.OnDark, 4.5)
        assertAtLeast("Dim", Aura.Dim, 4.5)
        assertAtLeast("VioletText", Aura.VioletText, 4.5)
        assertAtLeast("Teal", Aura.Teal, 4.5)
        assertAtLeast("Amber", Aura.Amber, 4.5)
        assertAtLeast("Pink", Aura.Pink, 4.5)
    }

    /** Borders, dividers and indicators need 3:1. */
    @Test
    fun boundariesAreVisible() {
        assertAtLeast("Outline", Aura.Outline, 3.0)
        assertAtLeast("Violet", Aura.Violet, 3.0)
    }

    /** Every colour the accuracy ramp can produce carries a reading. */
    @Test
    fun accuracyRampIsReadable() {
        var v = 0.0f
        while (v <= 1.0f) {
            assertAtLeast("accuracy(%.2f)".format(v), Aura.accuracy(v), 4.5)
            v += 0.05f
        }
    }

    /** The maths itself, against two values anyone can check by hand. */
    @Test
    fun ratioMathIsRight() {
        val blackOnWhite = Contrast.ratio(Color.Black, Color.White)
        assertTrue("preto sobre branco deu %.2f".format(blackOnWhite),
                   blackOnWhite in 20.9..21.1)
        val same = Contrast.ratio(Aura.Teal, Aura.Teal)
        assertTrue("a mesma cor deu %.2f".format(same), same in 0.99..1.01)
    }
}
