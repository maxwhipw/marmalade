# Credits — borrowed code and adapted patterns

Per the attribution rule (one-line source comment at the borrow site + an
entry here, kept current — remove the entry if the code is later rewritten).

Verbatim license texts and copyright lines for the code borrowed **into this
tree** are in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). This file is
the ledger of *what* was borrowed and *where*; that file is the legal notice.

| Project | License | Repo | What was borrowed | Where |
|---|---|---|---|---|
| hermes-agent (NousResearch) | MIT | github.com/NousResearch/hermes-agent | Pairing-store security patterns: hash-at-rest with constant-time compare, single-use TTL'd pending codes, pending cap, failed-attempt lockout (incl. the "lockout must also gate approve" lesson, their #10195) — from `gateway/pairing.py` | `packages/daemon/src/pairing.ts` |
| OpenClaw (steipete) | MIT | github.com/steipete/openclaw | Setup-code payload shape `{url, token, expiry}` encoded base64url for QR/paste delivery — from `extensions/device-pair` | `packages/daemon/src/pairing.ts` (`encodeSetupCode`) |
| qrcode-terminal | Apache-2.0 | github.com/gtanner/qrcode-terminal | Dependency (not vendored): terminal QR rendering for `marmalade pair` | `packages/cli` |
| Kai (SimonSchubert) | Apache-2.0 | github.com/SimonSchubert/Kai | Dynamic-UI JSON syntax-repair stages (fixJsonSyntax / sanitizeJson / trimTrailingIncomplete) from `ui/dynamicui/KaiUiParser.kt`, ported to TS by way of the Android client's JsonRepair.kt | `packages/ui-tree/src/repair.ts` |
| OpenClaw (steipete) | MIT | github.com/steipete/openclaw | Cron schedule math + hardening invariants from `src/cron/schedule.ts` + `stagger.ts` (never-past next-run with croner year-rollback retry, every-from-anchor step math, LRU evaluator cache, top-of-hour stagger) and the bug-class semantics encoded in their issue-named regression tests (restart-catchup, daily-skip #17852, at-reschedule #19676, every next-run #22895, unresolved next-run #66019, list-skips #16156, duplicate timers, single-flight) | `packages/daemon/src/cron-schedule.ts`, `cron-scheduler.ts` + `test/cron-*.test.ts` |
| croner | MIT | github.com/hexagon/croner | Dependency (not vendored): cron expression evaluation with IANA timezones | `packages/daemon` |
| hermes-agent (NousResearch) | MIT | github.com/NousResearch/hermes-agent | Attachment staging: base64/data-URL decode, magic-byte image sniff (PNG/JPEG/GIF/WebP/BMP) + 25 MB cap, queue-then-consume-at-next-submit model, and the PDF→pdftoppm page-render trick (fixed argv, bounded pages/timeout) — from `tui_gateway/server.py` (image.attach_bytes/file.attach/image.detach/pdf.attach). Adapted: type decided by bytes not the declared extension; delivery via a harness-only prompt preamble (Claude Code reads content natively) rather than a server-side vision pipeline | `packages/daemon/src/attachments.ts` |
| Lucide icons | ISC + MIT (dual — MIT © Cole Bemis for the Feather-derived icons) | github.com/lucide-icons/lucide | SVG path data for the 28 glyphs of the Marmalade icon map, copied unmodified from `icons/<name>.svg`. Token names, wire-name resolution and the generator are ours | `packages/icons/src/map.json` |
| Manrope, Fredoka, Space Mono, Geist Mono | OFL-1.1 | fonts.google.com | Webfont binaries (woff2, latin + latin-ext, unmodified) vendored so the UI does not call out to a third-party font CDN | `packages/webui/src/styles/fonts/` |
