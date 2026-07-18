# Plan — reconcile the docs to reality: Phase 1 done, Phase 2 builds on the laptop

**Date:** 2026-07-17
**Trigger:** User: *"push my changes on local laptop on github and also edit the buildmd and other
files to be relevant of the future changes — I will be making the app on the laptop."*

---

## Part 1 — the push (DONE)

- Local `dev` was 4 ahead / 3 behind `origin/dev`. The two machines had diverged: the box pushed
  C11→C13, the laptop had A0 committed locally and unpushed.
- Rebased the 4 local (never-pushed) commits onto `origin/dev` — no force-push of shared history.
- One conflict, `.claude/PLAYBOOK.md`: both sides appended mistakes-ledger entries. Resolved by
  **keeping both** (compiler C11/C13 entries + Android A0 entries). `.claude/INDEX.md` auto-merged.
- Verified on the merged tree (a tree that had never existed before):
  `verifyOfflineGuarantees` + `testDebugUnitTest` **force-rerun** (`--rerun-tasks`, not UP-TO-DATE)
  → BUILD SUCCESSFUL.
- `secret-scanner` → CLEAN (no credentials, no `*.p12`/`keystore.properties` tracked, no APKs,
  no copyrighted sources).
- Pushed: `a457dd0..ea55417`.

---

## Part 2 — what reality changed (the reason the docs are now wrong)

Two facts landed that the docs predate:

1. **The compiler is DONE (C0–C13).** `dist/pack_MRCP_PART1.db` exists: 86,702 atoms · 14,455 MCQs ·
   9,510 flashcards · 3,000 reels · 8,194 dossiers · **26,965 playable feed_items**. PATCH-01
   reproducibility gate CLEARED. Phase 1 is closed.
2. **The app is built on the laptop**, not the Hetzner box — and the user has now said so directly.

Consequences: every doc clause that assumes "no pack exists yet" or "the box builds the APK" is stale.

---

## Part 3 — the standing-order conflict (surfaced, not silently resolved)

`JOURNAL_APP.md` carries four standing orders dated **2026-07-17**. The user's new instruction
**reverses two of them**. Recording this explicitly so nobody thinks the spec was edited by drift:

| # | Standing order | Status now |
|---|---|---|
| 1 | "Never edit `BUILD_PROMPT.md`. The user will update it." | **REVERSED by explicit instruction** — user delegated the edit. |
| 2 | "No resequencing. A1 is next, in §8 order." | **STANDS.** Not touched by this work. |
| 3 | "The user makes the packs and builds the app." | Packs: **DONE** (C13). App: still the user's. |
| 4 | "Nothing gets pushed without an explicit ask." | Explicit ask given → pushed. Rule itself stands. |

Also reversed: the **2026-07-16 ruling that §0 `build_env` stays verbatim**. That ruling is the
direct reason `JOURNAL_APP.md` carries the divergence ledger at all. Amending §0 dissolves the
divergence — so the ledger becomes *history*, not a live "do not fix" warning.

---

## Part 4 — the edits (scope pending user confirmation)

### `BUILD_PROMPT.md`
| § | Line ~ | Stale text | Fix |
|---|---|---|---|
| §0 | 49 | `build_env: hetzner_headless_claude_code` | Split: compiler→box, app→laptop (Android Studio + physical phone). The **locked param the user previously froze**. |
| §8 A0 | 983 | "Buildable headless via `./gradlew assembleDebug` **over SSH**" | Drop "over SSH"; keep the INTENT (CLI-buildable, no IDE-only config). Mark A0 **DONE**. |
| §11c | 1428+ | "Build it on the Hetzner box via Gradle CLI"; "tell me the exact `scp` command"; "Cockpit Navigator… ask me which I'd prefer" | Replace with `adb install -r` over USB from the laptop. The scp-vs-Cockpit question is moot. |
| START HERE | 3–40 | "begin at §13 (Kickoff)"; resume path = `.compiler_state.json` + `JOURNAL.md` | Phase 1 closed → a fresh session resumes at **§8 A1**; app resume path is `JOURNAL_APP.md`. |
| §7 | 849 | Compiler stages C0–C13 as future work | Mark Phase 1 **COMPLETE**; keep the stage text as the historical record. |
| §11e / §8 A1 | 1457 / 991 | §11e says export/import is "part of A1"; §8 A1 doesn't mention it; §8 A7 does | Make consistent — **A1 owns §11e**. Now urgent: real progress becomes losable the moment the real pack is studied. |

### `JOURNAL_APP.md`
- Rewrite standing orders per Part 3 (mark #1/#4 reversed with the date; keep #2 intact).
- Demote the divergence ledger to a resolved historical note once §0 is amended.
- Kill the stale premise "no pack exists yet" — it does; note the pack + its delivery path.
- Escalate the §11e open item from "safe for later" → **urgent**.

### `.claude/INDEX.md`
- Update the Phase 1 status line to COMPLETE; keep the map true in the same commit.

**Not touched:** the pack format / decoupling contract (§2, §6) — unchanged by any of this.

---

## Part 5 — user's decisions (2026-07-17, answered)

**Q: How far should the `BUILD_PROMPT.md` edit go?** → **FULL RECONCILIATION.** The 2026-07-16
ruling that §0 `build_env` stays verbatim is hereby **reversed by the user**. Every clause in the
Part 4 table gets fixed.

**Q: "I want the app to work with other databases also in future — do whatever will help with that
and not cause any problems."** → clarified to mean **other exam packs in the same pack format**
(MRCP Part 2, USMLE, another subject), **not** foreign/third-party DBs (Anki/CSV). Foreign import is
explicitly OUT of scope; do not build an adapter layer.

### What that implies (and does NOT imply)
Multi-pack is **already the locked architecture** — §0 `multi_pack: true`, `first_exam` "the app must
NOT be hardcoded to it", §8 A1 "Pack loading = **generic**", per-pack `progress_<exam_code>.db`.
Verified 2026-07-17: the app contains **zero** exam-specific strings — the only occurrence of "MRCP"
under `app/` is a comment in `strings.xml` *forbidding* it. So this needs **no new architecture**.

Two concrete additions serve it:
1. **Schema-version gate at A1 (NEW).** The pack carries `pack_manifest.schema_version` and
   `PRAGMA user_version = 1`. A1 must **check them on import** and refuse a pack it can't play with a
   clear message — never crash, never half-load. This is what makes "works with other databases
   later" safe rather than a hope, because a future pack built on a bumped schema is precisely the
   case that breaks a naive loader.
2. **§11e owned by A1**, per §11e's own words ("Treat it as part of A1, not a nice-to-have for
   later"). Not resequencing → standing order #2 untouched. It matters *more* with multiple packs:
   progress is per-pack, so export/import must be per-pack too.

**Guard rail:** nothing in this change may weaken the decoupling contract (§2, §6) or hardcode an
exam. The pack format itself is untouched.

## Verification
- Docs-only change → no runtime surface, so no `verifier` run is warranted (global rule: verifier is
  for code changes). The app build was already verified pre-push in Part 1.
- Re-read the amended §0/§8/§11c end-to-end after editing to confirm no internal contradiction remains.
- Commit separately from the A0 push; stage files **by name**.
</content>
</invoke>

---

> **Footnote added 2026-07-17 (after the fact — the plan above is left unedited as the record of
> what was actually planned at the time).** This document refers to `JOURNAL_APP.md`. That file no
> longer exists: it was merged into **`JOURNAL.md`** (one log, sectioned "Phase 1 — compiler" /
> "Phase 2 — app", ⛔ standing orders at the top) and deleted on 2026-07-17, once Phase 1 finished
> and the two-machines-colliding reason for the split went away. Read `JOURNAL.md` instead.
