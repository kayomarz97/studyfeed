"""C3 — emphasis harvest (BUILD_PROMPT §C3/§5h; highlight-harvester + book-usage-analyst).

Digital annotations = NONE: all 107 source PDFs were probed, 0 highlight annots — these are distributed
study materials, not annotated copies. So `is_highlighted` (the USER's own highlights) is 0 everywhere,
and colour-mask on the Pastest/PassMedicine screenshots would only pick up their UI (buttons, peer-
response bars), not real highlights. The real, harvestable emphasis is the BOOK's own high-yield markers
(`book_flagged`, §5h), from two sources:

  (a) WHOLE-SOURCE salience — pearls (PassMedicine High-Yield, Essential Revision, …) are high-yield by
      construction (profile source_tiers.pearls.source_salience=0.75, exempt from the noise ceiling §5i).
  (b) PER-TOPIC markers in the already-extracted text — SsAcademy SKELETON's 🎤/▶️ high-yield emoji,
      plus 'key point'/'pearl' callouts. Subject to profile.zoning.book_flag_noise_ceiling (a book
      flagging >60% of itself is noise, §5i) — except exempt sources.

Output: work/emphasis.jsonl {source_id, page, book_flagged, book_salience} → feeds S_emphasis in C9
(mixed per profile.importance.emphasis_mix; my_highlight component is 0 → emphasis maxes at book_flag).
"""
from __future__ import annotations

import glob
import json
import re
from collections import Counter

from .config import Config, WORK_DIR

EXTRACT_DIR = WORK_DIR / "extracted"
OUT = WORK_DIR / "emphasis.jsonl"

# per-topic emphasis markers in the extracted text → (regex, salience). 'high-yield'/'important' are
# EXCLUDED: the former is a PassMedicine book-title header (handled by whole-source salience), the
# latter is a common word — both are noise, not per-topic author flags.
MARKERS = [(re.compile(r"🎤|▶"), 0.85),                 # SKELETON per-topic high-yield / video markers
           (re.compile(r"(?i)key point|key fact"), 0.70),
           (re.compile(r"(?i)\bpearl(s)?\b"), 0.60)]
_KIND2TIERKEY = {"past_paper": "past_papers", "qbank": "qbanks", "pearls": "pearls",
                 "textbook": "textbooks", "reference": "references"}


def _marker_salience(text: str) -> float:
    best = 0.0
    for pat, sal in MARKERS:
        if sal > best and pat.search(text):
            best = sal
    return best


def harvest(cfg: Config | None = None) -> dict:
    cfg = cfg or Config.for_exam("mrcp_part1")
    tiers = cfg.source_tiers
    ceiling = float(cfg.zoning.get("book_flag_noise_ceiling", 0.60))
    kinds = {json.loads(l)["work_id"]: json.loads(l)["kind"]
             for l in open(WORK_DIR / "classification.jsonl", encoding="utf-8")}

    rows, per_source = [], []
    for fn in sorted(glob.glob(str(EXTRACT_DIR / "*.jsonl"))):
        wid = int(fn.split("/")[-1][:3])
        kind = kinds.get(wid)
        tcfg = tiers.get(_KIND2TIERKEY.get(kind, ""), {})
        src_sal = float(tcfg.get("source_salience", 0) or 0)
        exempt = bool(tcfg.get("exempt_noise_ceiling", False))
        pages = [(str(r.get("page")), r.get("text") or "")
                 for r in (json.loads(l) for l in open(fn, encoding="utf-8"))]
        marker_pg = {pg: s for pg, t in pages if (s := _marker_salience(t)) > 0}
        # §5i noise ceiling: a non-exempt book flagging >ceiling of its pages is noise → drop markers
        noisy = (not exempt) and pages and (len(marker_pg) / len(pages) > ceiling)
        if noisy:
            marker_pg = {}
        flagged = 0
        for pg, _t in pages:
            bs = max(src_sal, marker_pg.get(pg, 0.0))
            if bs > 0:
                rows.append({"source_id": wid, "page": pg, "book_flagged": 1, "book_salience": round(bs, 3)})
                flagged += 1
        if flagged or src_sal or marker_pg:
            per_source.append({"wid": wid, "kind": kind, "pages": len(pages), "flagged": flagged,
                               "whole_source": bool(src_sal), "markers": len(marker_pg), "noisy": noisy})

    if OUT.exists():
        OUT.unlink()
    with open(OUT, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    by_kind = Counter()
    for s in per_source:
        by_kind[s["kind"]] += s["flagged"]
    marker_sources = [s for s in per_source if s["markers"]]
    stats = {"flagged_pages": len(rows), "flagged_by_kind": dict(by_kind),
             "whole_source_books": sum(1 for s in per_source if s["whole_source"]),
             "marker_books": len(marker_sources), "noisy_dropped": sum(1 for s in per_source if s["noisy"]),
             "top_marker_books": sorted(((s["wid"], s["markers"]) for s in marker_sources),
                                        key=lambda x: -x[1])[:6]}
    print("[C3] " + json.dumps(stats), flush=True)
    return stats


if __name__ == "__main__":
    harvest()
