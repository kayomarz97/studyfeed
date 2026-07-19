"""File-level dedupe BEFORE any source row exists (BUILD_PROMPT.md §5b).

The score-critical stage: if one PDF registers as two sources, every fact in it fakes corroboration
(24% of the score). So collapse duplicates at the FILE level, here, before any text is atomized.

Three tiers (§5b), zero tokens:
  1. exact SHA-256            -> auto-collapse (record alias paths).
  2. content fingerprint      -> (page_count, normalized sampled-text hash) match, different bytes:
                                 probable dup -> report (and merge into the same work).
  3. fuzzy shingle-Jaccard    -> >= profile threshold: POSSIBLE dup -> ASK (never auto-merge).

At 107 files a pairwise Jaccard over sampled-page 5-gram shingles is exact and cheap — the MinHash/ANN
machinery is reserved for C5's 15k+ MCQs. Also flags: cross-drop tier conflicts (same content in two
drops => different tiers, 33% of the score => the human decides) and possible editions of one work.
Uses only pdfinfo + pdftotext (no page ever enters a context window, §10c).
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import zlib
from itertools import combinations
from pathlib import Path
from typing import Any

from .config import Config, UNPACKED_DIR, WORK_DIR, DIST_DIR

# tier weights per kind, to describe a cross-drop conflict in the report
_DROP_TIER = {"Papers": "1 (past_paper)", "Qbank": "2 (qbank)", "Textbook": "3/4 (textbook/pearls/ref)"}


class _UF:
    def __init__(self, n: int) -> None:
        self.p = list(range(n))

    def find(self, x: int) -> int:
        while self.p[x] != x:
            self.p[x] = self.p[self.p[x]]
            x = self.p[x]
        return x

    def union(self, a: int, b: int) -> None:
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.p[ra] = rb


def _sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _pageinfo(p: Path) -> int:
    try:
        r = subprocess.run(["pdfinfo", str(p)], capture_output=True, text=True, timeout=120)
        for line in r.stdout.splitlines():
            if line.startswith("Pages:"):
                return int(line.split(":", 1)[1].strip())
    except Exception:
        pass
    return -1


def _pdftotext(p: Path, first: int, last: int) -> str:
    try:
        r = subprocess.run(["pdftotext", "-q", "-f", str(first), "-l", str(last), str(p), "-"],
                           capture_output=True, text=True, timeout=120)
        return r.stdout if r.returncode == 0 else ""
    except Exception:
        return ""


def _sample_pages(pages: int) -> list[int]:
    """Interior CONTENT pages, spread across the doc — deliberately NOT first/last pages: past papers
    share identical cover/instruction boilerplate there, which falsely merges distinct dated papers."""
    if pages <= 0:
        return [1]
    if pages <= 6:
        return list(range(1, pages + 1))
    fracs = [0.13, 0.28, 0.43, 0.58, 0.73, 0.88]
    return sorted({min(pages, max(1, round(fr * pages))) for fr in fracs})


def _sampled_text(p: Path, pages: int) -> str:
    return "\n".join(_pdftotext(p, pg, pg) for pg in _sample_pages(pages))


def _normalize(t: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", t.lower())).strip()


def _shingles(text: str, k: int = 5) -> set[int]:
    words = _normalize(text).split()
    if len(words) < k:
        return {zlib.crc32(w.encode()) for w in words}
    return {zlib.crc32(" ".join(words[i:i + k]).encode()) for i in range(len(words) - k + 1)}


def _thash(text: str) -> str:
    return hashlib.blake2b(_normalize(text).encode(), digest_size=16).hexdigest()


def _jaccard(a: set[int], b: set[int]) -> float:
    if not a or not b:
        return 0.0
    inter = len(a & b)
    return inter / (len(a) + len(b) - inter)


def _title_key(name: str) -> str:
    s = re.sub(r"[^a-z]", " ", name.lower().rsplit(".", 1)[0])
    return " ".join(w for w in s.split() if len(w) > 2)


def run(cfg: Config) -> dict[str, Any]:
    inv = [json.loads(l) for l in open(WORK_DIR / "inventory.jsonl", encoding="utf-8")]
    MIN_WORDS = 30  # below this, sampled text is empty/near-empty => scanned image => text-blind
    files: list[dict] = []
    for i, r in enumerate(inv):
        p = UNPACKED_DIR / r["path"]
        pages = _pageinfo(p)
        text = _sampled_text(p, pages)
        scanned = len(_normalize(text).split()) < MIN_WORDS
        files.append({
            "idx": i, "drop": r["drop"], "path": r["path"], "name": r["name"], "size": r["size"],
            "sha256": _sha256(p), "pages": pages, "scanned": scanned,
            # scanned files carry NO text signature: they may only be merged by exact SHA-256, never
            # by fingerprint/fuzzy (empty text collides). Content dedup for them happens at C5.
            "thash": _thash(text), "shingles": (set() if scanned else _shingles(text)),
        })
    n = len(files)
    uf = _UF(n)
    fuzzy_thr = float(cfg.dedupe.get("fuzzy_minhash_threshold", 0.90))

    # tier 1: exact sha256
    by_sha: dict[str, list[int]] = {}
    for f in files:
        by_sha.setdefault(f["sha256"], []).append(f["idx"])
    exact_groups = [g for g in by_sha.values() if len(g) > 1]
    for g in exact_groups:
        for j in g[1:]:
            uf.union(g[0], j)

    # tier 2: fingerprint (pages, normalized-sampled-text hash), different bytes
    by_fp: dict[tuple, list[int]] = {}
    for f in files:
        if f["pages"] > 0 and not f["scanned"]:
            by_fp.setdefault((f["pages"], f["thash"]), []).append(f["idx"])
    fp_groups = [g for g in by_fp.values() if len(g) > 1 and len({files[i]["sha256"] for i in g}) > 1]
    for g in fp_groups:
        for j in g[1:]:
            uf.union(g[0], j)

    # tier 3: fuzzy pairwise (not-yet-merged), plus editions heuristic
    possible: list[tuple[int, int, float]] = []
    editions: list[tuple[int, int, float]] = []
    for a, b in combinations(range(n), 2):
        if uf.find(a) == uf.find(b):
            continue
        fa, fb = files[a], files[b]
        if fa["scanned"] or fb["scanned"]:
            continue  # text-blind: no fuzzy merging (empty text would collide)
        jac = _jaccard(fa["shingles"], fb["shingles"])
        if jac >= fuzzy_thr:
            possible.append((a, b, round(jac, 3)))
        elif jac >= 0.45 and _title_key(fa["name"]) and _title_key(fa["name"]) == _title_key(fb["name"]):
            editions.append((a, b, round(jac, 3)))

    # assign work_id per union-find component; pick largest file as canonical
    comp: dict[int, list[int]] = {}
    for i in range(n):
        comp.setdefault(uf.find(i), []).append(i)
    work_of = {root: wid for wid, root in enumerate(sorted(comp))}
    canonicals: list[dict] = []
    for root, members in comp.items():
        members = sorted(members, key=lambda i: files[i]["size"], reverse=True)
        keep = files[members[0]]
        aliases = [{"drop": files[m]["drop"], "path": files[m]["path"], "sha256": files[m]["sha256"]}
                   for m in members[1:]]
        drops = sorted({files[m]["drop"] for m in members})
        canonicals.append({
            "work_id": work_of[root], "drop": keep["drop"], "path": keep["path"], "name": keep["name"],
            "size": keep["size"], "sha256": keep["sha256"], "pages": keep["pages"], "scanned": keep["scanned"],
            "aliases": aliases, "cross_drop": len(drops) > 1, "member_drops": drops,
        })

    # cross-drop tier conflicts: confident-merged group OR a possible-dup pair spanning drops
    cross_drop_merges = [c for c in canonicals if c["cross_drop"]]
    cross_drop_possible = [(a, b, j) for (a, b, j) in possible if files[a]["drop"] != files[b]["drop"]]

    _write_report(cfg, files, exact_groups, fp_groups, possible, editions,
                  cross_drop_merges, cross_drop_possible, canonicals)
    _write_canonicals(canonicals, possible, editions, files)

    return {
        "files": n, "canonical": len(canonicals),
        "scanned": sum(1 for f in files if f["scanned"]),
        "exact_collapsed": sum(len(g) - 1 for g in exact_groups),
        "fingerprint_merged": sum(len(g) - 1 for g in fp_groups),
        "possible_ask": len(possible), "editions": len(editions),
        "cross_drop_merges": len(cross_drop_merges), "cross_drop_possible": len(cross_drop_possible),
    }


def _f(files: list[dict], i: int) -> str:
    return f"{files[i]['drop']}/{files[i]['name']} ({files[i]['pages']}pp, {files[i]['size'] // 1024**2}MB)"


def _write_canonicals(canonicals, possible, editions, files) -> None:
    with open(WORK_DIR / "dedupe_canonicals.jsonl", "w", encoding="utf-8") as f:
        for c in canonicals:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    with open(WORK_DIR / "dedupe_pending.jsonl", "w", encoding="utf-8") as f:
        for a, b, j in possible:
            f.write(json.dumps({"type": "possible_dup", "jaccard": j,
                                "a": files[a]["path"], "b": files[b]["path"],
                                "a_drop": files[a]["drop"], "b_drop": files[b]["drop"]}, ensure_ascii=False) + "\n")
        for a, b, j in editions:
            f.write(json.dumps({"type": "possible_edition", "jaccard": j,
                                "a": files[a]["path"], "b": files[b]["path"]}, ensure_ascii=False) + "\n")


def _write_report(cfg, files, exact_groups, fp_groups, possible, editions,
                  cross_drop_merges, cross_drop_possible, canonicals) -> None:
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    L = ["# Dedupe report (§5b) — file-level, before any source row\n",
         f"- input files: **{len(files)}**  →  canonical works: **{len(canonicals)}**",
         f"- scanned/image PDFs (text-blind — exact-hash dedup only; content dedup at C5 post-OCR): {sum(1 for f in files if f['scanned'])}",
         f"- exact SHA-256 collapses: {sum(len(g)-1 for g in exact_groups)} · fingerprint merges: {sum(len(g)-1 for g in fp_groups)}",
         f"- possible dups to CONFIRM (⛔): {len(possible)} · possible editions: {len(editions)}",
         f"- **cross-drop tier conflicts (⛔): {len(cross_drop_merges)} merged + {len(cross_drop_possible)} fuzzy**\n"]

    if cross_drop_merges or cross_drop_possible:
        L.append("## ⛔ Cross-drop tier conflicts — same content across drops (tier gates 33% of the score)\n")
        for c in cross_drop_merges:
            tiers = ", ".join(f"{d} = tier {_DROP_TIER.get(d,'?')}" for d in c["member_drops"])
            L.append(f"- **{c['name']}** appears in {c['member_drops']} → {tiers}. Which tier is authoritative?")
        for a, b, j in cross_drop_possible:
            L.append(f"- (fuzzy {j}) {_f(files,a)}  ≈  {_f(files,b)} → tiers {_DROP_TIER.get(files[a]['drop'])} vs {_DROP_TIER.get(files[b]['drop'])}")
        L.append("")

    if exact_groups:
        L.append("## Exact duplicates (SHA-256) — auto-collapsed\n")
        for g in exact_groups:
            L.append(f"- {_f(files,g[0])}  ← aliases: " + "; ".join(files[i]["path"] for i in g[1:]))
        L.append("")
    if fp_groups:
        L.append("## Fingerprint matches (same pages + text, different bytes) — merged, please confirm\n")
        for g in fp_groups:
            L.append("- " + "  ≈  ".join(_f(files, i) for i in g))
        L.append("")
    if possible:
        L.append("## ⛔ Possible duplicates (fuzzy ≥ threshold) — CONFIRM before merging\n")
        for a, b, j in possible:
            L.append(f"- jaccard {j}: {_f(files,a)}  ≈  {_f(files,b)}")
        L.append("")
    if editions:
        L.append("## Possible editions of one work (same title, different content) — one voice?\n")
        for a, b, j in editions:
            L.append(f"- jaccard {j}: {_f(files,a)}  vs  {_f(files,b)}")
        L.append("")
    (DIST_DIR / "dedupe_report.md").write_text("\n".join(L), encoding="utf-8")


if __name__ == "__main__":
    cfg = Config.for_exam("mrcp_part1")
    print("hashing + sampling 107 PDFs (pdfinfo + pdftotext)…")
    s = run(cfg)
    print("\n=== DEDUPE SUMMARY ===")
    for k, v in s.items():
        print(f"  {k:22s} {v}")
    print("\n  report: compiler/dist/dedupe_report.md  ·  canonicals: work/dedupe_canonicals.jsonl")
