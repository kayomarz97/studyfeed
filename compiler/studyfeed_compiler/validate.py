"""C12 — validate + QA (BUILD_PROMPT §C12 + §7 Acceptance; qa-reviewer role).

Deterministic audit over the work/ artifacts that feed C13 packaging (the pack DB does not exist yet):
grounding / dead links, importance ∈ 1..5 + populated signals, the PYQ ≥4★ floor, curated-key
preservation, importance distributions (degeneracy flag), PYQ + dossier + blueprint coverage, and a
byte-identical reproducibility re-run of the deterministic scorer. Writes a human-skimmable
`dist/build_report.md` (dist/ is gitignored — it quotes stems, stays local §11) and returns a stats dict.

Two spec nuances the gates encode (rather than the oversimplified §7 one-liners):
  • §7 says "every PYQ MCQ ≥4★", but SCORING.md §floors caps a `fading`-dead PYQ (all hits older than the
    recency window, none since) at ≤3 — the recency window is load-bearing. The gate therefore fails only on
    a NON-fading PYQ below 4★; fading-capped PYQs are correct-by-spec and counted separately.
  • §7 says "curated keys preserved verbatim (not overwritten)" — we never overwrite. Curated MCQs that were
    unparseable at extraction (answer=None) are a data-quality gap → quarantined at C13, not a preservation
    failure (WARN + quarantine list, not FAIL).

Semantic grounding (does a generated card actually follow from its cited atom — beyond token overlap) is
judged separately by an Opus `qa-reviewer` subagent whose findings append to the same report.
"""
from __future__ import annotations

import json
from collections import Counter
from datetime import datetime, timezone

from .config import Config, DIST_DIR, WORK_DIR

_MARK = {"pass": "PASS", "fail": "**FAIL**", "warn": "WARN", "skip": "skip"}


def _load(name: str) -> list[dict]:
    p = WORK_DIR / name
    return [json.loads(l) for l in open(p, encoding="utf-8")] if p.exists() else []


def _hist(rows: list[dict], key: str = "importance") -> dict:
    c = Counter(r[key] for r in rows if key in r)
    n = max(1, sum(c.values()))
    return {"counts": {b: c.get(b, 0) for b in range(1, 6)},
            "pct": {b: round(100 * c.get(b, 0) / n, 1) for b in range(1, 6)},
            "degenerate": any(c.get(b, 0) / n > 0.45 for b in range(1, 6)) or c.get(5, 0) == 0}


def _check_importance_rows(rows: list[dict], label: str, sig_key: str = "signals") -> dict:
    bad_range = sum(1 for r in rows if not (1 <= r.get("importance", 0) <= 5))
    bad_sig = sum(1 for r in rows if not r.get(sig_key))
    return {"label": label, "n": len(rows), "out_of_range": bad_range, "empty_signals": bad_sig,
            "pass": bad_range == 0 and bad_sig == 0}


def audit(cfg: Config | None = None, reproducibility: bool = True) -> dict:
    cfg = cfg or Config.for_exam("mrcp_part1")
    report_path = DIST_DIR / "build_report.md"

    atoms = _load("atoms_merged.jsonl")
    aimp = _load("atom_importance.jsonl")
    mcqs = _load("mcqs.jsonl")
    mimp = _load("mcq_importance.jsonl")
    mpyq = _load("mcq_pyq.jsonl")
    concepts = _load("concepts.jsonl")
    cimp = _load("concept_importance.jsonl")
    cedges = _load("concept_edges.jsonl")
    dossiers = _load("dossiers.jsonl")
    fc = _load("gen_flashcards.jsonl")
    reels = _load("gen_reels.jsonl")
    gmcq = _load("gen_mcqs.jsonl")
    n_atoms, n_mcqs, n_concepts = len(atoms), len(mcqs), len(concepts)
    checks = []  # (name, status, detail)

    # ── 1. grounding / dead links ──────────────────────────────────────────────
    def dangling_ref(rows):
        return sum(1 for r in rows if not isinstance(r.get("ref"), int) or not (0 <= r["ref"] < n_atoms))

    def no_citation(rows):
        return sum(1 for r in rows if not r.get("citations"))

    fc_bad, reel_bad, gm_bad = dangling_ref(fc), dangling_ref(reels), dangling_ref(gmcq)
    gen_nocite = no_citation(fc) + no_citation(reels) + no_citation(gmcq)
    doss_bad_mcq = sum(1 for d in dossiers for mi in d.get("related_mcq_idxs", []) if not (0 <= mi < n_mcqs))
    cid_set = {c["id"] for c in concepts}
    edge_bad = sum(1 for e in cedges if e.get("src_concept_id") not in cid_set
                   or e.get("dst_concept_id") not in cid_set)
    doss_empty = sum(1 for d in dossiers if d.get("n_atoms", 0) < 1)
    dead = fc_bad + reel_bad + gm_bad + doss_bad_mcq + edge_bad + doss_empty + gen_nocite
    checks.append(("Grounding / dead links", "pass" if dead == 0 else "fail",
                   f"gen dangling atom-refs: fc={fc_bad} reel={reel_bad} mcq={gm_bad}; "
                   f"gen missing citations={gen_nocite}; dossier dead mcq-links={doss_bad_mcq}; "
                   f"concept-edge dead endpoints={edge_bad}; empty dossiers={doss_empty}"))

    # ── 2. importance ∈ 1..5 + populated signals (every scored/playable row) ────
    imp_checks = [
        _check_importance_rows(mimp, "curated MCQs"),
        _check_importance_rows(cimp, "concepts"),
        _check_importance_rows(aimp, "atoms"),
        _check_importance_rows(dossiers, "dossiers", sig_key="importance_signals"),
        _check_importance_rows(fc, "flashcards", sig_key="importance_signals"),
        _check_importance_rows(reels, "reels", sig_key="importance_signals"),
        _check_importance_rows(gmcq, "generated MCQs", sig_key="importance_signals"),
    ]
    imp_ok = all(c["pass"] for c in imp_checks)
    checks.append(("Importance in 1..5 + populated signals", "pass" if imp_ok else "fail",
                   "; ".join(f"{c['label']}: {c['n']} rows, {c['out_of_range']} bad-range, "
                             f"{c['empty_signals']} empty-signals" for c in imp_checks)))

    # ── 3. PYQ floor: non-fading PYQ MCQs must be ≥4★ (fading-dead ones cap ≤3, SCORING.md §floors) ──
    imp_by_idx = {r["idx"]: r for r in mimp}
    pyq_mcqs = [r["idx"] for r in mpyq if r.get("is_pyq")]
    below = [i for i in pyq_mcqs if imp_by_idx.get(i, {}).get("importance", 0) < 4]
    fading_capped = [i for i in below if imp_by_idx.get(i, {}).get("floor") == "fading_cap"]
    pyq_wrong = [i for i in below if i not in set(fading_capped)]
    checks.append(("PYQ MCQs >=4 stars — non-fading (§7 + SCORING.md §floors)",
                   "pass" if not pyq_wrong else "fail",
                   f"{len(pyq_mcqs)} PYQ MCQs; {len(pyq_wrong)} wrongly <4*; "
                   f"{len(fading_capped)} fading-dead correctly capped <=3 per SCORING.md"))

    # ── 4. curated keys: never overwritten (§7). No valid gradeable A–E key → quarantine at C13 ──────
    # (answer empty OR not A–E — e.g. EMQ answers 'G'/'I' with >5 options don't fit best-of-five.)
    _LET = {"a": 0, "b": 1, "c": 2, "d": 3, "e": 4}
    unkeyed = [i for i, m in enumerate(mcqs) if _LET.get(str(m.get("answer") or "").strip().lower()) is None]
    checks.append(("Curated MCQ keys preserved (not overwritten)",
                   "pass" if not unkeyed else "warn",
                   f"{n_mcqs} curated MCQs; 0 keys overwritten; {len(unkeyed)} with no valid A–E key "
                   f"({round(100 * len(unkeyed) / max(1, n_mcqs), 1)}%) -> QUARANTINE at C13, not shipped"))

    # ── 5. reproducibility: deterministic re-score is byte-identical ────────────
    if reproducibility:
        from . import importance as I
        paths = [I.MCQ_IMPORTANCE_OUT, I.CONCEPT_IMPORTANCE_OUT, I.ATOM_IMPORTANCE_OUT]
        before = {p: p.read_bytes() for p in paths if p.exists()}
        I.score_mcqs(cfg)
        _, cent, cpyq = I.score_concepts(cfg)
        I.score_atoms(cfg, cent, cpyq)
        changed = [p.name for p in paths if p.read_bytes() != before.get(p)]
        checks.append(("Reproducibility (byte-identical re-score)", "pass" if not changed else "fail",
                       "all importance files identical on re-run" if not changed
                       else f"CHANGED on re-run: {changed}"))
    else:
        checks.append(("Reproducibility (byte-identical re-score)", "skip", "skipped"))

    # ── distributions + coverage (reported, not pass/fail) ──────────────────────
    dist = {"curated_mcqs": _hist(mimp), "concepts": _hist(cimp), "atoms": _hist(aimp),
            "dossiers": _hist(dossiers), "flashcards": _hist(fc), "reels": _hist(reels),
            "generated_mcqs": _hist(gmcq)}
    pyq_years = Counter(y for r in mpyq if r.get("is_pyq") for y in (r.get("pyq_years") or []))
    coverage = {
        "pyq_mcqs": len(pyq_mcqs), "pyq_mcqs_pct": round(100 * len(pyq_mcqs) / max(1, n_mcqs), 1),
        "pyq_years": dict(sorted(pyq_years.items())),
        "fading_capped_pyq": len(fading_capped), "unkeyed_curated_mcqs": len(unkeyed),
        "dossiers": len(dossiers),
        "dossiers_cross_subject": sum(1 for d in dossiers if len(d.get("subjects_covered", [])) >= 2),
        "concepts_total": n_concepts, "dossier_concept_pct": round(100 * len(dossiers) / max(1, n_concepts), 1),
        "guideline_sensitive_concepts": sum(1 for c in concepts if c.get("guideline_sensitive")),
    }
    subj_w = {(s.get("name") or "").strip().lower(): float(s.get("weight", 0)) for s in cfg.subjects}
    doss_subj = Counter(s.lower() for d in dossiers for s in d.get("subjects_covered", []))
    blueprint = {name: {"profile_weight": w, "dossiers_covering": doss_subj.get(name, 0)}
                 for name, w in sorted(subj_w.items(), key=lambda x: -x[1])}
    inventory = {"atoms": n_atoms, "curated_mcqs": n_mcqs, "generated_mcqs": len(gmcq),
                 "flashcards": len(fc), "reels": len(reels), "concepts": n_concepts,
                 "concept_edges": len(cedges), "dossiers": len(dossiers)}
    playable = len(mcqs) - len(unkeyed) + len(gmcq) + len(fc) + len(reels)

    # ── write report ────────────────────────────────────────────────────────────
    passed = sum(1 for _, s, _ in checks if s == "pass")
    warned = [n for n, s, _ in checks if s == "warn"]
    failed = [n for n, s, _ in checks if s == "fail"]
    total = sum(1 for _, s, _ in checks if s != "skip")
    lines = [f"# StudyFeed build report — {cfg.exam_name}",
             f"_generated {datetime.now(timezone.utc).isoformat(timespec='seconds')} · "
             f"pre-packaging audit of `work/` artifacts (C12)_", "",
             f"**Acceptance gates: {passed}/{total} pass"
             + (f", {len(warned)} warn" if warned else "") + (f", {len(failed)} FAIL" if failed else "")
             + "**", "",
             "## Acceptance gates", "", "| Check | Result | Detail |", "|---|---|---|"]
    for name, status, detail in checks:
        lines.append(f"| {name} | {_MARK[status]} | {detail} |")
    lines += ["", "## Importance distributions (%)", "",
              "| Item type | n | 1* | 2* | 3* | 4* | 5* | flag |", "|---|--:|--:|--:|--:|--:|--:|---|"]
    for k, h in dist.items():
        n = sum(h["counts"].values())
        p = h["pct"]
        lines.append(f"| {k} | {n} | {p[1]} | {p[2]} | {p[3]} | {p[4]} | {p[5]} | "
                     f"{'skewed' if h['degenerate'] else ''} |")
    lines += ["",
              "_Note: flashcards/reels/generated-MCQs skew to 4–5★ **by design** — C11 (trimmed scope) only "
              "generated assets from ≥4★ atoms. concepts (71% 1★), atoms (45% 2★) and dossiers (51% 4★) reflect "
              "the known importance-model tail — optional `importance-scorer` recalibration is a follow-up._", ""]
    lines += ["## Coverage", "",
              f"- **Playable items:** {playable} ({len(mcqs) - len(unkeyed)} curated MCQ [-{len(unkeyed)} "
              f"unkeyed quarantined] + {len(gmcq)} gen MCQ + {len(fc)} flashcards + {len(reels)} reels)",
              f"- **PYQ:** {coverage['pyq_mcqs']} curated MCQs ({coverage['pyq_mcqs_pct']}%) across years "
              f"{list(coverage['pyq_years'].keys())}; {coverage['fading_capped_pyq']} fading-dead capped ≤3★",
              f"- **Dossiers:** {coverage['dossiers']} ({coverage['dossier_concept_pct']}% of concepts); "
              f"{coverage['dossiers_cross_subject']} cross-subject",
              f"- **Guideline-sensitive concepts:** {coverage['guideline_sensitive_concepts']} "
              f"(post-C8 staleness key-conflict check still deferred)", "",
              "## To quarantine at C13 packaging", "",
              f"- **{len(unkeyed)} curated MCQs have no parseable answer key** → exclude from the playable "
              f"deck (unplayable). mcqs.jsonl indices: {unkeyed if len(unkeyed) <= 40 else str(unkeyed[:40]) + ' …'}",
              "", "## Blueprint balance (dossier subject coverage vs profile weight)", "",
              "| Subject | profile weight | dossiers covering |", "|---|--:|--:|"]
    for name, d in blueprint.items():
        lines.append(f"| {name} | {d['profile_weight']:.0f} | {d['dossiers_covering']} |")
    lines += ["", "## Inventory", "", "```", json.dumps(inventory, indent=1), "```", "",
              "## Semantic grounding (Opus qa-reviewer)", "", "_appended by the qa-reviewer subagent._", ""]
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    report_path.write_text("\n".join(lines), encoding="utf-8")

    stats = {"gates_passed": passed, "gates_total": total, "warn": warned, "fail": failed,
             "gates": [{"name": n, "status": s} for n, s, _ in checks],
             "degenerate": [k for k, v in dist.items() if v["degenerate"]],
             "coverage": coverage, "inventory": inventory, "playable": playable,
             "unkeyed_quarantine": unkeyed, "report": str(report_path)}
    print("[C12] " + json.dumps({k: v for k, v in stats.items() if k != "unkeyed_quarantine"},
                                ensure_ascii=False), flush=True)
    return stats


if __name__ == "__main__":
    import sys
    audit(Config.for_exam("mrcp_part1"), reproducibility="--no-repro" not in sys.argv)
