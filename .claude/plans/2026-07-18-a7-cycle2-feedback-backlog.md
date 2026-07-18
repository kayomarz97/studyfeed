# A7 Cycle 2 — backlog from user feedback (2026-07-18)

The user tested the A7 cycle-1 APK on their phone + is steering via the emulator. This captures EVERY
feedback item so none is lost. **Directive: fix the clear bugs, investigate the features, hold the design
questions until the Fable cohesiveness walkthrough finishes, then ask ONE consolidated set, then implement.**

## A. Bugs (no design decision — just fix)
- [x] **Cloze flashcards leaked the answer on the front** — FIXED, committed `eb7d415` (`ui/feed/Cloze.kt`);
  verified on emulator ("HLA-[ …… ]" → reveal "HLA-DR4").
- [x] **Blob quiz-backs: correct option ~always "A"** (index-0 89%) — FIXED, committed `eb7d415`
  (`FeedRepository.shuffleOptions`, deterministic per blob).
- [x] **Android back gesture/button did nothing** (custom `when`-router had no back stack, so back exited
  the app) — FIXED: `BackHandler` in `MainActivity.Root` maps each screen to its parent; Home lets the OS
  exit. **Needs on-device test** (emulator busy with the Fable agent).

## B. Features (feasible — some need a small placement decision)
- **MCQ → topic page.** After/within an MCQ, a "View topic" link to a detailed dossier about the subject.
  **Feasible: 81% coverage** — `topic_pages.related_mcq_ids_json` maps topic→MCQs, so reverse-lookup finds
  an MCQ's topic(s). (Parse the JSON in Kotlin, not `json_each` — JSON1 isn't guaranteed at minSdk 26.)
  The A4 `DossierScreen` reader already exists to open. Decision: where the link sits (post-answer only?
  always? on every card type or just MCQ?), and fallback when an MCQ has no topic (~19%).
- **Library searchable by IMPORTANT topics + PYQ topics.** LibraryScreen already sorts by importance; add
  quick filters/chips: "5★ / 4★+ topics" (importance) and "PYQ topics" (topics whose related MCQs are
  past-paper). `topic_pages` has no `is_pyq`; derive PYQ-topic from related MCQs' `is_pyq`, or precompute.

## C. Design — needs the user's steering (the consolidated questions)
- **Feed top-control cohesiveness.** Three stacked strips (study-mode chips + "Sort:" + long filter row)
  are overwhelming/redundant — the #1 "not cohesive" culprit. Options: collapse into one bar / a single
  filter sheet / move modes elsewhere.
- **Consistent headers & back affordance** across screens (Home "‹ Home" vs others "← Back").
- **"Make the app colored."** ⚠ BIG direction: today it's AMOLED true-black + colour reserved to ENCODE
  importance (§8 A5). "More colour overall" can collide with that encoding — needs a deliberate choice:
  colourful accents/surfaces while keeping importance legible, per-subject colour, or a lighter theme.
- **Card signal layout** (loud PYQ / importance pill / subject footer / star) — does it read clearly?
- **Empty space** below short cards.
- **+ whatever the Fable agent surfaces** (full-app walkthrough, pending).

## Sequence
1. Fable walkthrough completes → merge its findings with A–C above. ✅
2. Ask the user ONE consolidated AskUserQuestion set (the highest-leverage decisions, with diagrams). ✅
3. Built a visual mockup (artifact v3), iterated with the user, **APPROVED 2026-07-18** — now building.

## APPROVED SPEC (build this — user approved the v3 mockup + these final adds)
Decisions locked: **vibrant colour on true-black** · **one-row feed + Filter sheet** · **same 4 rating
buttons everywhere** · **content-type Home with progress**. Palette = flat "clinical night": cool
near-black, one iris accent `#6e72e8`, coral PYQ `#f2705b` (distinct from amber importance ramp, KEPT),
muted content hues (iris/plum `#b07cd6`/teal `#3fa9a0`/amber `#e0a54a`). No gradient-slop; motion short
(150–320ms), eased, purposeful; honour reduce-motion.

Build parts (each: compile → emulator-verify → commit):
0. **Colour system** — theme + PYQ coral + accents (foundational).
1. **Home** — tiles show "X / total done" + bar; **Review** = due+mistakes+starred; big **Library** card;
   **Endless mix = SMALL button beside Map** (final add #2), not a big primary.
2. **Feed** — one-row controls (‹Home · Study▾ · Filter) + Filter **sheet** (types/importance/PYQ/subject/
   starred/mistakes, applies to every view) + removable filter tokens + specific empty state. Endless mix
   = the mixed feed.
3. **Cards** — ratings **advance to next** (with a **Settings toggle: advance vs just-highlight**, final
   add #1), same 4 buttons on every recall card, **Read full topic →** (all cards) + **Concept map →**
   when the topic has one, mistake-tags only after a WRONG MCQ. MCQ→topic via `topic_pages.
   related_mcq_ids_json` (parse in Kotlin, 81% coverage).
4. **Library** — first-class page, **Subject/PYQ/5★/4★** filters, **Map button per topic** that has a map.
5. **Passages** — same filters (shared sheet) + shuffled quiz (done).
6. **Motion** — smooth, sensible transitions per above.
7. **Global fixes** — back-nav (done) + one back style + button/screen name match + subject-list cap +
   fill card empty space.
8. **Maps as a LIST view** (final add #3) — Concept Map entry opens a searchable, **Subject/PYQ/5★/4★**-
   filterable LIST of concepts; tap one → its ego-graph. (Today it opens straight to the top hub.)

Then: rebuild → emulator smoke → new APK + GitHub Release → ping.
