package app.marmalade.android.utils

object SessionCategoryUtils {
    /**
     * Parse a slash-based category from a session display name.
     *
     * "Coding/Marmalade App" -> ("Coding", "Marmalade App")
     * "Simple Session"       -> (null, "Simple Session")
     * "/Leading Slash"       -> (null, "/Leading Slash")
     * "Trailing/"            -> (null, "Trailing/")
     * null                   -> (null, "New Session")
     */
    fun parseSessionCategory(displayName: String?): Pair<String?, String> {
        if (displayName.isNullOrBlank()) return null to "New Session"
        val slashIndex = displayName.indexOf('/')
        if (slashIndex <= 0 || slashIndex >= displayName.length - 1) return null to displayName
        val category = displayName.substring(0, slashIndex).trim()
        val name = displayName.substring(slashIndex + 1).trim()
        return if (category.isNotEmpty() && name.isNotEmpty()) category to name
        else null to displayName
    }
}
