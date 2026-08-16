package app.marmalade.android.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for MascotExpression enum.
 * Validates completeness, uniqueness, and default expression.
 */
class MascotExpressionTest {

    @Test
    fun mascotExpression_hasExactly8Values() {
        assertEquals(
            "MascotExpression should have exactly 8 values",
            8,
            MascotExpression.entries.size,
        )
    }

    @Test
    fun mascotExpression_containsAllExpectedVariants() {
        val names = MascotExpression.entries.map { it.name }.toSet()
        val expected = setOf(
            "HAPPY", "SLEEPY", "WORRIED", "ALERT",
            "SPEAKING", "CONFUSED", "FOCUSED", "JOY",
        )
        assertEquals(expected, names)
    }

    @Test
    fun mascotExpression_eachHasNonZeroDrawableRes() {
        for (expression in MascotExpression.entries) {
            assertNotEquals(
                "${expression.name} should have a non-zero drawableRes",
                0,
                expression.drawableRes,
            )
        }
    }

    @Test
    fun mascotExpression_allDrawableResAreUnique() {
        val drawableIds = MascotExpression.entries.map { it.drawableRes }
        assertEquals(
            "All drawableRes values should be unique",
            drawableIds.size,
            drawableIds.toSet().size,
        )
    }

    @Test
    fun mascotExpression_happyIsDefault() {
        // HAPPY should be the first enum value (default)
        assertEquals(MascotExpression.HAPPY, MascotExpression.entries.first())
    }

    @Test
    fun mascotExpression_blinkDrawableExists() {
        assertNotEquals(
            "BLINK_DRAWABLE_RES should be non-zero",
            0,
            MascotExpression.BLINK_DRAWABLE_RES,
        )
    }

    @Test
    fun mascotExpression_blinkDrawableIsUniqueFromExpressions() {
        val expressionDrawables = MascotExpression.entries.map { it.drawableRes }.toSet()
        assert(!expressionDrawables.contains(MascotExpression.BLINK_DRAWABLE_RES)) {
            "BLINK_DRAWABLE_RES should be distinct from all expression drawables"
        }
    }
}
