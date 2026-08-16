package app.marmalade.android.utils

import app.marmalade.android.rpc.types.WorkspaceContextResponse

/**
 * Pure logic for the workspace DETAIL screen: shaping the context strip / peek
 * tabs from a [WorkspaceContextResponse]. Kept out of Compose for headless
 * testing.
 */
object WorkspaceContextUtils {

    /** Which peek tab a chip opens (also the tab identity inside the sheet). */
    enum class PeekTarget { CLAUDE_MD, AGENTS_MD, MEMORY }

    /**
     * A single context-strip chip. [peek] null = display-only (the git chip);
     * non-null = tapping opens the context-peek sheet pre-selected on that tab.
     */
    data class ContextChip(
        val label: String,
        val peek: PeekTarget?,
        /** Muted/outlined styling (the git chip) vs. pastel (the file chips). */
        val outlined: Boolean,
    )

    /**
     * Build the ordered context-strip chips from a loaded [context]:
     *  - "git · <branch>" (outlined, display-only) when git_branch != null
     *  - "CLAUDE.md"  (pastel, peek) when present
     *  - "AGENTS.md"  (pastel, peek) when present
     *  - "memory · <n>" (pastel, peek) when n > 0
     * Returns empty when nothing is present — the caller then omits the strip.
     */
    fun chips(context: WorkspaceContextResponse): List<ContextChip> = buildList {
        context.git_branch?.takeIf { it.isNotBlank() }?.let { branch ->
            add(ContextChip(label = "git · $branch", peek = null, outlined = true))
        }
        if (context.claude_md != null) {
            add(ContextChip(label = "CLAUDE.md", peek = PeekTarget.CLAUDE_MD, outlined = false))
        }
        if (context.agents_md != null) {
            add(ContextChip(label = "AGENTS.md", peek = PeekTarget.AGENTS_MD, outlined = false))
        }
        val notes = context.memory.size
        if (notes > 0) {
            add(ContextChip(label = "memory · $notes", peek = PeekTarget.MEMORY, outlined = false))
        }
    }

    /** The peek-sheet tabs that actually have content, in display order. Mirrors
     *  the tappable chips (git is a strip-only chip, never a peek tab). */
    fun peekTabs(context: WorkspaceContextResponse): List<PeekTarget> = buildList {
        if (context.claude_md != null) add(PeekTarget.CLAUDE_MD)
        if (context.agents_md != null) add(PeekTarget.AGENTS_MD)
        if (context.memory.isNotEmpty()) add(PeekTarget.MEMORY)
    }
}
