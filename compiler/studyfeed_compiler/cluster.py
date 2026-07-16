"""C5 — near-dup clustering + corroboration (BUILD_PROMPT §5b, §5i).

Embed MCQ stems LOCALLY (fastembed / ONNX bge-small — zero API tokens), cluster near-duplicates by
cosine, and count DISTINCT WORKS per cluster — the "asked at multiple sites" signal (S_corrob) that
is the strongest evidence signal in importance scoring. Flag publisher boilerplate (§5i) so a shared
demo question can't fake corroboration.

Two-phase, per the spec:
  Phase 1 (this module, FREE): embed -> cosine pairs -> union-find on the HIGH band (>=0.92) -> per-
    cluster distinct-work/publisher counts + boilerplate net. Also collects the AMBIGUOUS band
    (0.82-0.92) as cross-cluster candidate pairs and reports their count — the C5 (gate) budget check.
  Phase 2 (gated, separate): send the ambiguous cross-cluster pairs to `cluster-adjudicator` (Opus),
    cache verdicts by pair-hash, merge the "yes" pairs, finalize.

ANN note: the stub/spec calls for hnswlib ANN ("never all-pairs") to scale. At C5 the corpus is only
~9.6k MCQ stems, so EXACT block-wise cosine (O(n^2) ~= 92M dot products, ~1-2s, full recall) is used
here — simpler, dependency-free, and more accurate than approximate ANN. The hnswlib ANN path is built
at C7, where atom clustering (159,689 items) makes O(n^2) genuinely infeasible.

fastembed notes (verified, see PLAYBOOK): vectors are float32 and already L2-normalized, so cosine ==
dot product; run single-process (NEVER `parallel=` — Py3.14 forkserver crash); model is cached under
work/.fastembed_cache and loaded offline via HF_HUB_OFFLINE=1.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
from collections import defaultdict

from .config import WORK_DIR

HIGH = 0.92   # >= HIGH  => same question, auto-merge (union-find)
LOW = 0.82    # [LOW, HIGH) => ambiguous band -> Opus adjudicator (gated, Phase 2)
BOILER_EARLY_PAGE = 10    # "same early position" = front-matter page window (§5i)
MODEL = "BAAI/bge-small-en-v1.5"

CLUSTERS_OUT = WORK_DIR / "mcq_clusters.jsonl"
CORROB_OUT = WORK_DIR / "mcq_corrob.jsonl"                     # per-MCQ sidecar for C9 scoring
ADJUDICATE_OUT = WORK_DIR / "mcq_adjudication_pending.jsonl"   # ambiguous cross-cluster pairs (Phase 2)

_WS = re.compile(r"\s+")


def _norm(s: str) -> str:
    return _WS.sub(" ", (s or "").strip().lower())


def _publisher(kind: str | None) -> str:
    return {"past_paper": "pastest", "qbank": "passmedicine"}.get(kind or "", kind or "unknown")


def _page_int(page_ref) -> int:
    m = re.search(r"\d+", str(page_ref or ""))
    return int(m.group()) if m else 10**9


def load_mcqs() -> list[dict]:
    return [json.loads(l) for l in open(WORK_DIR / "mcqs.jsonl", encoding="utf-8")]


def load_works() -> dict[int, dict]:
    works: dict[int, dict] = {}
    for l in open(WORK_DIR / "classification.jsonl", encoding="utf-8"):
        r = json.loads(l)
        works[r["work_id"]] = {"kind": r.get("kind"), "year": r.get("year"),
                               "name": r.get("name"), "publisher": _publisher(r.get("kind"))}
    return works


def embed(stems: list[str]):
    os.environ.setdefault("FASTEMBED_CACHE_PATH", str(WORK_DIR / ".fastembed_cache"))
    os.environ.setdefault("HF_HUB_OFFLINE", "1")   # model already warmed; stay offline
    import numpy as np
    from fastembed import TextEmbedding
    # attention memory is O(batch * seq^2); the default batch_size=256 OOM-kills this 7.6GB box.
    # Small batch + 2 threads keeps peak RSS well under budget (stems are also length-capped by caller).
    model = TextEmbedding(MODEL, threads=2)
    vecs = [v for v in model.embed(stems, batch_size=32)]
    return np.asarray(vecs, dtype="float32")  # (n,384), L2-normed


def pairs_above(vecs, low: float):
    """All (i, j, sim) with i<j and cosine>=low, computed block-wise (dot product == cosine)."""
    import numpy as np
    n = len(vecs)
    out = []
    B = 512
    for s in range(0, n, B):
        sims = vecs[s:s + B] @ vecs.T           # (b, n)
        for bi in range(sims.shape[0]):
            i = s + bi
            row = sims[bi]
            js = np.nonzero(row[i + 1:] >= low)[0] + (i + 1)
            for j in js:
                out.append((i, int(j), float(row[j])))
    return out


class _UF:
    def __init__(self, n: int):
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


def _is_boilerplate(members: list[int], mcqs: list[dict], norm_stems: list[str]) -> bool:
    """§5i: near-verbatim identical across DISTINCT works AND all at an early (front-matter) page."""
    wids = {mcqs[m]["source_id"] for m in members}
    if len(wids) < 2:
        return False
    if any(_page_int(mcqs[m].get("page_ref")) > BOILER_EARLY_PAGE for m in members):
        return False
    return len({norm_stems[m] for m in members}) == 1   # strictly verbatim after normalization


def finalize_clusters(clusters: dict[int, list[int]], mcqs: list[dict], norm_stems: list[str],
                      works: dict[int, dict]):
    """Per-cluster corroboration stats (§5b) + boilerplate (§5i). Shared by Phase 1 and adjudication."""
    rows, corrob, boiler_ct = [], {}, 0
    for cid, members in clusters.items():
        distinct_works = sorted({mcqs[m]["source_id"] for m in members})
        pubs = {works.get(w, {}).get("publisher", "unknown") for w in distinct_works}
        years = sorted({works.get(w, {}).get("year") for w in distinct_works if works.get(w, {}).get("year")})
        is_boiler = _is_boilerplate(members, mcqs, norm_stems)
        boiler_ct += is_boiler
        row = {
            "cluster_id": cid, "member_idx": sorted(members), "member_count": len(members),
            "distinct_work_count": len(distinct_works),
            "distinct_source_count": len(distinct_works),   # jsonl carries work-level provenance only
            "distinct_publisher_count": len(pubs), "pyq_years": years,
            "is_pyq": any(works.get(w, {}).get("kind") == "past_paper" for w in distinct_works),
            "is_boilerplate": bool(is_boiler), "works": distinct_works,
        }
        rows.append(row)
        for m in members:
            corrob[m] = {
                "idx": m, "cluster_id": cid, "member_count": row["member_count"],
                "distinct_work_count": 0 if is_boiler else row["distinct_work_count"],
                "distinct_publisher_count": 0 if is_boiler else row["distinct_publisher_count"],
                "pyq_years": years, "is_pyq": row["is_pyq"], "is_boilerplate": bool(is_boiler),
            }
    return rows, corrob, boiler_ct


def write_cluster_outputs(rows: list[dict], corrob: dict[int, dict], n: int) -> None:
    for p in (CLUSTERS_OUT, CORROB_OUT):
        if p.exists():
            p.unlink()
    with open(CLUSTERS_OUT, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    with open(CORROB_OUT, "w", encoding="utf-8") as f:
        for m in range(n):
            f.write(json.dumps(corrob[m], ensure_ascii=False) + "\n")


def cluster(_cfg=None) -> dict:
    mcqs = load_mcqs()
    works = load_works()
    n = len(mcqs)
    norm_stems = [_norm(m.get("stem", ""))[:1000] for m in mcqs]   # cap seq length -> bounds embed memory
    print(f"[C5] embedding {n} MCQ stems (fastembed bge-small, ~3 min)...", flush=True)
    vecs = embed(norm_stems)
    print("[C5] computing cosine pairs >= LOW (exact, block-wise)...", flush=True)
    allpairs = pairs_above(vecs, LOW)
    high = [(i, j, s) for (i, j, s) in allpairs if s >= HIGH]
    amb = [(i, j, s) for (i, j, s) in allpairs if s < HIGH]

    uf = _UF(n)
    for i, j, _ in high:
        uf.union(i, j)
    clusters: dict[int, list[int]] = defaultdict(list)
    for idx in range(n):
        clusters[uf.find(idx)].append(idx)

    # Ambiguous pairs that would MERGE two DISTINCT clusters -> the only ones worth adjudicating.
    # Dedup to one representative (highest-sim) pair per unordered cluster-pair.
    cross: dict[tuple[int, int], tuple[int, int, float]] = {}
    for i, j, s in amb:
        ci, cj = uf.find(i), uf.find(j)
        if ci == cj:
            continue
        key = (min(ci, cj), max(ci, cj))
        if key not in cross or s > cross[key][2]:
            cross[key] = (i, j, s)

    rows, corrob, boiler_ct = finalize_clusters(clusters, mcqs, norm_stems, works)
    write_cluster_outputs(rows, corrob, n)
    if ADJUDICATE_OUT.exists():
        ADJUDICATE_OUT.unlink()
    with open(ADJUDICATE_OUT, "w", encoding="utf-8") as f:
        for (ca, cb), (i, j, s) in sorted(cross.items()):
            ph = hashlib.sha256(f"{min(i, j)}|{max(i, j)}".encode()).hexdigest()[:16]
            f.write(json.dumps({"pair_hash": ph, "i": i, "j": j, "cosine": round(s, 4),
                                "stem_i": mcqs[i].get("stem", "")[:600],
                                "stem_j": mcqs[j].get("stem", "")[:600]}, ensure_ascii=False) + "\n")

    multi = [r for r in rows if r["distinct_work_count"] >= 2 and not r["is_boilerplate"]]
    xpub = [r for r in multi if r["distinct_publisher_count"] >= 2]
    sizes = sorted((r["member_count"] for r in rows), reverse=True)
    stats = {
        "mcqs": n, "clusters": len(rows), "singletons": sum(1 for r in rows if r["member_count"] == 1),
        "high_pairs": len(high), "ambiguous_pairs_total": len(amb),
        "ambiguous_cross_cluster_pairs": len(cross),
        "multi_work_clusters": len(multi), "cross_publisher_clusters": len(xpub),
        "boilerplate_clusters": boiler_ct, "largest_clusters": sizes[:8],
    }
    print("[C5] " + json.dumps(stats), flush=True)
    return stats


if __name__ == "__main__":
    import sys
    if len(sys.argv) <= 1 or sys.argv[1] == "cluster":
        cluster()
    else:
        print("usage: python -m studyfeed_compiler.cluster [cluster]")
