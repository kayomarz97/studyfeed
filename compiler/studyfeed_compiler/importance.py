"""C9 — THE deterministic importance engine (spec/SCORING.md). Signals → weighted sum → bucket 1..5 →
hard floors (incl. the recency window). Same inputs ⇒ byte-identical scores; importance_signals_json
records the why. All signals are COMPUTED, none LLM-rated (the opus importance-scorer only calibrates).

v1 scores MCQs — the playable content, ~83% signal-covered (pyq+corrob+freq+trend+tier+blueprint).
`emphasis` (needs C3 highlights, skipped) and `centrality` (needs an MCQ↔concept link) are 0 here; the
PYQ/corroboration FLOORS carry the high-value items regardless. Atom/concept scoring needs the
atom↔concept↔pyq linkage → deferred to v2 (see .claude/plans/2026-07-16-c9-importance.md).
"""
from __future__ import annotations

import json
import math
import re
from collections import Counter

from . import alias as A
from . import workgroups
from .config import Config, WORK_DIR

CURRENT_YEAR = 2026
MCQ_IMPORTANCE_OUT = WORK_DIR / "mcq_importance.jsonl"
CONCEPT_IMPORTANCE_OUT = WORK_DIR / "concept_importance.jsonl"
ATOM_IMPORTANCE_OUT = WORK_DIR / "atom_importance.jsonl"
_TREND = {"rising": 1.0, "steady": 0.5, "fading": 0.0, "none": 0.5}


def _pctl(vals: list[float], p: float) -> float:
    xs = sorted(v for v in vals if v > 0)
    if not xs:
        return 1.0
    return xs[min(len(xs) - 1, int(p * len(xs)))] or 1.0


def _load_sources() -> dict[int, dict]:
    out = {}
    for l in open(WORK_DIR / "classification.jsonl", encoding="utf-8"):
        r = json.loads(l)
        out[r["work_id"]] = {"tier_weight": r.get("tier_weight", 0.5),
                             "subject": (r.get("subject") or "").strip().lower()}
    return out


def _load_emphasis() -> dict[tuple[int, str], float]:
    """C3 book-emphasis: (source_id, page) -> book_salience. Empty if C3 not run."""
    m, p = {}, WORK_DIR / "emphasis.jsonl"
    if p.exists():
        for l in open(p, encoding="utf-8"):
            r = json.loads(l)
            m[(r["source_id"], str(r["page"]))] = float(r["book_salience"])
    return m


def _emphasis(emph: dict, mix: dict, source_id: int, page_ref) -> float:
    """S_emphasis for an item. is_highlighted=0 (no user highlights, C3), so only the book_flag term."""
    m = re.match(r"\d+", str(page_ref or ""))
    bs = emph.get((source_id, m.group() if m else str(page_ref)), 0.0)
    return mix.get("book_flag", 0.4) * bs


def score_mcqs(cfg: Config | None = None) -> dict:
    cfg = cfg or Config.for_exam("mrcp_part1")
    imp = cfg.importance
    w, th, fl = imp["weights"], imp["thresholds"], imp["floors"]
    window = int(imp.get("recent_window_years", 10))
    sources = _load_sources()
    # blueprint: subject name -> normalised weight (0..1)
    subj_w = {(s.get("name") or "").strip().lower(): float(s.get("weight", s.get("blueprint_weight", 0)))
              for s in cfg.subjects}
    max_sw = max([*subj_w.values(), 1.0])

    mcqs = [json.loads(l) for l in open(WORK_DIR / "mcqs.jsonl", encoding="utf-8")]
    pyq = {r["idx"]: r for r in (json.loads(l) for l in open(WORK_DIR / "mcq_pyq.jsonl", encoding="utf-8"))}
    corr = {r["idx"]: r for r in (json.loads(l) for l in open(WORK_DIR / "mcq_corrob.jsonl", encoding="utf-8"))}

    n_works = max(1, len({m["source_id"] for m in mcqs}))
    p95_freq = _pctl([corr.get(i, {}).get("member_count", 1) for i in range(len(mcqs))], 0.95)
    p95_pyq = _pctl([pyq.get(i, {}).get("s_pyq", 0.0) for i in range(len(mcqs))], 0.95)
    trend_val = {"rising": 1.0, "steady": 0.5, "fading": 0.0, "none": 0.5}
    emph, mix = _load_emphasis(), imp.get("emphasis_mix", {"my_highlight": 0.6, "book_flag": 0.4})

    rows, hist = [], Counter()
    for i, m in enumerate(mcqs):
        p, c = pyq.get(i, {}), corr.get(i, {})
        src = sources.get(m["source_id"], {})
        dwc = c.get("distinct_work_count", 1) or 1
        pubs = c.get("distinct_publisher_count", 1) or 1
        s_corrob = min(1.0, (math.log1p(dwc) / math.log1p(n_works)) * (1 + 0.10 * (pubs - 1)))
        s_freq = min(1.0, math.log1p(c.get("member_count", 1)) / math.log1p(1 + p95_freq))
        s_pyq = min(1.0, p.get("s_pyq", 0.0) / p95_pyq)
        s_trend = trend_val.get(p.get("pyq_trend", "none"), 0.5)
        s_tier = float(src.get("tier_weight", 0.5) or 0.5)
        s_blue = (subj_w.get(src.get("subject", ""), 0.0) / max_sw) if src.get("subject") in subj_w else 0.5
        signals = {"pyq": round(s_pyq, 4), "corrob": round(s_corrob, 4), "freq": round(s_freq, 4),
                   "emphasis": round(_emphasis(emph, mix, m["source_id"], m.get("page_ref")), 4),
                   "blueprint": round(s_blue, 4), "centrality": 0.0,
                   "tier": round(s_tier, 4), "trend": s_trend}
        raw = sum(w[k] * signals[k] for k in w)

        imp_score = min(1.0, raw)
        bucket = (5 if imp_score >= th["five"] else 4 if imp_score >= th["four"]
                  else 3 if imp_score >= th["three"] else 2 if imp_score >= th["two"] else 1)

        # ── hard floors (override the bucket) ──
        floor = None
        years = p.get("pyq_years") or []
        recent_hit = (p.get("pyq_last_year") or 0) >= CURRENT_YEAR - window
        if len(set(years)) >= 2 and recent_hit:
            bucket, floor = fl["multi_year_pyq_forces"], "multi_year_pyq"      # ⇒ 5
        elif p.get("is_pyq"):
            if bucket < fl["pyq_min_importance"]:
                bucket, floor = fl["pyq_min_importance"], "pyq_min"            # ⇒ ≥4
        if p.get("pyq_trend") == "fading" and dwc < 3 and bucket > fl["fading_max"]:
            bucket, floor = fl["fading_max"], "fading_cap"                     # ⇒ ≤3
        if not p.get("is_pyq") and dwc <= 1 and bucket > fl["uncorroborated_max"]:
            bucket, floor = fl["uncorroborated_max"], "uncorroborated_cap"     # ⇒ ≤3

        hist[bucket] += 1
        rows.append({"idx": i, "importance": bucket, "importance_score": round(imp_score, 4),
                     "signals": signals, "raw": round(raw, 4), "floor": floor})

    if MCQ_IMPORTANCE_OUT.exists():
        MCQ_IMPORTANCE_OUT.unlink()
    with open(MCQ_IMPORTANCE_OUT, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    n = len(rows)
    hist_pct = {b: round(100 * hist[b] / n, 1) for b in range(1, 6)}
    degenerate = any(hist[b] / n > 0.45 for b in range(1, 6)) or hist[5] == 0
    floors_fired = Counter(r["floor"] for r in rows if r["floor"])
    stats = {"mcqs_scored": n, "histogram": {b: hist[b] for b in range(1, 6)},
             "histogram_pct": hist_pct, "floors_fired": dict(floors_fired),
             "degenerate_distribution": degenerate, "n_works": n_works}
    print("[C9] " + json.dumps(stats, ensure_ascii=False), flush=True)
    return stats


def _bucket(score: float, th: dict) -> int:
    return (5 if score >= th["five"] else 4 if score >= th["four"] else 3 if score >= th["three"]
            else 2 if score >= th["two"] else 1)


def score_concepts(cfg: Config | None = None):
    """Score concepts. centrality is direct; corroboration = distinct canonical works among the edges
    mentioning it; PYQ is APPROXIMATE — multi-word concept names matched in PYQ MCQ stems (§C8 note).
    Returns (stats, concept_centrality{cid:val}, concept_pyq{cid:{...}}) for atom inheritance."""
    cfg = cfg or Config.for_exam("mrcp_part1")
    imp = cfg.importance
    w, th, fl = imp["weights"], imp["thresholds"], imp["floors"]
    window = int(imp.get("recent_window_years", 10))
    cmap, al, sources = workgroups.canonical_map(), A.Aliases.from_profile(), _load_sources()
    concepts = [json.loads(l) for l in open(WORK_DIR / "concepts.jsonl", encoding="utf-8")]
    key2id = {c["canonical_key"]: c["id"] for c in concepts}

    works, tier = {}, {}
    for l in open(WORK_DIR / "graph_edges_raw.jsonl", encoding="utf-8"):
        e = json.loads(l)
        sid = e.get("source_id")
        cw = cmap.get(sid, sid) if sid is not None else None
        tw = float(sources.get(sid, {}).get("tier_weight", 0.5) or 0.5)
        for side in ("s", "o"):
            cid = key2id.get(al.key(e[side]))
            if cid is None:
                continue
            if cw is not None:
                works.setdefault(cid, set()).add(cw)
            tier[cid] = max(tier.get(cid, 0.0), tw)
    n_cw = max(1, len({w for ws in works.values() for w in ws}))

    # approximate concept PYQ from PYQ MCQ stems (multi-word, ≥3-mention concepts only → precision)
    id2phrase = {c["id"]: c["name"].lower() for c in concepts
                 if len(c["name"].split()) >= 2 and c.get("mentions", 0) >= 3}
    mcqs = [json.loads(l) for l in open(WORK_DIR / "mcqs.jsonl", encoding="utf-8")]
    pyq = {r["idx"]: r for r in (json.loads(l) for l in open(WORK_DIR / "mcq_pyq.jsonl", encoding="utf-8"))}
    cpyq: dict[int, dict] = {}
    for i, m in enumerate(mcqs):
        p = pyq.get(i, {})
        if not p.get("is_pyq"):
            continue
        text = (m.get("stem", "") + " " + m.get("explanation", "")).lower()
        for cid, phrase in id2phrase.items():
            if phrase in text:
                d = cpyq.setdefault(cid, {"is_pyq": True, "s_pyq": 0.0, "years": set(), "trend": []})
                d["s_pyq"] = max(d["s_pyq"], p.get("s_pyq", 0.0))
                d["years"].update(p.get("pyq_years") or [])
                d["trend"].append(p.get("pyq_trend", "none"))

    p95_men = _pctl([c.get("mentions", 1) for c in concepts], 0.95)
    p95_cpyq = _pctl([d["s_pyq"] for d in cpyq.values()], 0.95)
    rows, hist = [], Counter()
    cent_map, pyq_map = {}, {}
    for c in concepts:
        cid = c["id"]
        d = cpyq.get(cid)
        s_cent = float(c.get("centrality", 0.0))
        s_corrob = math.log1p(len(works.get(cid, ()))) / math.log1p(n_cw)
        s_freq = min(1.0, math.log1p(c.get("mentions", 1)) / math.log1p(1 + p95_men))
        s_pyq = min(1.0, d["s_pyq"] / p95_cpyq) if d else 0.0
        dom_trend = Counter(d["trend"]).most_common(1)[0][0] if d and d["trend"] else "none"
        s_trend = _TREND.get(dom_trend, 0.5)
        signals = {"pyq": round(s_pyq, 4), "corrob": round(s_corrob, 4), "freq": round(s_freq, 4),
                   "emphasis": 0.0, "blueprint": 0.5, "centrality": round(s_cent, 4),
                   "tier": round(tier.get(cid, 0.5), 4), "trend": s_trend}
        raw = sum(w[k] * signals[k] for k in w)
        bucket = _bucket(min(1.0, raw), th)
        floor = None
        years = set(d["years"]) if d else set()
        recent = bool(d) and any(y and y >= CURRENT_YEAR - window for y in years)
        if len(years) >= 2 and recent:
            bucket, floor = fl["multi_year_pyq_forces"], "multi_year_pyq"
        elif d and d["is_pyq"] and bucket < fl["pyq_min_importance"]:
            bucket, floor = fl["pyq_min_importance"], "pyq_min"
        if dom_trend == "fading" and len(works.get(cid, ())) < 3 and bucket > fl["fading_max"]:
            bucket, floor = fl["fading_max"], "fading_cap"
        if not (d and d["is_pyq"]) and len(works.get(cid, ())) <= 1 and bucket > fl["uncorroborated_max"]:
            bucket, floor = fl["uncorroborated_max"], "uncorroborated_cap"
        cent_map[cid] = s_cent
        pyq_map[cid] = {"is_pyq": bool(d and d["is_pyq"]), "s_pyq": (d["s_pyq"] if d else 0.0),
                        "years": sorted(years), "trend": dom_trend}
        hist[bucket] += 1
        rows.append({"id": cid, "name": c["name"], "importance": bucket,
                     "importance_score": round(min(1.0, raw), 4), "signals": signals, "floor": floor})
    if CONCEPT_IMPORTANCE_OUT.exists():
        CONCEPT_IMPORTANCE_OUT.unlink()
    with open(CONCEPT_IMPORTANCE_OUT, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    stats = {"concepts_scored": len(rows), "histogram": {b: hist[b] for b in range(1, 6)},
             "concept_pyq_matched": len(cpyq), "top5_share_pct": round(100 * hist[5] / max(1, len(rows)), 1)}
    print("[C9-concepts] " + json.dumps(stats), flush=True)
    return stats, cent_map, pyq_map


def score_atoms(cfg: Config | None = None, cent_map: dict | None = None, pyq_map: dict | None = None):
    """Score atoms. Own corroboration/freq/tier/blueprint + centrality & PYQ INHERITED from the concepts
    the atom mentions (linked via graph_edges_raw rationale = the atom's statement)."""
    cfg = cfg or Config.for_exam("mrcp_part1")
    if cent_map is None:
        _, cent_map, pyq_map = score_concepts(cfg)
    imp = cfg.importance
    w, th, fl = imp["weights"], imp["thresholds"], imp["floors"]
    window = int(imp.get("recent_window_years", 10))
    cmap, al, sources = workgroups.canonical_map(), A.Aliases.from_profile(), _load_sources()
    concepts = [json.loads(l) for l in open(WORK_DIR / "concepts.jsonl", encoding="utf-8")]
    key2id = {c["canonical_key"]: c["id"] for c in concepts}

    # atom statement (first 240 chars = the stored rationale) -> concept ids it mentions
    stmt2cids: dict[str, set] = {}
    for l in open(WORK_DIR / "graph_edges_raw.jsonl", encoding="utf-8"):
        e = json.loads(l)
        s = stmt2cids.setdefault(e.get("rationale", ""), set())
        for side in ("s", "o"):
            cid = key2id.get(al.key(e[side]))
            if cid is not None:
                s.add(cid)

    atoms = [json.loads(l) for l in open(WORK_DIR / "atoms_merged.jsonl", encoding="utf-8")]
    n_works = max(1, len({cmap.get(a["source_id"], a["source_id"]) for a in atoms}))
    p95_freq = _pctl([a.get("member_count", 1) for a in atoms], 0.95)
    emph, mix = _load_emphasis(), imp.get("emphasis_mix", {"my_highlight": 0.6, "book_flag": 0.4})
    subj_w = {(s.get("name") or "").strip().lower(): float(s.get("weight", 0)) for s in cfg.subjects}
    max_sw = max([*subj_w.values(), 1.0])
    rows, hist = [], Counter()
    for a in atoms:
        cids = stmt2cids.get((a.get("statement", "") or "")[:240], ())
        dwc = a.get("distinct_work_count", 1) or 1
        pubs = a.get("distinct_publisher_count", 1) or 1
        s_corrob = min(1.0, (math.log1p(dwc) / math.log1p(n_works)) * (1 + 0.10 * (pubs - 1)))
        s_freq = min(1.0, math.log1p(a.get("member_count", 1)) / math.log1p(1 + p95_freq))
        s_cent = max((cent_map.get(c, 0.0) for c in cids), default=0.0)
        cp = [pyq_map[c] for c in cids if c in pyq_map and pyq_map[c]["is_pyq"]]
        a_ispyq = bool(cp)
        a_spyq = max((x["s_pyq"] for x in cp), default=0.0)
        a_years = sorted({y for x in cp for y in x["years"]})
        a_trend = Counter(x["trend"] for x in cp).most_common(1)[0][0] if cp else "none"
        src = sources.get(a["source_id"], {})
        s_blue = subj_w.get(src.get("subject", ""), 0.0) / max_sw if src.get("subject") in subj_w else 0.5
        signals = {"pyq": round(min(1.0, a_spyq), 4), "corrob": round(s_corrob, 4), "freq": round(s_freq, 4),
                   "emphasis": round(_emphasis(emph, mix, a["source_id"], a.get("page_ref")), 4),
                   "blueprint": round(s_blue, 4), "centrality": round(s_cent, 4),
                   "tier": round(float(src.get("tier_weight", 0.5) or 0.5), 4), "trend": _TREND.get(a_trend, 0.5)}
        raw = sum(w[k] * signals[k] for k in w)
        bucket = _bucket(min(1.0, raw), th)
        floor = None
        # atom floors (SCORING.md §floors): the multi-year-PYQ→5 floor is CONCEPT-ONLY, NOT atoms — an
        # atom that merely mentions several separately-PYQ'd concepts was not itself asked across years.
        # Atoms: PYQ enters only as an inherited SIGNAL; caps still apply.
        if a_trend == "fading" and dwc < 3 and bucket > fl["fading_max"]:
            bucket, floor = fl["fading_max"], "fading_cap"
        if not a_ispyq and dwc <= 1 and bucket > fl["uncorroborated_max"]:
            bucket, floor = fl["uncorroborated_max"], "uncorroborated_cap"
        hist[bucket] += 1
        rows.append({"stable_ref": a.get("statement", "")[:60], "importance": bucket,
                     "importance_score": round(min(1.0, raw), 4), "signals": signals, "floor": floor})
    if ATOM_IMPORTANCE_OUT.exists():
        ATOM_IMPORTANCE_OUT.unlink()
    with open(ATOM_IMPORTANCE_OUT, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    n = len(rows)
    linked = sum(1 for a in atoms if stmt2cids.get((a.get("statement", "") or "")[:240]))
    stats = {"atoms_scored": n, "atoms_concept_linked": linked, "histogram": {b: hist[b] for b in range(1, 6)},
             "histogram_pct": {b: round(100 * hist[b] / n, 1) for b in range(1, 6)}}
    print("[C9-atoms] " + json.dumps(stats), flush=True)
    return stats


if __name__ == "__main__":
    import sys
    cfg = Config.for_exam("mrcp_part1")
    mode = sys.argv[1] if len(sys.argv) > 1 else "all"
    if mode in ("all", "mcq"):
        score_mcqs(cfg)
    if mode in ("all", "concept", "atom"):
        _, cent, cpyq = score_concepts(cfg)
        if mode in ("all", "atom"):
            score_atoms(cfg, cent, cpyq)
