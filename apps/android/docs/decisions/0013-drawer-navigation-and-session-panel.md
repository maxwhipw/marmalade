# 0013 — Drawer navigation, title-bar session switcher, and the session tool panel

Status: **Accepted** (the maintainer signed off 2026-07-25)
Supersedes: nothing. Constrains: the Sessions screen, `MarmaladeNavHost` bottom bar,
`WorkspaceCard`, `WorkspaceDetailScreen`, and every future per-session surface.

## Context

The client grew three navigation systems that compete:

1. a bottom tab bar (Home / Sessions / Debug / Settings),
2. a Sessions screen with its own view tabs (Workspaces | Quick Chats | Terminals),
3. workspace cards that expand in place.

The visible symptom was a "pile of buttons" — search, settings, TTS, conversation
mode, model, effort, permission mode, new session, new terminal — with no rule
saying where any of them belonged. The deeper cause is that with three navigators,
no control has a single obvious home.

Four design-lab rounds ran against this (`design-lab/labs/new-session/`, archived
with the decision recorded in `labs.json`). Rounds 1–3 were rejected. Round 4 ran
as a deliberate pair — a thorough take and a novel take — which converged
independently on the same core, which is the main evidence for this decision.

Paseo is the UX reference point here, but **its source is off-limits — screenshots
only**. Any influence it had on this design came from screenshots, never from
reading its code. T3 Code (MIT) was read for ideas only.

## Decision

**The app is always one thing: a session, with a panel available beside it.**

1. **The drawer is the only navigator.** It lists the pinned main marmalade
   session, then workspaces — each collapsible, **only the current one expanded**,
   with its sessions nested underneath — then Quick sessions, then a top-level
   **Terminals** section. App-scoped buttons (search, new workspace, home,
   settings) sit in a row pinned to the drawer's bottom.
   **The bottom tab bar and the Sessions screen are deleted.**
2. **The title bar is the session switcher.** Tapping the title opens a sheet
   listing this workspace's sessions and terminals, with create rows in the
   footer. A search field appears once a workspace exceeds ~10 sessions
   (workspaces routinely hold 20+).
3. **A right-edge tool panel**, opened by a panel glyph in the top right. Tabs in
   order: **Overview**, Files, Artifacts. Overview carries the session summary, a
   **context donut** with `Compact now`, and **two quota bars (5-hour window and
   weekly)**, plus model / branch / started metadata.
   The panel opens from the **right**: the left edge belongs to the drawer, and
   one edge cannot own two surfaces.
4. **Terminals are never owned by an agent session.** They are top-level in the
   drawer, carry their workspace on the row, show a live/running dot, and appear
   in the switcher sheet alongside sessions. A running terminal must not be killed
   by navigation. (Direct answer to the stated footgun: losing track of which
   session a shell was started from.)
5. **Every control is placed by scope** — app / workspace / session / turn:
   - app → drawer bottom row (settings, search, home, new workspace)
   - workspace → workspace row overflow (new session/terminal here, rename, remove)
   - session → title tap (switch) and top-bar ⋮ (rename, branch, archive, delete,
     speak replies)
   - turn → composer (attach, dictate, send; model / effort / permission mode)
   - conversation mode → **long-press the mic**, because it replaces the whole
     input model and therefore deserves a mode rather than another button.
6. **The main marmalade session is the cold-start screen.** It has no Home tab
   because there is no tab bar; it is simply what the app opens to, and it stays
   pinned at the top of the drawer.

## Consequences

- `MarmaladeNavHost`'s bottom bar and `bottomBarDestinations` go away; Debug
  becomes a Settings page rather than a tab.
- `SessionListScreen` (Workspaces | Quick Chats | Terminals) is retired; its
  grouping logic moves into the drawer. `WorkspaceCard` is superseded by drawer
  rows. `WorkspaceDetailScreen`'s role shrinks to workspace settings/context.
- Adding a future per-session surface (artifacts, diffs, preview) costs a **panel
  tab**, not a redesign — that is the main reason for choosing a panel over more
  screens.
- Overview needs daemon data the client does not fully surface yet: session
  summary (exists), context occupancy (another session is landing this), and
  subscription/quota usage for the 5-hour and weekly windows. Overview ships with
  whatever is available and degrades per-field rather than blocking.
- Risk accepted: this is a large navigation change to a daily-driven app. It is
  therefore sequenced so each step is independently useful and shippable —
  **(1) title-bar switcher, (2) drawer replacing the tab bar, (3) tool panel** —
  rather than landing as one cut-over.

## Alternatives rejected

- **Keep the bottom tab bar alongside a drawer.** Two navigators is the disease.
- **A persistent session rail** (always-visible chips). Fastest to switch, but
  spends permanent vertical space and collapses past ~6 sessions against a real
  load of 20+.
- **Make a shell a "session kind" chosen at creation**, or a per-workspace
  singleton. Both force a classification step on every create to serve the rare
  case, and neither solves trackability.
- **Type-to-start** (`$ cmd` opens a shell) and **no-create-step** (workspace opens
  into a live composer). Both explicitly rejected by the maintainer.
- **Fable's "Spine"** (no drawer; one sheet whose top strip switches workspace and
  whose list switches session). Elegant and genuinely close, but the drawer keeps
  workspace structure visible at a glance, which is the quality Paseo was valued for.
  Its Overview panel design **was** adopted (see Decision 3).
