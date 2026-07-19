"""C7 — cross-source near-dup MERGE of atoms (BUILD_PROMPT §C7, `dedupe-merger`).

Embed atom statements -> hnswlib ANN (never all-pairs at 133k, §C5/§C7) -> union-find near-dup merge.
Per merge cluster: keep the best-sourced atom as display text, UNION every citation (never drop a
source — source count IS the signal, §5b), count DISTINCT WORKS via workgroups.canonical_map so a book's
own subject-slices (pearls 63-78 -> 63, textbook 80-96 -> 80) can't fake cross-work agreement.
distinct_work_count drives S_corrob (§5b), NOT raw file/source count. Output: atoms_merged.jsonl.

⚠ Embedding cosine ALONE over-merges SHORT atoms that share a template but differ in the key entity
("Digoxin causes prolonged PR" vs "Hypokalaemia causes prolonged PR") — a false merge that LAUNDERS
distinct facts into fake corroboration (§5b/§364). Two defences: (1) a pair must clear BOTH gates —
cosine >= SIM AND idf_jaccard >= MIN_IDF_J (IDF-weighted overlap keys on the rare ENTITY words a
template-sibling does NOT share); (2) LEADER clustering instead of single-linkage union-find — an atom
joins a cluster only if it clears both gates against that cluster's LEADER directly, never through an
intermediate member, so a stray surviving edge can't chain a whole template family into one giant
cluster (union-find's failure mode: one path keeps 74 unrelated facts connected). Precision-first: a
missed reword only softens corroboration; a false merge corrupts it.
"""
from __future__ import annotations

import json
import math
import re
from collections import Counter, defaultdict

from . import cluster as C
from . import workgroups
from .config import WORK_DIR

ATOMS_IN = WORK_DIR / "atoms.jsonl"
MERGED_OUT = WORK_DIR / "atoms_merged.jsonl"
VECS_CACHE = WORK_DIR / "atom_vecs.npy"        # embeddings are deterministic -> cache for fast re-tuning

SIM = 0.95          # cosine gate. Short factual statements -> high bar; no adjudication step (unlike C5).
MIN_IDF_J = 0.50    # IDF-weighted token-overlap gate: separates "same fact reworded" (rare entity words
                    # shared) from "different fact, same template" (rare words differ). Tuned on the
                    # WPW/PR-interval + flucloxacillin cases; precision-first (§5b/§364 anti-laundering).
K = 24              # ANN neighbours probed per atom
EF = 96             # hnswlib query breadth (recall vs speed)
TIER = {"past_paper": 1, "qbank": 2, "textbook": 3, "pearls": 4, "reference": 5}

_WORD = re.compile(r"[a-z0-9]+")
_STOP = set(("a an the is are was were of to in on with and or for as by be been being that this these those "
             "such causes cause caused causing may can result results resulting from into it its which include "
             "includes including due associated typically commonly seen used using more most less than at").split())


def _toks(s: str) -> set[str]:
    return {t for t in _WORD.findall((s or "").lower()) if t not in _STOP and len(t) > 2}


def load_atoms() -> list[dict]:
    return [json.loads(l) for l in open(ATOMS_IN, encoding="utf-8")]


def _embed_cached(norm: list[str], use_cache: bool):
    import numpy as np
    if use_cache and VECS_CACHE.exists():
        v = np.load(VECS_CACHE)
        if v.shape[0] == len(norm):
            print(f"[C7] reusing cached embeddings {v.shape}", flush=True)
            return v
    print(f"[C7] embedding {len(norm)} atom statements (fastembed bge-small)...", flush=True)
    v = C.embed(norm)
    if use_cache:
        np.save(VECS_CACHE, v)
    return v


def _ann_knn(vecs, k: int, ef: int):
    """Return (labels, dists) k-NN arrays from a hnswlib cosine ANN index (distance = 1 - cosine)."""
    import hnswlib
    import numpy as np
    n, dim = vecs.shape
    idx = hnswlib.Index(space="cosine", dim=dim)
    idx.init_index(max_elements=n, ef_construction=200, M=16)
    idx.add_items(vecs, np.arange(n))
    idx.set_ef(max(ef, k + 1))
    return idx.knn_query(vecs, k=min(k, n))


def dedupe(_cfg=None, sample: int | None = None, sim: float = SIM, min_idf_j: float = MIN_IDF_J,
           inspect: int = 0) -> dict:
    atoms = load_atoms()
    if sample:                                          # strided -> spans all works
        atoms = atoms[:: max(1, len(atoms) // sample)][:sample]
    n = len(atoms)
    works = C.load_works()
    cmap = workgroups.canonical_map()

    # IDF over the statements being processed (rare entity words get the weight)
    df: Counter = Counter()
    tok_sets = [_toks(a.get("statement", "")) for a in atoms]
    for ts in tok_sets:
        for t in ts:
            df[t] += 1
    def _idf(t: str) -> float:
        return math.log((n + 1) / (df.get(t, 0) + 1)) + 1.0

    def _idf_jaccard(i: int, j: int) -> float:
        ta, tb = tok_sets[i], tok_sets[j]
        if not ta or not tb:
            return 0.0
        inter = ta & tb
        if not inter:
            return 0.0
        num = sum(_idf(t) for t in inter)
        den = sum(_idf(t) for t in ta | tb)
        return num / den if den else 0.0

    norm = [C._norm(a.get("statement", ""))[:500] for a in atoms]
    vecs = _embed_cached(norm, use_cache=not sample)
    print(f"[C7] ANN + LEADER merge (hnswlib k={K}; join iff cosine>={sim} AND idf_jaccard>={min_idf_j} "
          f"vs the cluster LEADER, never an intermediate)...", flush=True)
    labels, dists = _ann_knn(vecs, K, EF)
    # Process longest (most complete) statements first as candidate leaders; each atom joins the
    # highest-cosine LEADER it clears both gates against, else becomes its own leader. Matching the
    # leader (not any member) is what stops a template family chaining into one giant false cluster.
    order = sorted(range(n), key=lambda m: -len(atoms[m].get("statement", "")))
    assign = [-1] * n
    n_join = 0
    for i in order:
        best, best_cos = None, sim
        for lbl, d in zip(labels[i], dists[i]):
            l = int(lbl)
            if l == i or assign[l] != l:                 # l must already be a cluster LEADER
                continue
            cos = 1.0 - float(d)
            if cos >= best_cos and _idf_jaccard(i, l) >= min_idf_j:
                best, best_cos = l, cos
        assign[i] = best if best is not None else i
        if best is not None:
            n_join += 1
    clusters: dict[int, list[int]] = defaultdict(list)
    for idx in range(n):
        clusters[assign[idx]].append(idx)

    def _tier(m: int) -> int:
        return TIER.get(works.get(atoms[m]["source_id"], {}).get("kind"), 9)

    merged_rows, merge_clusters = [], 0
    for members in clusters.values():
        rep = min(members, key=lambda m: (_tier(m), -len(atoms[m].get("statement", "")),
                                          atoms[m]["source_id"], str(atoms[m].get("page_ref", ""))))
        cites = sorted({(atoms[m]["source_id"], str(atoms[m].get("page_ref", ""))) for m in members})
        cworks = {cmap.get(atoms[m]["source_id"], atoms[m]["source_id"]) for m in members}
        pubs = {C._publisher(works.get(atoms[m]["source_id"], {}).get("kind")) for m in members}
        if len(members) > 1:
            merge_clusters += 1
        a = atoms[rep]
        merged_rows.append({
            "statement": a.get("statement", ""), "detail": a.get("detail", ""),
            "source_id": a["source_id"], "page_ref": a.get("page_ref"),
            "member_count": len(members),
            "distinct_work_count": len(cworks),            # ⇦ drives S_corrob (§5b)
            "distinct_source_count": len({s for s, _ in cites}),
            "distinct_publisher_count": len(pubs),
            "citations": [{"source_id": s, "page_ref": p} for s, p in cites],
        })

    if not sample:
        if MERGED_OUT.exists():
            MERGED_OUT.unlink()
        with open(MERGED_OUT, "w", encoding="utf-8") as f:
            for r in merged_rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")

    if inspect:
        big = sorted((c for c in clusters.values() if len(c) > 1), key=len, reverse=True)[:inspect]
        for c in big:
            wk = sorted({cmap.get(atoms[m]["source_id"], atoms[m]["source_id"]) for m in c})
            print(f"  --- {len(c)} atoms | works {wk} ---", flush=True)
            for m in c[:6]:
                print(f"     w{atoms[m]['source_id']} p{atoms[m].get('page_ref')}: "
                      f"{atoms[m].get('statement', '')[:100]}", flush=True)

    dwc = Counter(r["distinct_work_count"] for r in merged_rows)
    stats = {"atoms_in": n, "atoms_merged_out": len(merged_rows), "collapsed": n - len(merged_rows),
             "joined": n_join, "merge_clusters": merge_clusters,
             "largest_cluster": max((r["member_count"] for r in merged_rows), default=0),
             "multi_work_atoms": sum(1 for r in merged_rows if r["distinct_work_count"] >= 2),
             "distinct_work_hist": {k: dwc[k] for k in sorted(dwc)[:8]}}
    print("[C7] " + json.dumps(stats), flush=True)
    return stats


if __name__ == "__main__":
    import sys
    smp = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1] != "-" else None
    sm = float(sys.argv[2]) if len(sys.argv) > 2 else SIM
    jj = float(sys.argv[3]) if len(sys.argv) > 3 else MIN_IDF_J
    ins = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    dedupe(sample=smp, sim=sm, min_idf_j=jj, inspect=ins)
