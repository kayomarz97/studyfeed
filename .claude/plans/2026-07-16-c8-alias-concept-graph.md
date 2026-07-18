# Plan — C8: Alias normalization + concept graph

**Date:** 2026-07-16 · **Branch:** dev · **Stage:** C8 (graph.py + new alias.py; both from scratch)
**Spec:** BUILD_PROMPT §C8 (line 956) + §5e (alias) + §4 concept-grapher + schema `concepts`/`concept_edges`.

## Plain-language

Turn the 86,702 merged facts into a **brain-map**: one node per medical concept (drug, disease,
finding), joined by typed arrows ("treats", "causes", "contraindicated in"…). First make sure one
concept = one node even when books spell it differently (frusemide vs furosemide, haemoglobin vs
hemoglobin, adrenaline vs epinephrine) — that's alias normalization. Then score each node by how
"central" it is (a drug touching 4 specialties matters more) — that feeds importance in C9.

## The pipeline (and where money/gates are)

- **C8a-1 · Deterministic alias key (FREE, build+test now).** Pure code (§5e-a): `canonical_key(name)`
  = casefold, `ae|æ→e`, `oe|œ→e`, `-ise→-ize`, `-our→-or`, `-re→-er`, strip punctuation. Match key
  only — **never rewrite the UK display spelling**. Plus the synonym resolver from
  `profile.nomenclature.alias_hints` (§5e-b: the 2003 BAN→rINN switch, adrenaline/epinephrine, UK↔US).
- **C8a-2 · Concept extraction + novel-alias proposals (LLM, small).** Extract the concept mentions from
  atoms, fold by `canonical_key` + synonym map. Anything still-unmatched that *looks* like a synonym →
  `dist/alias_proposals.yaml`. **⛔ GATE:** you review proposals before any merge (a wrong merge
  corrupts the graph invisibly, §5e).
- **C8b · Typed grounded edges (MASS LLM — ⛔ BUDGET GATE).** `concept-grapher`: per atom, extract
  `(src, relation, dst)` triples over the canonical nodes. Relations: causes / treats /
  contraindicates / associated_with / differential_of / complication_of / investigated_by /
  mechanism_of / risk_factor_for / first_line_for. Every edge grounded (source_id). Set
  `guideline_sensitive=1` on management/target/threshold concepts (feeds C6 staleness + C9).
- **C8c · PageRank centrality (FREE).** PageRank over `concept_edges` → `concepts.centrality` (S_centrality,
  weight 0.06 in importance). Verify a known multi-spelling entity (e.g. adrenaline) = ONE node.

## Cost estimate (concept extraction, C8b — the gated spend)

86,702 merged atoms, batched ~10/call → ~8,700 calls. Per C4 analogue:
- **Haiku** (batch 0.5×): ~$8–15. Fast, adequate for triple extraction; the spec routes concept-grapher
  to *sonnet* for relation accuracy though.
- **Sonnet** (batch 0.5×): ~$25–45. Better typed-relation precision.
Alias-proposal LLM pass (C8a-2, novel candidates only): trivial (~$1–3).

## Order / dependencies

C8a-1 (free) → C8a-2 (extract + ⛔ alias gate) → C8b (⛔ budget gate, edges) → C8c (free PageRank).
C8 output (`concepts.centrality`, guideline_sensitive) feeds **C9 importance** and post-C8 staleness.

## Decisions needed from the user

1. **Model for concept/edge extraction** (Haiku ~$10 vs Sonnet ~$35) — precision vs cost.
2. Confirm proceed to the LLM stages, or build only the free deterministic foundation for now.

## Files

- NEW `compiler/studyfeed_compiler/alias.py` — canonical_key + synonym resolver + proposals writer.
- `compiler/studyfeed_compiler/graph.py` (stub → implement) — concept/edge extraction + PageRank.
- Reuses `workgroups.canonical_map` (source→work), `config` (profile alias_hints), `llm` (Batch API).
- Outputs: `work/concepts.jsonl`, `work/concept_edges.jsonl`, `dist/alias_proposals.yaml`.
