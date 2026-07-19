"""C6 — PYQ analysis + trend (BUILD_PROMPT §C6; S_pyq / S_trend in SCORING).

A question is a PYQ if its C5 cluster contains any past_paper member — so qbank/textbook questions that
were also asked in the papers inherit PYQ credit through the cluster. Per question we emit:
  - is_pyq, pyq_hit_count (distinct past_paper WORKS, canonicalised so book-slices don't double-count),
  - pyq_years, pyq_last_year,
  - S_pyq = Σ over hits exp(-λ·(current_year − paper_year))   [λ = recency_lambda; year=null → live-window baseline],
  - pyq_trend ∈ {rising, steady, fading, none}: mean hit-year vs corpus mean; 'fading' = nothing inside the
    recent window (a penalty at scoring, per SCORING.md).
Staleness (key_conflict on guideline_sensitive concepts) DEFERS to post-C8 — it needs the concept graph.
"""
from __future__ import annotations

import json
import math
from collections import Counter

from . import cluster as C
from . import workgroups
from .config import WORK_DIR

CURRENT_YEAR = 2026    # build year (deterministic; the papers span 2015–2026)
LAMBDA = 0.15          # profile.scoring.recency_lambda
RECENT_WINDOW = 10     # profile.scoring.recent_window_years — the "still live" window

PYQ_OUT = WORK_DIR / "mcq_pyq.jsonl"


def _load_class():
    year, kind = {}, {}
    for l in open(WORK_DIR / "classification.jsonl", encoding="utf-8"):
        r = json.loads(l)
        year[r["work_id"]] = r.get("year")
        kind[r["work_id"]] = r.get("kind")
    return year, kind


def pyq(_cfg=None) -> dict:
    mcqs = C.load_mcqs()
    year, kind = _load_class()
    canon = workgroups.canonical_map()
    clusters = [json.loads(l) for l in open(C.CLUSTERS_OUT, encoding="utf-8")]

    # Past-paper hits per cluster, deduped by canonical work (slices of one paper set count once).
    cluster_hits = []
    all_hit_years = []
    for cl in clusters:
        hits = {}
        for m in cl["member_idx"]:
            w = mcqs[m]["source_id"]
            if kind.get(w) == "past_paper":
                hits[canon.get(w, w)] = year.get(w)
        cluster_hits.append(hits)
        all_hit_years += [y for y in hits.values() if y]
    corpus_mean = sum(all_hit_years) / len(all_hit_years) if all_hit_years else float(CURRENT_YEAR)

    per_mcq, trend_ct = {}, Counter()
    for cl, hits in zip(clusters, cluster_hits):
        hit_years = [y for y in hits.values() if y]
        years = sorted(set(hit_years))
        is_pyq = len(hits) > 0
        last_year = max(years) if years else None
        s_pyq = sum(math.exp(-LAMBDA * (CURRENT_YEAR - y)) for y in hit_years) \
            + math.exp(-LAMBDA * RECENT_WINDOW) * (len(hits) - len(hit_years))   # year=null hits: baseline
        if not is_pyq:
            trend = "none"
        elif last_year is not None and last_year < CURRENT_YEAR - RECENT_WINDOW:
            trend = "fading"                                   # nothing inside the live window
        elif hit_years and (sum(hit_years) / len(hit_years)) >= corpus_mean:
            trend = "rising"                                   # recent-skewed vs corpus
        else:
            trend = "steady"
        trend_ct[trend] += 1
        for m in cl["member_idx"]:
            per_mcq[m] = {"idx": m, "is_pyq": is_pyq, "pyq_hit_count": len(hits),
                          "pyq_years": years, "pyq_last_year": last_year,
                          "pyq_trend": trend, "s_pyq": round(s_pyq, 4)}

    if PYQ_OUT.exists():
        PYQ_OUT.unlink()
    with open(PYQ_OUT, "w", encoding="utf-8") as f:
        for m in range(len(mcqs)):
            f.write(json.dumps(per_mcq[m], ensure_ascii=False) + "\n")

    pyq_mcqs = sum(1 for v in per_mcq.values() if v["is_pyq"])
    multi_year = sum(1 for h in cluster_hits if len({y for y in h.values() if y}) >= 2)
    recent = sum(1 for h in cluster_hits
                 if any(y and y >= CURRENT_YEAR - RECENT_WINDOW for y in h.values()))
    stats = {"mcqs": len(mcqs), "pyq_mcqs": pyq_mcqs, "corpus_mean_year": round(corpus_mean, 1),
             "trend_by_cluster": dict(trend_ct), "multi_year_pyq_clusters": multi_year,
             "clusters_with_recent_hit": recent}
    print("[C6] " + json.dumps(stats), flush=True)
    return stats


if __name__ == "__main__":
    pyq()
