package app.marmalade.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCategoryUtilsTest {

    @Test
    fun parseSessionCategory_slashSeparated_returnsCategoryAndName() {
        val (category, name) = SessionCategoryUtils.parseSessionCategory("Coding/Marmalade App")
        assertEquals("Coding", category)
        assertEquals("Marmalade App", name)
    }

    @Test
    fun parseSessionCategory_noSlash_returnsNullCategoryAndFullName() {
        val (category, name) = SessionCategoryUtils.parseSessionCategory("Simple Session")
        assertNull(category)
        assertEquals("Simple Session", name)
    }

    @Test
    fun parseSessionCategory_leadingSlash_returnsNullCategory() {
        val (category, name) = SessionCategoryUtils.parseSessionCategory("/Leading Slash")
        assertNull(category)
        assertEquals("/Leading Slash", name)
    }

    @Test
    fun parseSessionCategory_trailingSlash_returnsNullCategory() {
        val (category, name) = SessionCategoryUtils.parseSessionCategory("Trailing/")
        assertNull(category)
        assertEquals("Trailing/", name)
    }

    @Test
    fun parseSessionCategory_null_returnsNewSession() {
        val (category, name) = SessionCategoryUtils.parseSessionCategory(null)
        assertNull(category)
        assertEquals("New Session", name)
    }

    @Test
    fun parseSessionCategory_blank_returnsNewSession() {
        val (category, name) = SessionCategoryUtils.parseSessionCategory("   ")
        assertNull(category)
        assertEquals("New Session", name)
    }

    @Test
    fun parseSessionCategory_multipleSlashes_usesFirstSlash() {
        val (category, name) = SessionCategoryUtils.parseSessionCategory("Work/Projects/Alpha")
        assertEquals("Work", category)
        assertEquals("Projects/Alpha", name)
    }
}
