# A4 — Topic Dossier Reader (plan)

**Date:** 2026-07-17 · **Branch:** `dev` · **Stage:** Phase 2 / §8 **A4**
**Golden-loop:** plan-first (this file) → functional questions → build → verify → log.

> Read-first: `BUILD_PROMPT.md` §8 A4 (line 1129), `spec/schema.sql` (topic_pages/topic_sections/
> concepts), the A7 resume note. **Design/layout polish is deferred to A7** — A4 builds a *functional*
> reader; the A7 backlog is about the FEED, not the dossier, so A4 stays lean and correct.

## 1. Spec (§8 A4)

Library/Search screen (**sortable by importance**): search entity by name/alias → open pre-built
`topic_pages.body_md` as **one long scrollable page** with **inline diagrams**, **collapsible
per-subject sections** (`topic_sections`) + **jump-nav**, **links to that entity's MCQs/flashcards**.
**Pure render — nothing generated.** Acceptance 8: "Search a drug → dossier shows cross-subject sections
+ diagrams, instantly, no generation."

## 2. The data (measured from the real pack — counts only, no content)

- **8,194 `topic_pages`** — EVERY one fully populated: `title`, `body_md` (avg ~2,456 chars),
  `aliases_json`, `subjects_covered_json`, `figure_ids_json` (~1.5 figures/page), `related_mcq_ids_json`
  (~15.5/page), `related_flashcard_ids_json`, `importance` + `importance_signals_json`. All linked to a
  `concept_id`.
- **20,796 `topic_sections`** (~2.5/page), ALL with a `heading` — the collapsible per-subject sections
  (`topic_page_id`, `subject_id`, `heading`, `body_md`, `figure_ids_json`, `ord`).
- Importance histogram: 5★ 177 · 4★ 4,158 · 3★ 2,795 · 2★ 957 · 1★ 107.
- **1,121 figures** on disk (the shipped subset); a missing PNG degrades to no-figure (as in A2).

## 3. Architecture

Pure pack reads (no progress join needed to render a dossier), so a dedicated **read-only pack
connection**, mirroring `FeedRepository`'s pattern (and its figure-resolution: `figures.path` relative
to the pack dir, verified on disk — never Coil/Glide, D12). Reuse the existing `PackMarkdown`/
`MarkdownText` renderer (NO markdown library — PATCH 04) and `FigureImage` + `CardChrome` importance
indicator.

```
dossier/DossierRepository.kt   (new; opens the pack READ-ONLY, no ATTACH — dossiers are pack-only)
  search(query, sort, limit)      → List<DossierListItem>{topicPageId,title,subjects,importance,score}
                                     match on title + aliases_json  (+ body_md if the user opts in)
  topDossiers(sort, limit)        → default list when the search box is empty (importance ↓)
  load(topicPageId)               → Dossier{ title, bodyMd, importance, signals, subjectsCovered,
                                     figures[], sections[]{heading,bodyMd,figures[],subjectName},
                                     relatedMcqs[]{feedItemId,importance,subject}, relatedFlashcards[] }
  loadCardByRef(type, refId)      → one FeedCard (reuses FeedRepository loaders) for the detail viewer

ui/dossier/
  LibraryScreen.kt      search field + importance sort toggle + LazyColumn of dossier rows
  DossierScreen.kt      ONE long LazyColumn: header (title+importance+subjects) · jump-nav (headings →
                        scrollToItem) · body_md · page figures · each section (collapsible: heading row
                        toggles → body + figures) · "Related questions/flashcards" (tap → detail viewer)
  DossierViewModel.kt   loads off-thread; holds the pack-reader for the session
  ItemDetailScreen.kt   a single FeedCard rendered via the existing A2 FeedCards composable (opened from
                        a related link; proves "links to MCQs/flashcards", Acceptance 8)
```

**Navigation:** `MainActivity.Root` currently switches `Screen.Feed ↔ Manage` (a simple enum `when`).
Add `Library`, `Dossier(topicPageId)`, `ItemDetail(type,refId)`. Entry point for A4 = a **book icon in
the Feed top bar** → Library. ⚠ Global navigation (bottom tabs / home page) is an **A7 redesign item
(backlog 2.1)** — A4 adds the *minimal* entry so the reader is reachable now, without committing to the
final nav chrome. A5 (concept map) and the feed ("read up on this") will later call `openDossier(id)`.

**Inline figures:** `body_md` has no figure placeholders (checked: figures are a flat `figure_ids_json`),
so "inline" = render `body_md`, then the page's figures beneath it; each section renders its own
`body_md` then its `figure_ids`. Placement refinement is A7 polish; A4 gets them on the page.

## 4. Scope guard (what A4 does NOT do)

- **Nothing generated** — every byte comes from `topic_pages`/`topic_sections`/`figures`. No LLM, no
  synthesis, no invented links.
- **No markdown library** (PATCH 04) — reuse `PackMarkdown`; `*`/`<`/`>` stay literal.
- **No image/network loader** — `FigureImage` (`BitmapFactory.decodeFile` + LRU), D12.
- **No new runtime dependency.** No exam-specific strings (subjects come from the pack).
- **No feed-design changes** — the A7 feed backlog is untouched here.

## 5. LOCKED (user, 2026-07-17)

1. **Search = name/alias + full `body_md` text.** `search 'warfarin'` finds the Warfarin page, its
   aliases, AND any page whose body mentions it. LIKE over title/aliases/body (8,194 rows, <100 ms, no
   FTS). Rank: title/alias hits above body-only hits, then importance ↓.
2. **Related tap = in-place single-card detail viewer** (`ItemDetailScreen`, reuses A2 `FeedCards`),
   Back returns to the same dossier spot. NOT a jump into the main feed.

## 6. Build order

1. Ask §5; lock defaults.
2. `DossierRepository` + row/detail data classes; unit-test the JSON parsing (JVM) where possible.
3. `LibraryScreen` + `DossierViewModel` + `DossierScreen` (+ jump-nav, collapsible sections, figures).
4. `ItemDetailScreen` reusing A2 `FeedCards`; wire related links.
5. `MainActivity` routes + Feed top-bar entry point.
6. Instrumented test on the real pack: search hits, a dossier loads full (body + sections + figures +
   related), figure paths resolve. `verifyOfflineGuarantees`; on-device smoke (Acceptance 8: instant).
7. Update `.claude/INDEX.md`, `JOURNAL.md`; commit; **secret-scan before any push** (no real content).
```
