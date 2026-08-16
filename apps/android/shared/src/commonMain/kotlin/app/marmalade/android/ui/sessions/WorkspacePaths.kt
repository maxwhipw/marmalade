package app.marmalade.android.ui.sessions

/**
 * Pure path helpers for the gateway-side workspace picker. Gateway paths are
 * POSIX ('/'-separated) absolute paths, independent of the Android device's
 * own filesystem. Kept free of Compose/Android so the navigation logic is
 * unit-testable headless — ported from desktop's `remote-picker.tsx`
 * (clean / parentDir / pathName / crumbs).
 */
object WorkspacePaths {

    /** One breadcrumb segment: a display [label] and the absolute [path] it
     *  navigates to. */
    data class Crumb(val label: String, val path: String)

    /** Strip trailing slashes; the filesystem root normalises to "/". */
    fun clean(path: String): String {
        val trimmed = path.trimEnd('/')
        return trimmed.ifEmpty { "/" }
    }

    /** The parent directory of [path], or "/" at the root. */
    fun parentDir(path: String): String {
        val value = clean(path)
        if (value == "/") return "/"
        val parent = value.substring(0, value.lastIndexOf('/'))
        return parent.ifEmpty { "/" }
    }

    /** The last path segment (basename) — what to show for a folder. Falls
     *  back to the whole path when there are no segments (i.e. "/"). */
    fun pathName(path: String): String {
        val segs = clean(path).split('/').filter { it.isNotEmpty() }
        return segs.lastOrNull() ?: path
    }

    /** Breadcrumb trail from the root down to [path], root first. */
    fun crumbs(path: String): List<Crumb> {
        val parts = clean(path).split('/').filter { it.isNotEmpty() }
        val out = mutableListOf(Crumb("/", "/"))
        var acc = ""
        for (part in parts) {
            acc += "/$part"
            out.add(Crumb(part, acc))
        }
        return out
    }

    /** Breadcrumb trail clamped to [root] (the daemon's home dir): [root] is
     *  the first crumb, shown as "~", and only segments at or below it appear.
     *  The daemon confines fs.list to home, so navigating above [root] always
     *  errors — this keeps the trail (and ".." ) inside the reachable range.
     *  Falls back to a full [crumbs] trail if [path] is not under [root]. */
    fun crumbsFrom(root: String, path: String): List<Crumb> {
        val r = clean(root)
        val p = clean(path)
        if (p != r && !p.startsWith("$r/")) return crumbs(p)
        val out = mutableListOf(Crumb("~", r))
        var acc = r
        for (part in p.removePrefix(r).split('/').filter { it.isNotEmpty() }) {
            acc += "/$part"
            out.add(Crumb(part, acc))
        }
        return out
    }
}
