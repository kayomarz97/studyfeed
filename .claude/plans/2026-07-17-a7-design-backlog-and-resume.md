# A7 DESIGN BACKLOG + RESUME NOTE — read this FIRST after compaction

**Date:** 2026-07-17 · **Branch:** `dev` · **Author:** user (design intent) + Claude (capture)

> ⛔ **This file is the compaction handoff. State on disk is the truth, never the transcript.** The user
> is compacting the conversation and will resume the **A2 → A7 build** from here. Read this, then
> `JOURNAL.md` (Phase 2 tail + ⛔ standing orders), `BUILD_PROMPT.md` §8, `.claude/INDEX.md`,
> `.claude/plans/2026-07-17-a2-study-feed.md` (A2 plan + decision log D1–D12).

---

## 0. THE DIRECTIVE (how to run A2 → A7)

1. **Build A2 → A7 FUNCTIONALLY, in §8 order.** Get every stage working and correct first.
2. **Defer ALL design/layout/UX polish to a dedicated A7 pass.** The items in §2 below are NOT to be
   built now — they are captured so they aren't lost, and executed at A7. Build the functional version
   now; redesign at A7.
3. **Keep asking the user questions in chat** — with **options, pros/cons, and ASCII diagrams** — at each
   decision point. The user wants to steer via choices, not prose.
4. Standing orders in `JOURNAL.md` still bind (no resequencing, ask before pushing, no exam strings, etc.).

---

## 1. WHERE WE ARE (resume point)

**Phase 2, stage A2 (Study Feed) — data/logic feature-complete; design pass owed at A7.**
Pushed to `origin/dev` through **`4104072`**. Guard green throughout, **zero new runtime deps** across all
of A2 (markdown renderer, filters, sort, star/tag, blob quiz-back are all pure SQL + Compose).

**A2 acceptance status (all proven on-device or by tests on the real pack):**
- ✅ (2) Mixed feed, 5 card types (mcq/flashcard/reel/blob/interlink — **no `image` type**, PATCH 04),
  importance + PYQ on every card.
- ✅ (3) Tap importance → "why" sheet from `importance_signals_json`.
- ✅ (4) 5★ filter = 1,046, PYQ filter = 4,306 (exact); **Priority sort ≠ Importance↓** (weakness folded
  in locally).
- ✅ (5) One-tap ⭐/tag, persists across restart, feeds the Starred/Tag filters.
- ✅ (6) MCQ answer → explanation + per-distractor why-wrong → `mistakes` row → FSRS write (verified: an
  interlink rated Good wrote `fsrs_cards` stability 3.26 + a `reviews` row).
- ✅ (7) core: blob read → quiz-back on the passage's own `blob_quiz`; `reread_due` = +1 day (in-app, no
  notification). **Spaced "a few cards later" insertion is A3's job**, deliberately deferred.
- ✅ **Long-MCQ nested scroll CONFIRMED working on the phone (2026-07-17)** — the one on-device risk that
  was open. The card's internal `verticalScroll` scrolls content and the pager takes over at the edges.

**A2 technical items still open:**
- ⚠ **Figures on cards not deployed.** Code is done + tested (`FigureImage` via `BitmapFactory.decodeFile`
  + LRU, D12 — never Coil/Glide). The 235 MB figures dir kept **timing out over wifi-adb**, so
  image-bearing cards (~1.7%, ~250 distinct figures) currently show text only. Pure deploy-logistics, not
  code. Fix: push figures in smaller batches, or accept it (a real user imports figures via SAF with the
  pack, where it's a one-time local copy, not wifi).

**Next stages to BUILD (functional), in order:** ~~A3~~ **✅** → ~~A4~~ **✅** (PATCH 05) → ~~A5~~ **✅**
(ego-graph) → ~~A6 (mistakes/mastery/stats)~~ **✅ DONE 2026-07-18** (`stats/` + `ui/stats/`; first DB
migration v1→v2 `resolved_count`; re-drill N=3; forgetting curve; 3 instrumented tests; see JOURNAL "A6
… COMPLETE") → **A7 (settings + THE DESIGN PASS below) ← NEXT, LAST STAGE.**

**A7 now also carries these functional-polish items surfaced during A3–A6 (do them in the A7 pass):**
- A5 concept map: layout is compact / labels collide near the centre → spread + label de-collision.
- A6 daily-goal target is a constant (50) → wire it to the A3 deadline pace (daily new-MCQ budget).
- A6 Progress screen may fold into the home page (backlog 2.1); re-drill N=3 → expose as a setting.
- Deadline (`feed.deadline`) + re-drill N + feed intensity/burst → Settings knobs (§8 A7).

---

## 2. THE A7 DESIGN BACKLOG (build functional now; redesign here at A7)

> Everything in this section is the user's design feedback on the A2 feed, given 2026-07-17. **Do NOT
> implement now.** Build the functional versions through A7, then execute this backlog as the A7 design
> pass, asking the user to choose between the options with diagrams.

### 2.1 A landing / home page on app open ("things still left")
**User:** *"maybe the first page when app loads of things still left."*
**Now:** the app opens straight into the feed (no home screen).
**Want:** a first screen showing what remains — due items, progress, what's left to study.

```
OPTION A — Dashboard home            OPTION B — Feed-first + summary strip   OPTION C — Bottom nav tabs
┌─────────────────────────┐          ┌─────────────────────────┐            ┌─────────────────────────┐
│ MRCP(UK) Part 1         │          │ ▸ 142 due · 30 new  ▾   │  ← strip   │        (the feed)       │
│ ───────────────────     │          │─────────────────────────│            │                         │
│  142 due today          │          │                         │            │                         │
│  30 new · 🔥 5-day      │          │      (the feed)         │            │                         │
│ ▓▓▓▓▓░░ Cardiology 71%  │          │                         │            ├─────────────────────────┤
│ ▓▓▓░░░░ Neurology  42%  │          │  tap strip → expands to │            │ 🏠 Home  📚 Feed  📊 Stats│
│  [ Start studying → ]   │          │  the dashboard          │            └─────────────────────────┘
└─────────────────────────┘          └─────────────────────────┘
+ orients the user, shows progress   + feed stays 1st (fast to study)       + scales to A6 stats/A4/A5
- one extra tap before studying      - dashboard is secondary               - nav chrome always on screen
```
Ties into **A6** (mastery/stats/streaks) — the home page is largely an A6 surface. Decide at A7 whether
home is its own screen or the A6 stats screen doubles as it.

### 2.2 Subject presentation
**Now:** small grey UPPERCASE label, top-left of each card ("CARDIOLOGY").
**User:** wants it reimagined (grouped with the PYQ concern).
**Constraint:** ⚠ **colour already encodes IMPORTANCE** (§8 A5) — a coloured subject tag would collide
with that. Keep subject non-colour-coded, or find a distinct channel (icon, position).
```
A: current corner label   B: bottom metadata bar        C: subtle left spine + label
┌──────────────┐          ┌──────────────┐              ┌──────────────┐
│ CARDIOLOGY 5★│          │   (content)  │              │┃ Cardiology  │
│  (content)   │          │──────────────│              │┃ (content)   │  ← thin subject-tinted spine,
└──────────────┘          │Cardiology·PYQ│              └──────────────┘    distinct from importance pill
                          └──────────────┘
```

### 2.3 PYQ presentation — user wants this to STAND OUT
**Now:** plain outline pill "Asked 2019 · 2023" beside the importance chip.
**User:** grouped subject+PYQ as "not the way I imagined." PYQ is the **strongest yield signal** in the
app — it likely deserves the most visual weight, not a quiet pill.
```
A: loud badge         B: corner ribbon/flag        C: combined signal row (subject+PYQ+importance)
┌──────────────┐      ┌──────────────┐              ┌───────────────────────────┐
│ 🔥 PYQ '19'23│      │        ╱PYQ╱ │  ← flag      │ Cardiology · 🔥2× · 5★     │
│  (content)   │      │  (content)   │    top-right │  (content)                │
└──────────────┘      └──────────────┘              └───────────────────────────┘
```

### 2.4 Tags (Careless slip / Knowledge gap / …) → move to BOTTOM, AFTER the answer
**User (clear):** *"the careless slip etc etc should come below after the answer as thumb reaches
easily."*
**Now:** tag chips sit at the TOP of the card, always visible (even before answering).
**Want:** tags appear at the **bottom** of the card, **only after the answer is revealed**, within easy
one-thumb reach.
```
NOW                          WANTED
┌──────────────┐             ┌──────────────┐
│ 5★  ☆star    │             │ 5★    ☆star  │
│ [tags row]   │  ← top      │  (stem)      │
│  (stem)      │             │  (options)   │
│  ...         │             │  [Reveal / answer]        │
└──────────────┘             │  (explanation)│
                             │ ─────────────│
                             │ [tags row]   │  ← bottom, post-answer, thumb-reachable
                             └──────────────┘
```
This one is unambiguous — likely a safe early A7 change (star stays top; tags move to bottom, gated on
reveal). `CardActionsState` already carries star+tags; just relocate the tag row and gate it.

### 2.5 Flashcard vs Reel — the taxonomy question (DEEPER — flag carefully)
**User:** *"what is the difference between flashcards and reels its one and the same … reels could be
flashcards and mcqs or text all together coming randomly subject wise or in a structured format."*
**Facts (from the pack):**
- `reel` = `hook_md` + `detail_md` — a one-line hook, tap to reveal the detail. "Swipeable fact cards."
  Rated got-it/shaky. **Passive exposure.**
- `flashcard` = `front_md` + `back_md` (cloze supported) — Q→A, 4-button FSRS rating. **Active recall.**
- They ARE presentationally similar (both reveal-then-rate). The intended difference is *recall vs
  exposure*, which the current UI does not make obvious.
**⚠ This touches the CONTENT MODEL, not just the UI.** The item types come from the **compiler** (Phase 1,
DONE + dormant). The app must not invent or merge feed item types (that's the app authoring content,
which §runtime + PATCH 04 forbid). So options:
- **(a) UI-only differentiation (safe, app-side):** keep the pack's types, but make the *modes* visually
  distinct — reels as fast "did you know" cards, flashcards as "recall this" with the rating front-loaded.
  Clarifies the difference without touching the pack.
- **(b) Presentation modes (app-side):** a feed-mode toggle — "Mixed (random)" vs "By subject
  (structured)" vs "Recall only (flashcards+mcqs)" vs "Skim (reels+blobs)." Groups types by *how the user
  wants to study*, not by the pack's labels. Powerful and app-side.
- **(c) Merge the types in the pack (Phase-1 change):** would need waking the dormant compiler + a
  reproducibility re-run + re-import. Heaviest; probably out of scope. Flag to user; default NO.
**Recommendation to propose at A7:** (a) + (b). Do NOT do (c) without explicit user go-ahead.

### 2.6 Tap ANYWHERE to reveal
**User:** *"for tap to reveal allow to tap anywhere on the screen."*
**Now:** the user taps the "Reveal" button / the card body.
**Want:** tapping **anywhere** on the screen reveals (before the answer stage). Easy: a full-card
clickable overlay that consumes a tap only while `!revealed`, then releases so option taps work. Applies
to reel, interlink, flashcard (flip), and the blob quiz. MCQ is different (you tap an *option*, not
"reveal") — leave MCQ option-tapping as is, but consider tap-anywhere for its post-answer "next".

---

## 3. RESUME CHECKLIST (do this when the user resumes)

1. Read this file, then `JOURNAL.md` (⛔ standing orders + Phase 2 tail), `BUILD_PROMPT.md` §8, the A2 plan.
2. Confirm git state: `git log --oneline -5` should show `4104072` (A2 blob quiz-back) at or near HEAD on
   `dev`, in sync with `origin/dev`.
3. Emulator (`Medium_Phone_API_36.1`) and the phone (`SM-S918B`, wifi-adb — enable **Stay awake while
   charging**, it drops on sleep) are the two test targets. Pack lives in the app's `files/packs/<exam>/`;
   `RealPackTest`/`FeedRepositoryTest` read `files/realpack/` (push via the run-as procedure in
   `RealPackTest`'s KDoc). `am instrument -r` directly — never `connectedAndroidTest` (it uninstalls +
   wipes the fixture). **A skip is not a pass — check the `skipped` count.**
4. ~~Build A3 next~~ **A3 DONE** (see `.claude/plans/2026-07-17-a3-feed-ordering-engine.md` for the full
   design + §7 LOCKED POLICY). **Build A4 next** (Topic Dossier reader, §8 A4): Library/Search screen
   sortable by importance, search entity by name/alias → `topic_pages.body_md` as one long scrollable
   page with inline diagrams, collapsible `topic_sections` + jump-nav, links to that entity's
   MCQs/flashcards. **Pure render — nothing generated.** Then A5 → A6 → A7.
5. **At A7:** execute §2 above as the design pass, asking the user to choose options with diagrams. Also
   the deferred UX: tags-to-bottom (2.4), tap-anywhere (2.6), home page (2.1).
6. Every push: **secret-scanner FIRST, no exception** (a skipped-scan mistake is in the ledger). Never
   paste real pack content into any tracked file — use synthetic FORMS (ledger entry exists).
