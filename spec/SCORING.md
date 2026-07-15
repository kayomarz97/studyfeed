# StudyFeed — the importance model

Every playable item gets an integer **`importance ∈ {1,2,3,4,5}`** (sortable in the app), the continuous
**`importance_score ∈ [0,1]`** it came from, and **`importance_signals_json`** explaining *why* (so a card
can say "2019 + 2023 papers · 4 sources · you highlighted it").

**All of it is deterministic arithmetic in `compiler/studyfeed_compiler/importance.py`.** Same inputs →
byte-identical scores, every run. LLMs never rate items one-by-one; Opus (`importance-scorer`) only
*calibrates* the weight vector, *audits* the histogram, and *reviews* the top band. This is a hard
requirement: reproducible ranking is why scoring is arithmetic, not per-item LLM judgement.

Weakness is **not** here — it's personal, lives only in `progress.db`, and is composed on the phone:
`priority = importance_normalized × weakness`. Packs stay shareable; personalization stays local.

---

## Source tiers (from the profile)

| Tier | Kind | `tier_weight` | Key trust (prose) |
|---|---|---|---|
| 1 | `past_paper` (official/recall) | 1.00 | highest (see reliability) |
| 2 | `qbank` | 0.75 | high |
| 3 | `pearls` / `textbook` | 0.65 / 0.60 | prose reference |
| 4 | `reference` | 0.45 | prose reference |

### The printed-key rule — tier is per-source, key trust is per-item
Tier measures trust in a source's **prose as evidence**. It says nothing about an MCQ that ships with its
**own printed answer key.** Any MCQ parsed with an accompanying key is `origin='curated'` and its
`key_trust` is qbank-level **regardless of the source's tier** — a question the author wrote with the
answer the author gave is a curated key. **Never re-derive a printed answer.** `S_tier` (5% of the score)
still uses the source tier; only key trust is decoupled.

**Reliability:** `sources.reliability ∈ {official, recall, unknown}`. A `recall`-sourced key that
contradicts ≥2 independent tier-3/4 sources is **not** silently overwritten — set `mcqs.key_conflict=1`
+ note, list it for the human to arbitrate; the app shows "key disputed".

---

## Signals (all computed, none LLM-rated)

| Signal | Definition | Notes |
|---|---|---|
| `S_pyq` | `Σ over hits: exp(-λ·(current_year − paper_year))`, λ default 0.15/yr | `year=null` → a hit with no recency bonus. **Heaviest weight.** |
| `S_corrob` | `log1p(distinct_work_count)/log1p(N_works)`, scaled by `distinct_publisher_count` | "asked at many sites". Counts **works, not files**. Excludes boilerplate + non-body zones *before* counting (§5i). |
| `S_freq` | `log1p(member_count)`, **damped** | one qbank repeating a Q 20× must not beat 3 qbanks asking it once. |
| `S_emphasis` | `emphasis_mix`: my highlights (`is_highlighted`×salience) + book markers (`book_flagged`×`book_salience`) | both mean "a human flagged this". |
| `S_blueprint` | subject blueprint weight | from the profile. |
| `S_centrality` | PageRank over `concept_edges` | hub concepts (a drug touching 4 specialties) rank higher. |
| `S_tier` | `max(tier_weight)` across asserting sources | 5% — trivial. |
| `S_trend` | recent-skewed → `rising`; all-old → `fading` | `fading` is a **penalty**; `w_trend` may be negative. |

```
raw   = w_pyq·S_pyq + w_corrob·S_corrob + w_freq·S_freq + w_emphasis·S_emphasis
      + w_blueprint·S_blueprint + w_centrality·S_centrality + w_tier·S_tier + w_trend·S_trend
score = clamp(raw / Σ|w|, 0, 1)          # → importance_score
```

Default weights (sum to 1.00, so `score ≈ raw`; the `importance-scorer` may recalibrate and must justify):

```
pyq 0.33 · corrob 0.24 · freq 0.07 · emphasis 0.11 · blueprint 0.07 · centrality 0.06 · tier 0.05 · trend 0.07
```

Signal names map **1:1** onto `profile.importance.weights` keys — no translation.

> **Why file-dedupe is upstream of this (§5b):** if one book registers as two `sources`, every fact gets
> `distinct_work_count=2` and fakes 24%-of-score corroboration across thousands of atoms. `dedupe-merger`
> makes it worse (it unions citations). So dedupe at the **file level, at C1, before any source row
> exists**, and count corroboration over **distinct works** (two editions = one voice).

---

## Bucketing to 1–5 + hard floors

Bucket `importance_score` on absolute thresholds (default `.80/.62/.42/.22` → 5/4/3/2, else 1), then
apply **floors that override**:

- MCQ verified as a past-paper question ⇒ `importance ≥ 4`
- Concept hit in **≥2 distinct past-paper years, ≥1 within `recent_window_years` (default 10)** ⇒ `= 5`
- Atom corroborated by **≥3 distinct works** AND highlighted ⇒ `≥ 4`
- **Zero** corroboration AND **zero** PYQ link ⇒ `≤ 3` (a confident single source is still one source)
- **`fading`** concept (all PYQ hits older than the window, none since) ⇒ `≤ 3` unless ≥3 current sources

> **The recency window is load-bearing.** Without it, a topic asked in 2009 + 2011 and *never since*
> satisfies "≥2 distinct years" and force-promotes to 5★ — outranking a question asked last year. The
> window + `fading` cap make repetition count only when the topic is still live. Dated papers are what
> let us detect this; undated papers would hide it.

Then **audit the histogram**: if any bucket > 45% or the 5★ band is empty, the distribution is degenerate
→ `importance-scorer` recalibrates + re-runs. Final histogram + weights → `pack_manifest`.

---

## Propagation (score once, inherit everywhere)

Score concepts + atoms first, then propagate — don't score asset types independently:

- `flashcard`, `reel` → inherit source atom's score
- `blob` → weighted mean of covered concepts (+bonus if any 5★)
- `mcq` → direct signals win (PYQ status is a property of the MCQ), else its topic
- `interlink_quiz` → mean of the edge's two endpoints
- `topic_page` → **max** of constituent atoms (a dossier is as important as its most important fact)

---

## Worked examples

**A — recent, corroborated, highlighted (→ 5★ by floor).** Asked in 2019 + 2023 papers; 4 distinct works,
3 publishers; cluster of 6; highlighted + book-flagged; hub concept; tier 1.

| signal | value | ×weight |
|---|---|---|
| S_pyq | 0.85 | 0.281 |
| S_corrob | 0.65 | 0.156 |
| S_freq | 0.30 | 0.021 |
| S_emphasis | 0.80 | 0.088 |
| S_blueprint | 0.50 | 0.035 |
| S_centrality | 0.60 | 0.036 |
| S_tier | 1.00 | 0.050 |
| S_trend | 0.70 | 0.049 |

`raw = 0.716`, `score = 0.716` → threshold bucket **4★**. But the **multi-year PYQ floor** (2019 + 2023,
both inside the 10-yr window) forces **`importance = 5`**. `importance_signals_json` records both papers,
the 4 works, the highlight, and "floor: multi_year_pyq".

**B — single-source textbook claim (→ ≤3★ by floor).** One tier-3 book, no PYQ, not highlighted, low
centrality. `raw ≈ 0.20` → threshold **1–2★**; the uncorroborated+no-PYQ floor caps it at **≤3** anyway.
No matter how confident the prose sounds, one source doesn't reach the top band.

**C — retired topic (→ capped at 3★).** Asked 2008 + 2010, nothing since. Threshold might land at 3–4★ on
historical frequency, but every PYQ hit is older than the 10-yr window with none since → `fading` → capped
at **3★**. Exactly the failure mode dated papers let us catch.

---

## Reproducibility guarantee

- Signals are pure functions of pack data; scoring is arithmetic. Re-running on unchanged inputs yields
  **byte-identical** `importance` + `importance_score` + `importance_signals_json`.
- Every Opus verdict (cluster adjudication, calibration) is **cached by content hash**, so recalibration
  is deterministic and re-runs are free.
- The exact weights, thresholds, `recency_lambda`, and `recent_window_years` used for a build are written
  to `pack_manifest.importance_weights_json` — the pack carries its own audit trail.
