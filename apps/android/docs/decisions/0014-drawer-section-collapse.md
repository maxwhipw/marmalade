# 0014 — Drawer sections collapse; the segmented switcher is deferred, not rejected

Status: **Accepted** (the maintainer signed off 2026-07-25)
Supersedes: nothing. Refines ADR 0013 decision 1 (the drawer's section order is
unchanged; only its collapse behaviour is added).

## Context

After using the ADR 0013 drawer on device, the maintainer asked whether **Workspaces /
Chats / Terminals should become three rounded buttons in a shared container**
that switch what the drawer displays, instead of stacking in one scroll —
explicitly as a question ("not sure if this will be better but want to check"),
not as an instruction.

The design lab `design-lab/labs/drawer-sections/` drew four shapes: **A** today's
single scroll, **B** the segmented switcher as asked, **C** collapsible sections,
**D** a filter that scrolls rather than switches.

Two facts decided it:

1. **B is the control ADR 0013 deleted**, moved one level up — the old Sessions
   screen's `Workspaces | Quick Chats | Terminals` view tabs. That is not
   disqualifying (the ADR's objection was *three competing navigators*, and the
   drawer is now the only one), but it means B inherits the property that made
   those tabs bad: state goes behind a mode.
2. **Terminals are top-level precisely so a running shell cannot get lost**
   (ADR 0013 decision 4, from the maintainer's stated footgun). Behind a segment, a running
   shell is a dot — and a dot cannot say *which* shell, or *which* chat is
   unread. A badge is a worse version of the row it is hiding.

## Decision

**Take option C.** The drawer stays one scroll; **Quick sessions and Terminals
become collapsible, exactly like workspaces already are.**

- A collapsed header carries its **count and its status dot**, so the glance
  survives the fold. Section status ranks running > awaiting input > unread —
  the same ranking the session rows' own dots use, so the header summarises its
  rows instead of inventing a second scheme.
- The chevron is the **same affordance the workspace rows already have**, on the
  same x. There is nothing new to learn; that is most of C's advantage over B.
- **Collapse state is per section and user-owned once touched**, the same rule
  the workspace toggles follow. Until touched, the default tracks app state.
- **Terminals default to expanded while a shell is running**, collapsed when
  none is. A user collapse outranks that default even with shells running.
- Quick sessions default to expanded.

**Option B is deferred, not rejected. The maintainer wants it kept on the table as
something he may try later.** If it is ever built, the lab's condition holds:
the segments must carry **live badges** (unread count on Chats, running count on
Terminals), or the drawer stops answering "what is marmalade doing right now"
and it will be felt first on a long-running shell. That would be a **new ADR
superseding this one** — not an edit here.

The real fork behind the choice, worth re-reading before revisiting: *is the
drawer's job "pick a workspace, then live inside it", or "see everything
marmalade is doing"?* If it is ever the former, B becomes correct rather than
lossy. Round 4 and the terminal footgun both answered "the latter".

## Consequences

- `DrawerSectionUtils` (`:shared`, commonMain) owns the two default rules and
  the status aggregation; `MarmaladeDrawer` only draws them.
- Collapse state lives in `MarmaladeNavHost` composition alongside
  `toggledWorkspaces` — remembered for the app's lifetime, not persisted. That
  matches the workspace toggles; if either is ever persisted, both should be.
- The fixed "Workspaces" header reserves the chevron slot so the three section
  labels stay aligned.
- The lab is archived with this decision recorded in `design-lab/labs.json`.
  Because that file lives in the (non-git) umbrella workspace, **this ADR is the
  durable record** — in particular of the deferred option B.
