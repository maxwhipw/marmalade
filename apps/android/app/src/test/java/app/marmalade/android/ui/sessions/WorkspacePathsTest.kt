package app.marmalade.android.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Headless tests for the workspace picker's pure path navigation logic
 * (the digital-twin core of WorkspacePickerSheet). Mirrors desktop's
 * remote-picker.tsx clean/parentDir/pathName/crumbs behaviour.
 */
class WorkspacePathsTest {

    @Test
    fun `clean strips trailing slashes and normalises root`() {
        assertEquals("/proj", WorkspacePaths.clean("/proj/"))
        assertEquals("/proj/src", WorkspacePaths.clean("/proj/src///"))
        assertEquals("/", WorkspacePaths.clean("/"))
        assertEquals("/", WorkspacePaths.clean(""))
        assertEquals("/", WorkspacePaths.clean("///"))
    }

    @Test
    fun `parentDir walks up one level`() {
        assertEquals("/proj", WorkspacePaths.parentDir("/proj/src"))
        assertEquals("/proj", WorkspacePaths.parentDir("/proj/src/"))
        assertEquals("/", WorkspacePaths.parentDir("/proj"))
    }

    @Test
    fun `parentDir at root stays at root`() {
        assertEquals("/", WorkspacePaths.parentDir("/"))
        assertEquals("/", WorkspacePaths.parentDir(""))
    }

    @Test
    fun `pathName returns the basename`() {
        assertEquals("src", WorkspacePaths.pathName("/proj/src"))
        assertEquals("marmalade", WorkspacePaths.pathName("/home/user/coding/marmalade/"))
        // Root has no segment — falls back to the whole path.
        assertEquals("/", WorkspacePaths.pathName("/"))
    }

    @Test
    fun `crumbs builds root-first trail`() {
        val crumbs = WorkspacePaths.crumbs("/home/user/coding")
        assertEquals(
            listOf("/", "home", "user", "coding"),
            crumbs.map { it.label },
        )
        assertEquals(
            listOf("/", "/home", "/home/user", "/home/user/coding"),
            crumbs.map { it.path },
        )
    }

    @Test
    fun `crumbs at root is a single root entry`() {
        val crumbs = WorkspacePaths.crumbs("/")
        assertEquals(1, crumbs.size)
        assertEquals("/", crumbs[0].label)
        assertEquals("/", crumbs[0].path)
    }

    @Test
    fun `crumbsFrom clamps trail to home root`() {
        val crumbs = WorkspacePaths.crumbsFrom("/home/user", "/home/user/coding/marmalade")
        assertEquals(
            listOf("~", "coding", "marmalade"),
            crumbs.map { it.label },
        )
        assertEquals(
            listOf("/home/user", "/home/user/coding", "/home/user/coding/marmalade"),
            crumbs.map { it.path },
        )
    }

    @Test
    fun `crumbsFrom at home root is a single tilde entry`() {
        val crumbs = WorkspacePaths.crumbsFrom("/home/user/", "/home/user")
        assertEquals(1, crumbs.size)
        assertEquals("~", crumbs[0].label)
        assertEquals("/home/user", crumbs[0].path)
    }

    @Test
    fun `crumbsFrom falls back to full trail when path escapes root`() {
        // Defensive: a path above home shouldn't happen (navigation is clamped),
        // but if it does we degrade to the absolute trail rather than lie.
        val crumbs = WorkspacePaths.crumbsFrom("/home/user", "/etc")
        assertEquals(listOf("/", "etc"), crumbs.map { it.label })
    }
}
