# A2 — Study Feed (build first, make excellent)

**Date:** 2026-07-17 · **Branch:** `dev` · **Stage:** §8 A2 (A1 ✅ feature-complete) · **Status:** PLAN — no A2 code written

> Read from disk first: `BUILD_PROMPT.md` §8 A2 + its end-to-end Acceptance list, `spec/SPEC.md`,
> `spec/schema.sql`, `CLAUDE.md`, `.claude/INDEX.md`, and the ⛔ standing orders atop `JOURNAL.md`.
> **Every fact below was read from the SHIPPED pack, not from a doc.**

---

## 0. DECISION LOG — every call, and *why* (continues A1's D1–D8)

> Standing instruction: *"changes/mistakes during the app making are always noted so they don't happen
> again and why that decision was made."* Append the moment a call is made. A decision without its *why*
> is worthless — the next session cannot tell a deliberate constraint from an accident, so it "cleans up"
> the constraint.

| # | Decision | Why (the part that must survive) | Consequence accepted |
|---|---|---|---|
| D9 | **NO standalone Image card. Figures render INLINE on the card they belong to.** (User's call, 2026-07-17 → **PATCH 04**.) | §8 A2 named an Image card and Acceptance #2 demanded "six item types", but the pack has **five** and **zero `image` feed rows** — figures are **attachments**, not items (`atoms.figure_id` 3,630 · `mcqs.figure_id` **1** · `topic_pages.figure_ids_json` 1,461). The compiler did this deliberately; it is finished and dormant. **Rows are authoritative (D5).** Synthesising image cards would mean the app *inventing feed items the pack never authored* — breaking "the pack is the exam" and brushing §runtime's no-generation rule. A figure without its question is a quiz with no answer. | **Acceptance #2's "six" becomes FIVE** — a spec correction (PATCH 04), not a shortfall. Figures reach the feed on ~564 of 32,957 cards (**1.7%**, ~250 distinct); the other ~870 stay reachable via dossiers at **A4**, which is where the compiler put them. |
| D10 | **NO markdown library. A tiny in-house renderer that treats `*`, `<`, `>` as LITERAL text.** | **Not a dependency-hygiene call — a CLINICAL CORRECTNESS one, measured across 68,893 real fields (§3.2).** The pack's "markdown" is essentially plain text: newlines 22.7%, `- ` bullets 2.3%, `1. ` numbered 0.04%. **Headings, links, images, code fences, strikethrough: ZERO.** But medical text is *full* of characters a renderer would seize: every `*italic*` hit is **multiplication in a lab value** (form `<count> * 10^9/L`), and every `>` blockquote is a **threshold** (form `> <N>: <interpretation>`). A markdown renderer eats the asterisks → `<count> 10^9/L`. **It would silently corrupt lab values on a physician's revision cards.** (Forms shown, never pack text — copyright rule.) Risk is real: `>` in **8.9%** of MCQ explanations, `<` in 6.1%, `*` in 3.6% of stems. | Must hand-write list/newline handling (small — that is the entire vocabulary). Will not render exotic markdown; the compiler emits none. **The 2 triple-asterisk `***…***` emphases and 5 table fields are rendered as literal text** — 7 fields in 68,893, versus corrupting ~1,300. |
| D11 | **Hand-rolled windowing over A1's `FeedReader`, not Paging 3** | The D4 reader already does `LIMIT/OFFSET` on an indexed order, and a feed is consumed strictly forwards. **A3 rewrites the ordering anyway** — adopting a paging library now means fitting A3's engine around a library chosen before its requirements exist. No new dependency, nothing new to clear the guard. | ⚠ Unproven on hardware: if `LIMIT/OFFSET` stutters at 32,957 items, cache the window — do not reach for a library reflexively. Revisit at A3 with real requirements. |
| D12 | **Figures via `BitmapFactory.decodeFile` + a small LRU. NEVER Coil/Glide.** | Figures are local PNGs in `filesDir`; an image loader's whole value (HTTP fetch, network cache) is **moot**. Worse, **Coil's standard artifact depends on OkHttp — the guard's denylist would fail the build, correctly.** An image loader that *can* fetch URLs has no business in an app that must never touch a network. | Hand-rolled decode + cache + sampling. That is the right amount of work for ~250 feed-reachable local PNGs. |

---

## 1. What A2 is

**Plain:** The thing the user actually opens. An endless vertical feed of study cards — swipe up for the
next one — where every card knows how important it is and rating it teaches the app when to show it again.

**Technical:** An infinite vertical pager over 32,957 `feed_items`, with a composable per item type, an
importance indicator + PYQ badge on every card, filter chips and a sort control built from the pack, and
one-tap star/tag. Ratings feed FSRS-6 and write to `progress_<EXAM>.db`.

**Scope boundary that matters:** **A3 owns the ordering engine** (FSRS due pool, variety rules, drip).
A2 renders the feed and does the simple ordering the pack already provides (importance DESC). Standing
order #2: no resequencing — do not build A3's engine here.

---

## 2. Ground truth — the feed, as it actually is

| Fact | Value |
|---|---|
| `feed_items` | **32,957** |
| item types **actually present** | `mcq` 14,455 · `flashcard` 9,507 · `reel` 3,000 · `interlink` 2,998 · `blob` 2,997 |
| item types the spec names | reel · blob · **image** · flashcard · mcq · interlink (**six**) |
| importance spread | 5★ **1,046** · 4★ **23,452** · 3★ 305 · 2★ 3,736 · 1★ 4,418 |
| PYQ | `is_pyq=1` → **4,306** · `is_pyq=0` → 28,651 |
| `feed_items` columns | `id, item_type, ref_id, subject_id, importance, importance_score, is_pyq` |

**Note the importance shape: 71% of the feed is 4★.** That is the documented, audited outcome (the Opus
importance-scorer called the histogram *defensible* — the bimodality is the intended `pyq_min` floor).
It matters for A2 because **"4★+" as a filter selects 74% of the deck — nearly a no-op**, while "5★ only"
selects 1,046 items (3%). The filters are still exactly what §8 A2 requires; just don't be surprised.

---

## 3. ⛔ Settle BEFORE code

### 3.1 SPEC-vs-REPO: the "Image" card type has NO data, and figures are attachments 🔴

§8 A2 specifies a card type:
> **Image** — figure + caption; pinch-zoom; tap → linked atom.

and Acceptance #2 requires *"Mixed feed shows all **six** item types"*. `schema.sql` agrees:
`item_type TEXT, -- reel | blob | image | flashcard | mcq | interlink`.

**The pack has five types and ZERO `image` rows.** Figures are not feed items — they are **attachments**:

| Path to a figure | Count |
|---|---|
| `feed_items` of type `image` | **0** |
| `mcqs.figure_id` | **1** (one, in 14,455) |
| `flashcards → atoms.figure_id` | 424 cards → **196 distinct figures** |
| `reels → atoms.figure_id` | 140 cards → **74 distinct figures** |
| `topic_pages.figure_ids_json` | 1,461 dossiers (**A4's** reader, not A2) |

So of 1,121 vision-captioned figures, only **~250 distinct are reachable from the feed at all**, on ~564
of 32,957 cards (**1.7%**). The rest live in dossiers → **A4**.

**This is not a bug to fix in A2.** The compiler is done and dormant; `figures.py` deliberately linked
figures to atoms/dossiers, never emitting standalone image feed rows. **Standing order #1 says surface,
never silently reconcile** — so:

**Ask:** which reading is right?
- **(a) No standalone Image card; figures render INLINE** on any card whose atom carries a `figure_id`
  (flashcard/reel), plus the MCQ that has one. Acceptance #2's "six" becomes **five** — a correction to
  the acceptance list, since the sixth type has no data by construction. *(My recommendation — it matches
  D5: rows are authoritative, and it makes all ~250 feed-reachable figures visible where they're relevant
  rather than as context-free flashcards.)*
- **(b) Synthesise image feed rows in the app** from `figures` so an "Image" card type exists. Rejected
  unless you want it: the app would be **inventing feed items the pack never authored**, which breaks
  "the pack is the exam" — and §runtime forbids generation.
- **(c) Leave it for a future compiler run** to emit `image` feed rows. Requires waking a finished
  compiler; A2 ships five types meanwhile.

### 3.2 ✅ DECIDED (D10) — NO markdown library. It would corrupt lab values. 🔴

**Measured, not guessed: 68,893 real markdown fields scanned across all 11 `*_md` columns.**

*What markdown the pack ACTUALLY contains:*

| construct | occurrences | % of fields |
|---|---|---|
| newline | 141,769 | **22.69%** |
| `- ` bullet | 12,801 | **2.31%** |
| `1. ` numbered | 107 | 0.04% |
| `\|` table | 25 (5 fields) | 0.01% |
| `***…***` | 2 | 0.00% |
| **heading · link · image · `code` · fence · strikethrough · `_italic_`** | **0** | **ABSENT** |

*What the "markdown" hits turned out to be — this is the decisive part:*

Every `*italic*` match is **multiplication in a lab value**, and every `>` blockquote is a **threshold**.
The forms below are illustrative, **not copied pack text** (copyright rule: docs never quote book content):

```
"<cell count> * 10^9/L"  and  "<count> * 10^9/L (<low> - <high> * 10^9/L)"   <- NOT italic. Multiplication.
"> <N>: <interpretation>"                                                    <- NOT a quote. A threshold.
```

**A markdown renderer takes the text between two asterisks as italic AND EATS THE ASTERISKS.**
`<count> * 10^9/L` renders as `<count> 10^9/L` — **the multiplication silently disappears
from a lab value on a physician's revision card.** A `> <N>` threshold becomes an indented
quotation, losing "greater than". These are **wrong facts**, not ugly ones.

**Scale of the corruption a library would cause:** `>` appears in **8.9% of MCQ explanations** (1,283
fields), `<` in **6.1%** (884), `*` in **3.6% of stems** (516).

**Decision: a tiny in-house renderer.** Support exactly what exists — **newlines, `- ` bullets, `1. `
numbered lists** — and treat `*`, `<`, `>`, `~`, `` ` `` as **literal characters**. That is not a
compromise: for this corpus it is strictly *more correct* than any general markdown engine, and it needs
no dependency and nothing to clear the guard.
**Cost, stated honestly:** the 2 `***…***` emphases and 5 table fields render as literal text — 7 fields
out of 68,893, against corrupting ~1,300. Right trade.

⚠ **Never "upgrade" this to a markdown library later.** It would look like a tidy-up and would silently
re-introduce the corruption. This decision is about the corpus, not about effort.

### 3.3 DECISION: paging 32,957 items 🟡

- **(a) `androidx.paging` (Paging 3).** The standard answer; integrates with Compose `LazyColumn`. New
  dependency; must clear the guard.
- **(b) Hand-rolled windowing over `FeedReader.feed(limit, offset)`.** A1 already built that query. A
  feed is consumed strictly forwards, one card at a time — a simple sliding window is genuinely
  sufficient, and `LIMIT/OFFSET` on an indexed order is fast.

**Recommendation: (b) for A2.** It uses the D4 reader we already proved, adds no dependency, and A3 will
rewrite the ordering anyway — pulling in Paging 3 now risks fitting A3's engine around a library chosen
before its requirements exist. **⚠ Needs the real device to confirm** `LIMIT/OFFSET` scroll is smooth.

### 3.4 TRAP: image loading must NOT bring a networking library 🔴

Figures are local PNGs in `filesDir/packs/<exam>/figures/`. The reflex is Coil or Glide — **both would be
blocked by our own guard, correctly**: Coil's standard artifact depends on OkHttp, which is on the
denylist (`CheckDependencyDenylistTask` names the offender and the path that pulled it in). That is not
an obstacle to route around — an image loader that can fetch URLs has no business in an app that must
never touch a network. **Use `BitmapFactory.decodeFile` + a small LRU cache.** No network client can
enter the classpath, and the files are local anyway, so the loader's whole value proposition (HTTP,
caching, fetching) is moot here.

### 3.5 DECISION: FSRS wiring — where does the schedule get written? 🟡

§8 A2: reels → got-it/shaky; flashcards → 4-button rating; MCQ → answer → FSRS; wrong → mistake ledger.
A0 already shipped `FsrsScheduler` (java-fsrs 1.0.0, 3 tests incl. upstream's oracle).

- Every rating: **write `fsrs_cards` + append `reviews` via Room**, then advance the pager.
- `reviews` is append-only — it is the raw history A7's optional local FSRS-weight optimisation needs.
  Losing it would make that impossible later, so it is written from day one even though nothing reads it
  yet.
- Wrong MCQ → `mistakes` row (§8 A6's ledger). Written at A2 because the data must exist before A6 can
  render it.
- **Writes must not block the swipe.** Rating → write on IO → pager moves immediately. A card that waits
  on a DB write to advance will feel broken on a 120Hz panel.

### 3.6 The AMOLED question — the one thing only your phone can answer

§8 A2: *"made excellent"* — infinite pager, swipe, tap-to-flip, AMOLED dark. §8 A7 fixes the palette
(pure `#000000`, dynamic colour off). **This is the reason the app moved to the laptop + a real device in
the first place** (JOURNAL, §0 divergence). The S23 Ultra's panel is exactly the target.

**What the emulator CAN prove:** correctness, filters, sort, FSRS writes, no-crash, no-network.
**What only the phone can:** whether `#000000` reads as *depth* rather than *void*, whether swipe feel is
right, whether 32,957-item scroll is smooth, and the real cold-start number. Those are **judgements**, and
I will not claim them from an emulator.

---

## 4. Build order (each step ends green)

1. **Scan the pack's real markdown vocabulary** (zero cost, decides §3.2 on evidence not taste).
2. **The feed query + windowing** over the D4 reader: item + its payload + importance + PYQ years, one
   query per page. JVM/emulator-testable.
3. **Markdown → AnnotatedString** (per §3.2) + unit tests against **real strings pulled from the pack**.
4. **Card composables**: Reel, Flashcard, MCQ, Blob, Interlink (+ inline figure per §3.1).
5. **Importance indicator + PYQ badge + the "why" sheet** from `importance_signals_json` (Acceptance 3).
6. **Filters + sort** (Acceptance 4 — Priority must *differ* from Importance ↓, proving weakness folds in).
7. **Star + tag in one tap** (Acceptance 5), persisted, visible to A6's filters.
8. **FSRS wiring + mistake ledger** (Acceptance 6).
9. **Blob read → quiz-back a few cards later + re-read next session** (Acceptance 7) — **in-app only, no
   notification, no alarm**; the guard enforces that.
10. **On the phone**: AMOLED judgement, swipe feel, scroll perf, cold start.

## 5. Acceptance (from §8's end-to-end list — each provable)

- [ ] (2) Mixed feed shows every item type the pack HAS; every card shows 1–5 importance; PYQs badge years.
- [ ] (3) Tapping the importance indicator renders the "why" from `importance_signals_json`.
- [ ] (4) "5★ only" (1,046) and "PYQ only" (4,306) filter correctly; Importance ↓ sorts; **Priority
      differs from Importance ↓** — the proof weakness is composed locally.
- [ ] (5) ⭐/tag in one tap; survives restart.
- [ ] (6) MCQ wrong → explanation + per-distractor why → `mistakes` row → resurfaces via FSRS.
- [ ] (7) Blob read → quiz-back later → re-read next session, **in-app, no notification**.
- [ ] (9) **Airplane mode: everything works.** Force-stop/reopen: no background process ran.
- [ ] Zero exam strings; zero hardcoded asset assumptions (a pack without blobs offers no blob cards).
- [ ] `verifyOfflineGuarantees` green; JVM + instrumented suites green (counts from XML).

## 6. Risks

- **71% of the feed is 4★** → "4★+" is nearly a no-op filter. Expected, documented, not a bug.
- **Markdown rendering is the sleeper**: it touches all 32,957 cards, and a bad choice is expensive to
  undo. Hence §3.2's measure-first.
- **The pager must not stutter.** Only the phone can prove it (D6). If `LIMIT/OFFSET` is slow, cache the
  window — do not reach for a library reflexively.
- **A3 scope creep.** A2 renders and simply orders; the ordering *engine* is A3. Standing order #2.
- **The stale-spec pattern continues** (§3.1 is the 4th such find in two days). Trust the DB.
