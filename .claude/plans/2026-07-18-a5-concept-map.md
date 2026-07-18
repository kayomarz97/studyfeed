# A5 — Concept Map (plan)

**Date:** 2026-07-18 · **Branch:** `dev` · **Stage:** Phase 2 / §8 **A5**
**Golden-loop:** plan-first → design question → build → verify → log.

> Read-first: `BUILD_PROMPT.md` §8 A5 (line 1133), `spec/schema.sql` (concepts/concept_edges/
> interlink_quizzes), the A7 resume note. Design polish deferred to A7; A5 builds a *functional* map.

## 1. Spec (§8 A5)

Force-directed (Fruchterman–Reingold) on a pan/zoom Compose Canvas; **node size = centrality**, **colour
= importance**; tap node → summary + typed edges → **"Quiz me on these links"** pulls `interlink_quizzes`.

## 2. The data reality (measured — this DICTATES the design)

- **37,795 concepts, 67,795 edges.** Full-graph force layout is O(n²)/iteration → impossible on a phone.
  **⇒ the map is an EGO-GRAPH: one focus concept + its immediate neighbours, walkable by tapping.**
- **`concepts.summary_md` is EMPTY (0/37,795).** A finding. "tap node → summary" therefore uses the
  concept's **typed edges** + a link to its **dossier** (A4) — 8,194 concepts have a `topic_page`.
- **10 relations:** associated_with 19k · causes 17.8k · treats 7k · risk_factor_for 5.9k ·
  investigated_by 5.3k · first_line_for 4.5k · mechanism_of 3.1k · contraindicates 2k ·
  complication_of 2k · differential_of 1.1k. Edges carry `relation` + `rationale`.
- **Degree avg 3.6, max 314** (hub diseases). ⇒ neighbours MUST be capped (top-K by importance/
  centrality) with a "+N more" note; never dump 314 nodes.
- **centrality 0.0064–1.0** → node radius. **importance 1–5** → colour (reuse `Importance.color`).
- **interlink_quizzes = 2,998, each tied to an `edge_id`.** Only those edges are quizzable. "Quiz me on
  these links" = the `interlink_quizzes` whose `edge_id` ∈ the focus concept's edges.

## 3. Architecture

Pack-only read-only reader (like `DossierRepository`), a pure force-layout engine (JVM-testable), a
Canvas screen with gestures, a VM. Reuses `Importance.color`, and the A4 in-place viewer pattern for the
quiz.

```
concept/ConceptRepository.kt   (pack-only, READ-ONLY)
  search(q)                    → List<ConceptRef>{id,name,importance,centrality}  (name/alias, like A4)
  topConcepts()                → highest-centrality hubs (the default landing focus + suggestions)
  ego(conceptId, k)            → EgoGraph{ focus, neighbours[≤k]{concept, relation, edgeId, direction},
                                   moreCount }  (k most-important neighbours; degree capped)
  quizzesForConcept(conceptId) → List<interlink refId> (interlink_quizzes.edge_id ∈ concept's edges)
  hasDossier(conceptId)        → topic_page id? (enables "Open dossier" → A4)
  loadInterlink(refId)         → FeedCard.Interlink (for the in-place quiz viewer, no FSRS)

concept/ForceLayout.kt         (PURE Kotlin — Fruchterman–Reingold on ≤K+1 nodes)
  layout(nodeCount, edges, iterations) → List<Offset>  deterministic (seeded circle start, no RNG) →
  unit-testable: nodes spread out, connected nodes pulled together, stable/finite result.

ui/concept/
  ConceptMapScreen.kt   Canvas: draw edges (labelled by relation) + nodes (r=centrality, colour=
                        importance, focus centred). Pan/zoom (graphicsLayer + transformable). Tap a
                        neighbour → recenter (load its ego). Tap focus/any node → info sheet.
  ConceptInfoSheet      name · importance · typed-edge list (relation → neighbour) · "Open dossier"
                        (if any) · "Quiz me on these links" (if any interlink quizzes) → in-place viewer.
  ConceptMapViewModel   holds ConceptRepository; focus state; ego + layout computed off-thread.
```

**Entry point:** a **"Map"** text button in the feed top bar beside "Search" (global nav = A7). Later the
dossier can deep-link ("see concept map"); A5 just needs to be reachable.

**Force layout:** initial positions on a deterministic circle (seeded by index — NO `Math.random`, so
it's reproducible and testable), then N iterations of repulsion (all pairs, fine at ≤~25 nodes) +
attraction (along edges) + cooling. Runs off the main thread; positions animate in.

## 4. Scope guard

- **Ego-graph only** — never attempt the full 37,795-node graph. Cap neighbours (K≈12) + "+N more".
- **Nothing generated** — concepts/edges/quizzes all from the pack. No invented links.
- **No new dependency** — Compose Canvas + pure-Kotlin layout. No graph/physics library.
- **No markdown library / no image loader** rules still hold (reuse existing renderers where text shows).
- Quiz = the pack's `interlink_quizzes`, opened read-only in the in-place viewer (like A4).

## 5. Open design decision → ASK (with a diagram)

The scale forces an ego-graph, but the *interaction model* is a real choice: a **walkable ego-explorer**
(focus + neighbours, tap to walk the graph, search to jump) vs a **static top-hubs overview** (a fixed
map of the ~40 most-central concepts, no infinite walk). Bring this to the user.

## 6. Build order

1. Ask §5; lock the model.
2. `ConceptRepository` (ego/search/quizzes/dossier-link) + `ForceLayout` (+ JVM test for layout).
3. `ConceptMapViewModel` + `ConceptMapScreen` (Canvas, pan/zoom, tap-to-walk) + info sheet.
4. Wire the quiz → in-place interlink viewer; "Open dossier" → A4; feed top-bar entry.
5. Instrumented test on the real pack (ego graph loads, neighbours capped, quizzes resolve, layout
   finite). `verifyOfflineGuarantees`; on-device smoke.
6. Update INDEX/JOURNAL (note the `summary_md`-empty finding); commit; secret-scan before any push.
```
