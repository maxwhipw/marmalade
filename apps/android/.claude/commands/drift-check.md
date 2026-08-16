---
description: Verify CLAUDE.md and .claude/rules/* claims against current code; report contradictions without editing
---

# Drift-check pass

This is a verification pass, **not** an edit pass. Read docs, check
against code, report findings only.

## Steps

1. Read this module’s `CLAUDE.md` and all `.claude/rules/*.md`.
2. For each concrete claim — file paths, library names, build commands,
   "don't do X", "we use Y", file:line pointers — verify against current
   code with grep or by reading the relevant files.
3. For each ADR in `docs/decisions/*.md`, check the **Status** field:
   - `Accepted` ADRs should reflect what's live in code today
   - `Superseded by NNNN` ADRs should reference an existing newer ADR
   Do **not** verify ADR bodies — those are immutable historical records.
4. Look at git log since the last drift-check (default: last 14 days)
   for commits that touched files referenced in the docs. Flag any that
   might have invalidated a doc claim.

## Output

A single table:

| File:Line | Claim | Observed | Verdict |

Verdicts:
- `OK` — claim matches code
- `STALE` — claim contradicts current code; doc needs update
- `NEEDS-NEW-ADR` — code state differs from accepted ADR; needs a
  superseding ADR rather than editing the old one
- `UNCERTAIN` — couldn't verify cleanly; explain why

Keep the report under 600 words. Be decisive — `STALE` means the doc
contradicts code right now, not "the code might change someday."

## Do not

- Do not edit any doc, ADR, or rules file. Even if a fix is obvious.
  Report it; let the human decide whether the doc or the code is wrong.
- Do not propose deleting ADRs. They're immutable; supersede instead.
- Do not flag "soft" advice (e.g., "be careful with X") as STALE just
  because it's vague — only flag concrete falsifiable claims. But if
  you find a soft rule that should be made concrete, suggest a sharper
  rewording at the end.

## Stop conditions

If the project is mid-refactor (e.g., gateway being rewritten by
another agent), drift in the affected area is expected. Report it
once with a `MID-REFACTOR` note and don't keep flagging the same area.
