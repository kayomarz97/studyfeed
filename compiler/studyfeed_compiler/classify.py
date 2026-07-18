"""Classify canonical works: kind + tier + subject + paper YEAR (BUILD_PROMPT.md §7 C1c).

Provenance (which drop) is the strong prior; per-file content refines it. NEVER guess a paper year
(unparseable -> null + flag). Textbook drop is tier 3/4 MIXED -> classify per file (textbook / pearls
/ reference), flagged for human confirmation. Emits a classification table for the C1 ⛔ gate; the
human corrects it before anything is atomized. Zero tokens (filename + drop only).
"""

from __future__ import annotations

import json
import re
from typing import Any

from .config import Config, WORK_DIR, DIST_DIR

# kind -> (tier, tier_weight, default_reliability) mirroring profile.source_tiers
_KIND_TIER = {
    "past_paper": (1, 1.00, "recall"),
    "qbank": (2, 0.75, "unknown"),
    "pearls": (3, 0.65, "unknown"),
    "textbook": (3, 0.60, "unknown"),
    "reference": (4, 0.45, "unknown"),
}

# subject keyword -> canonical profile subject (+ provisional folds flagged separately)
_SUBJECT_MAP = [
    ("cardio", "Cardiology"), ("neuro", "Neurology"),
    ("ophthal", "Ophthalmology"),  # kept separate (confirmed at C1)
    ("respir", "Respiratory medicine"), ("respi", "Respiratory medicine"), ("rheum", "Rheumatology"),
    ("nephro", "Nephrology"), ("renal", "Nephrology"),
    ("haemat", "Haematology"), ("hemat", "Haematology"), ("oncol", "Haematology"),  # onc fold
    ("git", "Gastroenterology & hepatology"), ("gastro", "Gastroenterology & hepatology"),
    ("hepat", "Gastroenterology & hepatology"),
    ("endocrin", "Endocrinology"), ("diabet", "Endocrinology"),
    ("infect", "Infectious diseases (incl. GUM/HIV)"), ("gum", "Infectious diseases (incl. GUM/HIV)"),
    ("psychiat", "Psychiatry"), ("geriatr", "Geriatric medicine"),
    ("palliat", "Palliative medicine"),  # kept separate (confirmed at C1)
    ("derma", "Dermatology"), ("pharma", "Clinical pharmacology, therapeutics & toxicology"),
    ("toxic", "Clinical pharmacology, therapeutics & toxicology"),
    ("clinical sci", "Clinical sciences"), ("cli sci", "Clinical sciences"),
    ("clin sci", "Clinical sciences"), ("basic sci", "Clinical sciences"), ("statistic", "Clinical sciences"),
]
_FOLD_KEYWORDS = ("oncol",)  # only Oncology stays folded (into Haematology; no distinct onc file)

_MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
_PEARLS_CUES = ("high yield", "high-yield", "highyield", "pearl", "commonly tested", "essential",
                "rapid", "crash", "in a page", "one line", "quick", "mnemonic", "last minute", "revision notes")
_REF_CUES = ("harrison", "kumar", "davidson", "oxford handbook", "cecil", "robbins", "clark", "handbook of")


def _extract_year(name: str) -> tuple[int | None, str]:
    m = re.search(r"(?:19|20)\d{2}", name)
    if m:
        y = int(m.group())
        if 1990 <= y <= 2026:
            return y, "filename-4digit"
    m = re.search(rf"({_MONTHS})[a-z]*[\s\-_']*(\d{{2}})\b", name.lower())
    if m:
        y = 2000 + int(m.group(2))
        if 2000 <= y <= 2026:
            return y, "filename-monthYY"
    return None, "unparsed"


def _extract_subject_str(name: str) -> str | None:
    m = re.match(r"^\s*\d+\s*[.\-]\s*(.+?)(?:\s*\([A-Za-z]\))?\.pdf$", name, re.I)
    if m:
        return m.group(1).strip()
    return None


def _map_subject(text: str | None) -> tuple[str | None, bool]:
    """Return (canonical_subject_or_None, is_provisional_fold)."""
    if not text:
        return None, False
    low = text.lower()
    for kw, subj in _SUBJECT_MAP:
        if kw in low:
            return subj, any(f in low for f in _FOLD_KEYWORDS)
    return None, False


def _textbook_kind(name: str) -> tuple[str, str]:
    n = name.lower()
    if any(k in n for k in _PEARLS_CUES):
        return "pearls", "high"
    if any(k in n for k in _REF_CUES):
        return "reference", "high"
    if _extract_subject_str(name):
        return "textbook", "medium"
    return "textbook", "low"


def _is_nonlatin_name(name: str) -> bool:
    """True if the filename is dominated by Arabic/Bengali script (excluded per §5g, confirmed at C1)."""
    stem = name.rsplit(".", 1)[0]
    latin = sum(1 for c in stem if c.isascii() and c.isalpha())
    nonlatin = sum(1 for c in stem if "؀" <= c <= "ۿ" or "ঀ" <= c <= "৿" or "ݐ" <= c <= "ݿ")
    return nonlatin > 0 and latin < 3


def run(cfg: Config) -> dict[str, Any]:
    canon = [json.loads(l) for l in open(WORK_DIR / "dedupe_canonicals.jsonl", encoding="utf-8")]
    rows: list[dict] = []
    for c in canon:
        drop, name = c["drop"], c["name"]
        flags: list[str] = []
        if _is_nonlatin_name(name):  # excluded per §5g (confirmed at C1) — not atomized
            rows.append({"work_id": c["work_id"], "drop": drop, "name": name, "pages": c["pages"],
                         "scanned": c.get("scanned", False), "kind": "excluded", "tier": None,
                         "tier_weight": None, "reliability": None, "subject": None, "year": None,
                         "year_source": None, "confidence": "n/a", "flags": ["non-latin-excluded"]})
            continue
        year, year_src = None, None
        subject, fold = None, False

        if drop == "Papers":
            kind = "past_paper"
            year, year_src = _extract_year(name)
            if year is None:
                flags.append("year-unparsed")
            conf = "high"
        elif drop == "Qbank":
            kind = "qbank"
            subject, fold = _map_subject(_extract_subject_str(name) or name)
            if subject is None:
                flags.append("subject-unmapped")
            if fold:
                flags.append("subject-fold-confirm")
            conf = "high"
        else:  # Textbook — mixed tier 3/4, classify per file
            if "high yield" in (c.get("path", "") or "").lower():
                kind, conf = "pearls", "high"   # Passmedicine High-Yield Textbook = high-yield PROSE (§7 C1c)
            else:
                kind, conf = _textbook_kind(name)
            subject, fold = _map_subject(_extract_subject_str(name) or name)
            if fold:
                flags.append("subject-fold-confirm")
            flags.append("kind-confirm" if conf != "high" else "kind-review")

        tier, tw, reliability = _KIND_TIER[kind]
        rows.append({
            "work_id": c["work_id"], "drop": drop, "name": name, "pages": c["pages"],
            "scanned": c.get("scanned", False), "kind": kind, "tier": tier, "tier_weight": tw,
            "reliability": reliability, "subject": subject, "year": year, "year_source": year_src,
            "confidence": conf, "flags": flags,
        })

    with open(WORK_DIR / "classification.jsonl", "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    _write_report(rows)
    return _summary(rows)


def _summary(rows: list[dict]) -> dict[str, Any]:
    import collections
    return {
        "total": len(rows),
        "by_kind": dict(collections.Counter(r["kind"] for r in rows)),
        "papers_year_unparsed": [r["name"] for r in rows if "year-unparsed" in r["flags"]],
        "papers_years": sorted(r["year"] for r in rows if r["kind"] == "past_paper" and r["year"]),
        "textbook_kind_confirm": [(r["name"], r["kind"]) for r in rows if "kind-confirm" in r["flags"]],
        "subject_folds": [r["name"] for r in rows if "subject-fold-confirm" in r["flags"]],
        "subject_unmapped": [r["name"] for r in rows if "subject-unmapped" in r["flags"]],
    }


def _write_report(rows: list[dict]) -> None:
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    L = ["# Classification table (§7 C1c) — CONFIRM at the C1 gate before atomizing\n"]
    for drop in ("Papers", "Qbank", "Textbook"):
        drop_rows = [r for r in rows if r["drop"] == drop]
        L.append(f"\n## {drop} — {len(drop_rows)} works\n")
        L.append("| name | kind | tier | subject | year | flags |")
        L.append("|---|---|---|---|---|---|")
        for r in sorted(drop_rows, key=lambda x: x["name"]):
            L.append(f"| {r['name'][:48]} | {r['kind']} | {r['tier']} | {r['subject'] or '—'} | "
                     f"{r['year'] or '—'} | {', '.join(r['flags']) or '—'} |")
    (DIST_DIR / "classification.md").write_text("\n".join(L), encoding="utf-8")


if __name__ == "__main__":
    s = run(Config.for_exam("mrcp_part1"))
    print("=== CLASSIFICATION SUMMARY ===")
    print(f"  total works:        {s['total']}")
    print(f"  by kind:            {s['by_kind']}")
    print(f"  paper years:        {s['papers_years']}")
    print(f"  YEAR UNPARSED ({len(s['papers_year_unparsed'])}): {s['papers_year_unparsed']}")
    print(f"  subject folds to confirm ({len(s['subject_folds'])}): {s['subject_folds']}")
    print(f"  subject unmapped ({len(s['subject_unmapped'])}): {s['subject_unmapped']}")
    print(f"  textbook kind to confirm: {len(s['textbook_kind_confirm'])}")
    print("\n  full table: compiler/dist/classification.md  ·  work/classification.jsonl")
