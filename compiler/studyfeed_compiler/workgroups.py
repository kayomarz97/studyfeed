"""Work-identity grouping (BUILD_PROMPT §5b). User-approved 2026-07-16.

Several source files are per-subject slices (or a duplicate copy) of ONE book. Counting each file as a
separate "work" fakes cross-corroboration — the exact trap §5b warns "breaks the whole model" (one book
counted many times floods the top-importance band). So slices of one book share a canonical work_id, and
the redundant duplicate copy is dropped. The corroboration/dedup stages (C5 cluster, C7 atom-merge) MUST
map source_id -> canonical work_id via `canonical_map()` before counting distinct works.

Concrete groups for the MRCP Part-1 source set (wids from C1 classification):
  - PassMedicine High-Yield: 16 per-subject files (63-78) = 1 work; the combined Part1+2 copy (79) dropped.
  - SKELETON "(C)"/"(Online)" series: 14 per-subject slices (80,81,83-92,95,96) = 1 work.
  - 82/93/94/97/98 and all past_paper/qbank stay distinct.
The textbooks themselves are prose high-yield notes (atomized, not MCQ banks); their only MCQs are
front-matter demo/sample questions, excluded as §5i boilerplate.
"""
from __future__ import annotations

import json

from .config import WORK_DIR

PASSMEDICINE_HIGHYIELD = list(range(63, 79))                                    # 16 files -> 1 work
SKELETON_C_SERIES = [80, 81, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 95, 96]   # 14 files -> 1 work
DROPPED = {79}                                                                  # combined PM Part1+2 copy

GROUPS = {
    "passmedicine_highyield": {"canonical": 63, "members": PASSMEDICINE_HIGHYIELD,
                               "note": "16 per-subject files = 1 work"},
    "skeleton_c_series": {"canonical": 80, "members": SKELETON_C_SERIES,
                          "note": "'(C)'+'(Online)' subject slices = 1 work"},
}
WORK_GROUPS_JSON = WORK_DIR / "work_groups.json"


def build_canonical(all_work_ids) -> dict[int, int]:
    """file work_id -> canonical work_id (identity for ungrouped, undefined for dropped)."""
    canon: dict[int, int] = {}
    for grp in GROUPS.values():
        for w in grp["members"]:
            canon[w] = grp["canonical"]
    for w in all_work_ids:
        if w not in DROPPED:
            canon.setdefault(w, w)
    return canon


def write(all_work_ids) -> dict:
    canon = build_canonical(all_work_ids)
    out = {"canonical": {str(k): v for k, v in sorted(canon.items())},
           "dropped": sorted(DROPPED), "groups": GROUPS}
    WORK_GROUPS_JSON.write_text(json.dumps(out, indent=1), encoding="utf-8")
    return out


def canonical_map() -> dict[int, int]:
    """Load the file->canonical map (empty if not yet written)."""
    if not WORK_GROUPS_JSON.exists():
        return {}
    return {int(k): v for k, v in
            json.loads(WORK_GROUPS_JSON.read_text(encoding="utf-8"))["canonical"].items()}
