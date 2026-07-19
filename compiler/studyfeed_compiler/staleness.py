"""Post-C8 staleness check (BUILD_PROMPT §C6/§staleness; staleness-checker role).

An OLD past-paper key can contradict CURRENT guidance on a management/target/threshold topic. This flags
those for review — it NEVER overwrites a key (the app shows "key disputed"). We narrow to the genuine risk
set (a PYQ MCQ whose concept is guideline_sensitive AND whose answer is management/target/threshold-shaped),
chunk it for staleness-checker subagents (medical judgment: does the key still match current UK guidance?),
and collect verdicts → work/mcq_key_conflicts.jsonl {idx, key_conflict, note} → pack.py sets mcqs.key_conflict.
"""
from __future__ import annotations

import glob
import json
import os
import re

from . import alias as A
from .config import Config, WORK_DIR

IN_DIR = WORK_DIR / "staleness_input"
OUT_DIR = WORK_DIR / "staleness_output"
CONFLICTS = WORK_DIR / "mcq_key_conflicts.jsonl"


def build_candidates(cfg: Config | None = None, chunk_size: int = 60) -> dict:
    cfg = cfg or Config.for_exam("mrcp_part1")
    cues = [c.lower() for c in cfg.raw.get("staleness", {}).get("guideline_sensitive_cues", [])] + \
           ["first line", "initiate", "aim for", "titrate", "dose", "mg", "target of", "threshold",
            "screen", "monitor", "add ", "switch to", "step "]
    gs_names = [c["name"].lower() for c in (json.loads(l) for l in open(WORK_DIR / "concepts.jsonl",
                encoding="utf-8")) if c.get("guideline_sensitive") and len(c["name"].split()) >= 2]
    mcqs = [json.loads(l) for l in open(WORK_DIR / "mcqs.jsonl", encoding="utf-8")]
    pyq = {r["idx"]: r for r in (json.loads(l) for l in open(WORK_DIR / "mcq_pyq.jsonl", encoding="utf-8"))}
    cand = []
    for i, m in enumerate(mcqs):
        p = pyq.get(i, {})
        if not p.get("is_pyq"):
            continue
        text = (m.get("stem", "") + " " + m.get("explanation", "")).lower()
        if not any(g in text for g in gs_names):          # touches a guideline-sensitive concept
            continue
        if not any(c in text for c in cues):              # management/target/threshold-shaped answer
            continue
        yrs = p.get("pyq_years") or []
        ans = str(m.get("answer", "")).strip().lower()
        opts = {"a": m.get("opt_a"), "b": m.get("opt_b"), "c": m.get("opt_c"), "d": m.get("opt_d"),
                "e": m.get("opt_e")}
        cand.append({"idx": i, "stem": m.get("stem", ""), "answer": opts.get(ans, ""),
                     "explanation": (m.get("explanation", "") or "")[:600], "pyq_years": yrs,
                     "oldest_year": min(yrs) if yrs else None})
    # oldest papers first (most likely stale)
    cand.sort(key=lambda x: (x["oldest_year"] or 9999, x["idx"]))
    IN_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    ids = []
    for k in range(0, len(cand), chunk_size):
        cid = f"{k // chunk_size:04d}"
        with open(IN_DIR / f"{cid}.jsonl", "w", encoding="utf-8") as f:
            for r in cand[k:k + chunk_size]:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        ids.append(cid)
    print(f"[staleness build] {len(cand)} candidate PYQ MCQs -> {len(ids)} chunks (size {chunk_size})", flush=True)
    return {"candidates": len(cand), "chunks": ids}


def pending() -> list[str]:
    return [os.path.basename(p)[:-6] for p in sorted(glob.glob(str(IN_DIR / "*.jsonl")))
            if not (OUT_DIR / os.path.basename(p)).exists()]


def collect() -> dict:
    n_mcqs = sum(1 for _ in open(WORK_DIR / "mcqs.jsonl", encoding="utf-8"))
    rows, seen, stats = [], set(), {"seen": 0, "conflicts": 0, "clean": 0, "bad": 0}
    for p in sorted(glob.glob(str(OUT_DIR / "*.jsonl"))):
        for l in open(p, encoding="utf-8"):
            l = l.strip()
            if not l:
                continue
            try:
                r = json.loads(l)
            except json.JSONDecodeError:
                stats["bad"] += 1
                continue
            idx = r.get("idx")
            if not isinstance(idx, int) or not (0 <= idx < n_mcqs) or idx in seen:
                continue
            seen.add(idx)
            stats["seen"] += 1
            kc = 1 if r.get("key_conflict") else 0
            if kc:
                stats["conflicts"] += 1
                rows.append({"idx": idx, "key_conflict": 1, "note": (r.get("note") or "").strip()[:400]})
            else:
                stats["clean"] += 1
    rows.sort(key=lambda x: x["idx"])
    if CONFLICTS.exists():
        CONFLICTS.unlink()
    with open(CONFLICTS, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"[staleness collect] {json.dumps(stats)} -> {CONFLICTS}", flush=True)
    return stats


if __name__ == "__main__":
    import sys
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "build":
        build_candidates()
    elif cmd == "collect":
        collect()
    elif cmd == "pending":
        print(json.dumps(pending()))
    else:
        print("usage: staleness.py [build|collect|pending]")
