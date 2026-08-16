// Glyph geometry adapted from Lucide (https://lucide.dev) — the upstream SVG
// `d` attributes, with <rect>/<circle>/<line> elements expanded to equivalent
// path data and arc flags de-compressed so Compose's PathParser can read them.
// Licence: third_party/lucide/LICENSE (dual — ISC © Lucide Icons and
// Contributors; MIT © 2013-present Cole Bemis for the Feather-derived subset,
// which here is `feather`, `key`, `search` and `check`). See CREDITS.md.
package app.marmalade.android.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Marmalade icon map — one named glyph vocabulary shared by every surface
 * that has to say "this is a terminal call" or "this is a subagent".
 *
 * Signed off 2026-08-01 (design-lab `icon-map`). The rules that make it a map
 * rather than a pile of icons:
 *
 *  - **Lucide for content, Material for platform chrome.** Navigation, expand
 *    chevrons, overflow, close and the like stay Material Symbols — those are
 *    OS idioms. Anything that names a *concept the agent is doing* comes from
 *    here, so one concept is never drawn at two weights.
 *  - **The token name is the contract, the glyph is not.** `icon.tool.skill`
 *    may be repointed at a different drawing; it may not be renamed, because
 *    the other Marmalade surfaces resolve the same names.
 *  - **No sparkles.** Not as a glyph, not as an alternate, not as a fallback
 *    (maintainer, 2026-08-01) — Marmalade does not draw agency as magic.
 *
 * Tokens with no consumer yet (most of the `icon.agent.*` prompt/lifecycle
 * marks) are declared here deliberately: the vocabulary is the deliverable.
 * The approval and sudo cards still carry tone in a border colour alone; the
 * secret card draws [ShieldKey], because "the model does not see this" is not
 * something a border colour can say.
 */
object MarmaladeIcons {

    /** `icon.tool.terminal` — Bash / terminal / shell / run. Lucide `square-terminal`. */
    val Terminal: ImageVector by lazy {
        lucide(
            "Terminal",
            "m 7 11 l 2 -2 l -2 -2",
            "M 11 13 h 4",
            "M 5.0 3.0 h 14.0 a 2.0 2.0 0 0 1 2.0 2.0 v 14.0 a 2.0 2.0 0 0 1 -2.0 2.0 h -14.0 a 2.0 2.0 0 0 1 -2.0 -2.0 v -14.0 a 2.0 2.0 0 0 1 2.0 -2.0 z",
        )
    }

    /** `icon.tool.read` — Read / read_file / view. Lucide `file-text`. */
    val Read: ImageVector by lazy {
        lucide(
            "Read",
            "M 6 22 a 2 2 0 0 1 -2 -2 V 4 a 2 2 0 0 1 2 -2 h 8 a 2.4 2.4 0 0 1 1.704 .706 l 3.588 3.588 A 2.4 2.4 0 0 1 20 8 v 12 a 2 2 0 0 1 -2 2 z",
            "M 14 2 v 5 a 1 1 0 0 0 1 1 h 5",
            "M 10 9 H 8",
            "M 16 13 H 8",
            "M 16 17 H 8",
        )
    }

    /** `icon.tool.write` — Write / write_file. Lucide `file-plus`. */
    val Write: ImageVector by lazy {
        lucide(
            "Write",
            "M 6 22 a 2 2 0 0 1 -2 -2 V 4 a 2 2 0 0 1 2 -2 h 8 a 2.4 2.4 0 0 1 1.704 .706 l 3.588 3.588 A 2.4 2.4 0 0 1 20 8 v 12 a 2 2 0 0 1 -2 2 z",
            "M 14 2 v 5 a 1 1 0 0 0 1 1 h 5",
            "M 9 15 h 6",
            "M 12 18 v -6",
        )
    }

    /** `icon.tool.edit` — Edit / MultiEdit / NotebookEdit. Lucide `file-pen`. */
    val Edit: ImageVector by lazy {
        lucide(
            "Edit",
            "M 12.659 22 H 18 a 2 2 0 0 0 2 -2 V 8 a 2.4 2.4 0 0 0 -.706 -1.706 l -3.588 -3.588 A 2.4 2.4 0 0 0 14 2 H 6 a 2 2 0 0 0 -2 2 v 9.34",
            "M 14 2 v 5 a 1 1 0 0 0 1 1 h 5",
            "M 10.378 12.622 a 1 1 0 0 1 3 3.003 L 8.36 20.637 a 2 2 0 0 1 -.854 .506 l -2.867 .837 a .5 .5 0 0 1 -.62 -.62 l .836 -2.869 a 2 2 0 0 1 .506 -.853 z",
        )
    }

    /** `icon.tool.list` — Glob / ls / list_dir. Lucide `folder-open`. */
    val ListFiles: ImageVector by lazy {
        lucide(
            "ListFiles",
            "m 6 14 l 1.5 -2.9 A 2 2 0 0 1 9.24 10 H 20 a 2 2 0 0 1 1.94 2.5 l -1.54 6 a 2 2 0 0 1 -1.95 1.5 H 4 a 2 2 0 0 1 -2 -2 V 5 a 2 2 0 0 1 2 -2 h 3.9 a 2 2 0 0 1 1.69 .9 l .81 1.2 a 2 2 0 0 0 1.67 .9 H 18 a 2 2 0 0 1 2 2 v 2",
        )
    }

    /** `icon.tool.search` — Grep / search / find. Lucide `search-code`. */
    val Search: ImageVector by lazy {
        lucide(
            "Search",
            "m 13 13.5 l 2 -2.5 l -2 -2.5",
            "m 21 21 l -4.3 -4.3",
            "M 9 8.5 L 7 11 l 2 2.5",
            "M 3.0 11.0 a 8.0 8.0 0 1 0 16.0 0 a 8.0 8.0 0 1 0 -16.0 0 z",
        )
    }

    /** `icon.tool.web.fetch` — WebFetch / browse / fetch. Lucide `globe`. */
    val WebFetch: ImageVector by lazy {
        lucide(
            "WebFetch",
            "M 2.0 12.0 a 10.0 10.0 0 1 0 20.0 0 a 10.0 10.0 0 1 0 -20.0 0 z",
            "M 12 2 a 14.5 14.5 0 0 0 0 20 a 14.5 14.5 0 0 0 0 -20",
            "M 2 12 h 20",
        )
    }

    /** `icon.tool.web.search` — WebSearch. Lucide `search`. */
    val WebSearch: ImageVector by lazy {
        lucide(
            "WebSearch",
            "m 21 21 l -4.34 -4.34",
            "M 3.0 11.0 a 8.0 8.0 0 1 0 16.0 0 a 8.0 8.0 0 1 0 -16.0 0 z",
        )
    }

    /** `icon.tool.image` — generate_image / image. Lucide `image`. */
    val Image: ImageVector by lazy {
        lucide(
            "Image",
            "M 5.0 3.0 h 14.0 a 2.0 2.0 0 0 1 2.0 2.0 v 14.0 a 2.0 2.0 0 0 1 -2.0 2.0 h -14.0 a 2.0 2.0 0 0 1 -2.0 -2.0 v -14.0 a 2.0 2.0 0 0 1 2.0 -2.0 z",
            "M 7.0 9.0 a 2.0 2.0 0 1 0 4.0 0 a 2.0 2.0 0 1 0 -4.0 0 z",
            "m 21 15 l -3.086 -3.086 a 2 2 0 0 0 -2.828 0 L 6 21",
        )
    }

    /** `icon.tool.skill` — Skill. Lucide `feather`. */
    val Skill: ImageVector by lazy {
        lucide(
            "Skill",
            "M 14.086 18.412 A 2 2 0 0 1 12.67 19 H 5 v -7.672 a 2 2 0 0 1 .586 -1.414 L 11.75 3.75 a 6 6 0 1 1 8.49 8.49 z",
            "M 16 8 L 2 22",
            "M 17.488 15 H 9",
        )
    }

    /** `icon.tool.subagent` — Task / Agent. Lucide `bot`. */
    val Subagent: ImageVector by lazy {
        lucide(
            "Subagent",
            "M 12 8 V 4 H 8",
            "M 6.0 8.0 h 12.0 a 2.0 2.0 0 0 1 2.0 2.0 v 8.0 a 2.0 2.0 0 0 1 -2.0 2.0 h -12.0 a 2.0 2.0 0 0 1 -2.0 -2.0 v -8.0 a 2.0 2.0 0 0 1 2.0 -2.0 z",
            "M 2 14 h 2",
            "M 20 14 h 2",
            "M 15 13 v 2",
            "M 9 13 v 2",
        )
    }

    /** `icon.tool.question` — AskUserQuestion. Lucide `message-circle-question-mark`. */
    val Question: ImageVector by lazy {
        lucide(
            "Question",
            "M 2.992 16.342 a 2 2 0 0 1 .094 1.167 l -1.065 3.29 a 1 1 0 0 0 1.236 1.168 l 3.413 -.998 a 2 2 0 0 1 1.099 .092 a 10 10 0 1 0 -4.777 -4.719",
            "M 9.09 9 a 3 3 0 0 1 5.83 1 c 0 2 -3 3 -3 3",
            "M 12 17 h .01",
        )
    }

    /** `icon.tool.todo` — TodoWrite. Lucide `list-todo`. */
    val Todo: ImageVector by lazy {
        lucide(
            "Todo",
            "M 13 5 h 8",
            "M 13 12 h 8",
            "M 13 19 h 8",
            "m 3 17 l 2 2 l 4 -4",
            "M 4.0 4.0 h 4.0 a 1.0 1.0 0 0 1 1.0 1.0 v 4.0 a 1.0 1.0 0 0 1 -1.0 1.0 h -4.0 a 1.0 1.0 0 0 1 -1.0 -1.0 v -4.0 a 1.0 1.0 0 0 1 1.0 -1.0 z",
        )
    }

    /** `icon.tool.mcp` — mcp__<server>__<tool>. Lucide `plug`. */
    val Mcp: ImageVector by lazy {
        lucide(
            "Mcp",
            "M 12 22 v -5",
            "M 15 8 V 2",
            "M 17 8 a 1 1 0 0 1 1 1 v 4 a 4 4 0 0 1 -4 4 h -4 a 4 4 0 0 1 -4 -4 V 9 a 1 1 0 0 1 1 -1 z",
            "M 9 8 V 2",
        )
    }

    /** `icon.tool.doc` — article / doc / docs. Lucide `book-open-text`. */
    val Doc: ImageVector by lazy {
        lucide(
            "Doc",
            "M 12 5 v 16",
            "M 16 13 h 2",
            "M 16 9 h 2",
            "M 20.001 19 A 2 2 0 0 0 22 17 V 5 a 2 2 0 0 0 -1.999 -2 L 16 3.002 A 5 5 0 0 0 12 5 a 5 5 0 0 0 -4 -2 H 4 a 2 2 0 0 0 -2 2 v 12 a 2 2 0 0 0 1.999 2 H 8 a 5 5 0 0 1 4 2 a 5 5 0 0 1 4 -2 z",
            "M 6 13 h 2",
            "M 6 9 h 2",
        )
    }

    /** `icon.tool.unknown` — anything unmatched. Lucide `wrench`. */
    val Unknown: ImageVector by lazy {
        lucide(
            "Unknown",
            "M 14.7 6.3 a 1 1 0 0 0 0 1.4 l 1.6 1.6 a 1 1 0 0 0 1.4 0 l 3.106 -3.105 c .32 -.322 .863 -.22 .983 .218 a 6 6 0 0 1 -8.259 7.057 l -7.91 7.91 a 1 1 0 0 1 -2.999 -3 l 7.91 -7.91 a 6 6 0 0 1 7.057 -8.259 c .438 .12 .54 .662 .219 .984 z",
        )
    }

    /** `icon.agent.thinking` — reasoning.* / think. Lucide `brain`. */
    val Thinking: ImageVector by lazy {
        lucide(
            "Thinking",
            "M 12 18 V 5",
            "M 15 13 a 4.17 4.17 0 0 1 -3 -4 a 4.17 4.17 0 0 1 -3 4",
            "M 17.598 6.5 A 3 3 0 1 0 12 5 a 3 3 0 1 0 -5.598 1.5",
            "M 17.997 5.125 a 4 4 0 0 1 2.526 5.77",
            "M 18 18 a 4 4 0 0 0 2 -7.464",
            "M 19.967 17.483 A 4 4 0 1 1 12 18 a 4 4 0 1 1 -7.967 -.517",
            "M 6 18 a 4 4 0 0 1 -2 -7.464",
            "M 6.003 5.125 a 4 4 0 0 0 -2.526 5.77",
        )
    }

    /** `icon.agent.approval` — approval.request. Lucide `shield-check`. */
    val Approval: ImageVector by lazy {
        lucide(
            "Approval",
            "M 20 13 c 0 5 -3.5 7.5 -7.66 8.95 a 1 1 0 0 1 -.67 -.01 C 7.5 20.5 4 18 4 13 V 6 a 1 1 0 0 1 1 -1 c 2 0 4.5 -1.2 6.24 -2.72 a 1.17 1.17 0 0 1 1.52 0 C 14.51 3.81 17 5 19 5 a 1 1 0 0 1 1 1 z",
            "m 9 12 l 2 2 l 4 -4",
        )
    }

    /** `icon.agent.secret` — secret prompt. Lucide `key`. */
    val Secret: ImageVector by lazy {
        lucide(
            "Secret",
            "m 15.5 7.5 l 2.3 2.3 a 1 1 0 0 0 1.4 0 l 2.1 -2.1 a 1 1 0 0 0 0 -1.4 L 19 4",
            "m 21 2 l -9.6 9.6",
            "M 2.0 15.5 a 5.5 5.5 0 1 0 11.0 0 a 5.5 5.5 0 1 0 -11.0 0 z",
        )
    }

    /**
     * `icon.agent.secret.entry` — the secure-input card for `secret.request`.
     *
     * The plain [Secret] key says "a credential is involved"; this one has to
     * say something stronger and more specific: *you* are typing a credential
     * into a protected field, and the model is not going to see it. Lucide has
     * no shield-key, so this composes the `shield-check` shield (identical
     * outline to [Approval], which is what makes the family read as one
     * vocabulary) around a keyhole — circle plus stem, the universal "locked,
     * and you hold the key" mark. Not a padlock: a padlock means "this thing
     * is closed", and the card is the opposite — it is open, for you only.
     */
    val ShieldKey: ImageVector by lazy {
        lucide(
            "ShieldKey",
            "M 20 13 c 0 5 -3.5 7.5 -7.66 8.95 a 1 1 0 0 1 -.67 -.01 C 7.5 20.5 4 18 4 13 V 6 a 1 1 0 0 1 1 -1 c 2 0 4.5 -1.2 6.24 -2.72 a 1.17 1.17 0 0 1 1.52 0 C 14.51 3.81 17 5 19 5 a 1 1 0 0 1 1 1 z",
            "M 10.2 10.4 a 1.8 1.8 0 1 0 3.6 0 a 1.8 1.8 0 1 0 -3.6 0 z",
            "M 12 12.2 v 3.4",
        )
    }

    /** `icon.agent.sudo` — sudo prompt. Lucide `shield-alert`. */
    val Sudo: ImageVector by lazy {
        lucide(
            "Sudo",
            "M 20 13 c 0 5 -3.5 7.5 -7.66 8.95 a 1 1 0 0 1 -.67 -.01 C 7.5 20.5 4 18 4 13 V 6 a 1 1 0 0 1 1 -1 c 2 0 4.5 -1.2 6.24 -2.72 a 1.17 1.17 0 0 1 1.52 0 C 14.51 3.81 17 5 19 5 a 1 1 0 0 1 1 1 z",
            "M 12 8 v 4",
            "M 12 16 h .01",
        )
    }

    /** `icon.agent.voice` — mic / source=voice. Lucide `mic`. */
    val Voice: ImageVector by lazy {
        lucide(
            "Voice",
            "M 12 19 v 3",
            "M 19 10 v 2 a 7 7 0 0 1 -14 0 v -2",
            "M 12.0 2.0 h 0.0 a 3.0 3.0 0 0 1 3.0 3.0 v 7.0 a 3.0 3.0 0 0 1 -3.0 3.0 h -0.0 a 3.0 3.0 0 0 1 -3.0 -3.0 v -7.0 a 3.0 3.0 0 0 1 3.0 -3.0 z",
        )
    }

    /** `icon.agent.attachment` — image.attach_bytes / file.attach. Lucide `paperclip`. */
    val Attachment: ImageVector by lazy {
        lucide(
            "Attachment",
            "m 16 6 l -8.414 8.586 a 2 2 0 0 0 2.829 2.829 l 8.414 -8.586 a 4 4 0 1 0 -5.657 -5.657 l -8.379 8.551 a 6 6 0 1 0 8.485 8.485 l 8.379 -8.551",
        )
    }

    /** `icon.agent.schedule` — cron / scheduled run. Lucide `alarm-clock`. */
    val Schedule: ImageVector by lazy {
        lucide(
            "Schedule",
            "M 4.0 13.0 a 8.0 8.0 0 1 0 16.0 0 a 8.0 8.0 0 1 0 -16.0 0 z",
            "M 12 9 v 4 l 2 2",
            "M 5 3 L 2 6",
            "m 22 6 l -3 -3",
            "M 6.38 18.7 L 4 21",
            "M 17.64 18.67 L 20 21",
        )
    }

    /** `icon.agent.plugin` — plugin. Lucide `puzzle`. */
    val Plugin: ImageVector by lazy {
        lucide(
            "Plugin",
            "M 15.39 4.39 a 1 1 0 0 0 1.68 -.474 a 2.5 2.5 0 1 1 3.014 3.015 a 1 1 0 0 0 -.474 1.68 l 1.683 1.682 a 2.414 2.414 0 0 1 0 3.414 L 19.61 15.39 a 1 1 0 0 1 -1.68 -.474 a 2.5 2.5 0 1 0 -3.014 3.015 a 1 1 0 0 1 .474 1.68 l -1.683 1.682 a 2.414 2.414 0 0 1 -3.414 0 L 8.61 19.61 a 1 1 0 0 0 -1.68 .474 a 2.5 2.5 0 1 1 -3.014 -3.015 a 1 1 0 0 0 .474 -1.68 l -1.683 -1.682 a 2.414 2.414 0 0 1 0 -3.414 L 4.39 8.61 a 1 1 0 0 1 1.68 .474 a 2.5 2.5 0 1 0 3.014 -3.015 a 1 1 0 0 1 -.474 -1.68 l 1.683 -1.682 a 2.414 2.414 0 0 1 3.414 0 z",
        )
    }

    /** `icon.agent.error` — errored tool / subagent. Lucide `circle-alert`. */
    val Error: ImageVector by lazy {
        lucide(
            "Error",
            "M 2.0 12.0 a 10.0 10.0 0 1 0 20.0 0 a 10.0 10.0 0 1 0 -20.0 0 z",
            "M 12 8 L 12 12",
            "M 12 16 L 12.01 16",
        )
    }

    /** `icon.agent.done` — complete. Lucide `check`. */
    val Done: ImageVector by lazy {
        lucide(
            "Done",
            "M 20 6 L 9 17 l -5 -5",
        )
    }

}

/**
 * Lucide's drawing model: a 24x24 viewport, no fill, a 2px round-capped,
 * round-joined stroke. Building the [ImageVector] that way — rather than
 * converting each glyph to a filled outline — is what keeps the strokes
 * optically identical to Material's outlined set at 18dp.
 *
 * The stroke brush is opaque black only so that something is painted;
 * `Icon()` tints the whole vector, exactly as it does for the Material set.
 */
private fun lucide(name: String, vararg pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = ICON_SIZE.dp,
        defaultHeight = ICON_SIZE.dp,
        viewportWidth = ICON_SIZE,
        viewportHeight = ICON_SIZE,
    ).apply {
        pathData.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE_WIDTH,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private const val ICON_SIZE = 24f
private const val STROKE_WIDTH = 2f
