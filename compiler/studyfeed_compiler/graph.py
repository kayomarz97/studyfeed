"""C8 — concept graph (BUILD_PROMPT §C8 / §4 concept-grapher). Typed grounded edges + PageRank centrality.

Sonnet (Batch API) extracts (subject, relation, object) triples per merged atom; concept nodes are folded
by alias.Aliases so ONE entity = ONE node (haemoglobin=hemoglobin, frusemide=furosemide,
adrenaline=epinephrine); genuinely novel look-alikes are PROPOSED (dist/alias_proposals.yaml, never
auto-merged, §5e); PageRank over the directed edges -> concepts.centrality (S_centrality, C9). Edges are
grounded (source_id + rationale) and deduped, carrying an agreement count. guideline_sensitive is set on
concepts whose facts concern management/target/threshold (feeds staleness + C9).

Pipeline: `submit` -> poll -> `harvest` (parse triples -> work/graph_edges_raw.jsonl) -> `build`
(fold + dedupe + PageRank -> concepts.jsonl + concept_edges.jsonl + proposals). `test N` validates
the prompt on a strided sample and extrapolates cost BEFORE any full spend.
"""
from __future__ import annotations

import json
import re
import time
from collections import Counter, defaultdict

from . import alias as A
from . import llm
from .config import WORK_DIR

ATOMS = WORK_DIR / "atoms_merged.jsonl"
EDGES_RAW = WORK_DIR / "graph_edges_raw.jsonl"
CONCEPTS_OUT = WORK_DIR / "concepts.jsonl"
EDGES_OUT = WORK_DIR / "concept_edges.jsonl"
PROPOSALS_OUT = WORK_DIR.parent / "dist" / "alias_proposals.yaml"

MODEL = llm.MODEL_ATOMIZE                  # Haiku for bulk extraction (full coverage, ~1/3 Sonnet cost);
                                           # a Sonnet subagent verifies the ambiguous edges post-hoc.
BATCH = 12                                 # atoms per request
RELATIONS = {"causes", "treats", "contraindicates", "associated_with", "differential_of",
             "complication_of", "investigated_by", "mechanism_of", "risk_factor_for", "first_line_for"}
# management/target/threshold -> guideline_sensitive (§C6 staleness / §C8)
_GS = re.compile(r"(?i)\b(first[- ]line|target|aim for|threshold|titrat|<\s*\d|>\s*\d|mmol|mg/day|"
                 r"guideline|recommend|maintenance dose|initial dose|treatment of choice)\b")

GRAPH_SYS = (
    "You build a medical CONCEPT GRAPH from MRCP(UK) exam-revision facts. You are given several numbered "
    "facts. For EACH fact, extract the concept-to-concept relationships it EXPLICITLY states. Output ONE "
    "relationship PER LINE in the EXACT form:  N|subject|relation|object  — where N is the fact's number. "
    "Allowed relations (use EXACTLY one, nothing else): causes, treats, contraindicates, associated_with, "
    "differential_of, complication_of, investigated_by, mechanism_of, risk_factor_for, first_line_for. "
    "Concepts must be canonical medical ENTITIES — a drug, disease, syndrome, finding, or investigation — "
    "a short noun phrase in UK spelling, singular, no leading articles, no numeric values. Emit a line "
    "ONLY when the fact clearly asserts that relation between two NAMED entities; emit NOTHING for a fact "
    "that is a definition, a bare value, or has no clear relation. Prefer the most specific relation "
    "(first_line_for over treats when it says first-line; contraindicates over associated_with when it "
    "says avoid/contraindicated). Output ONLY these pipe-delimited lines — no JSON, no markdown, no prose, "
    "no header, no blank lines."
)


def load_atoms() -> list[dict]:
    return [json.loads(l) for l in open(ATOMS, encoding="utf-8")]


def build_requests(atoms: list[dict], limit: int | None = None) -> list[dict]:
    reqs = []
    for start in range(0, len(atoms), BATCH):
        chunk = atoms[start:start + BATCH]
        body = "\n".join(f"{i + 1}. {llm._clean(a.get('statement', ''))}" for i, a in enumerate(chunk))
        reqs.append(llm._req(f"graph_{start}", MODEL, GRAPH_SYS, body[:12000], 2000))
        if limit and len(reqs) >= limit:
            break
    return reqs


def _parse_triples(cid: str, text: str, atoms: list[dict]) -> list[dict]:
    """Parse `N|subject|relation|object` lines back to grounded edges (src, rel, dst, source_id, rationale)."""
    start = int(cid.split("_")[1])
    out = []
    for line in text.splitlines():
        parts = line.strip().split("|", 3)
        if len(parts) < 4:
            continue
        m = re.match(r"\s*(\d+)", parts[0])
        if not m:
            continue
        gi = start + int(m.group(1)) - 1
        if gi < 0 or gi >= len(atoms):
            continue
        s, r, o = parts[1].strip(), parts[2].strip().lower(), parts[3].strip()
        if s and o and r in RELATIONS and s.lower() != o.lower():
            atom = atoms[gi]
            out.append({"s": s, "r": r, "o": o, "source_id": atom.get("source_id"),
                        "rationale": atom.get("statement", "")[:240]})
    return out


def harvest() -> dict:
    atoms = load_atoms()
    if EDGES_RAW.exists():
        EDGES_RAW.unlink()
    n_ok = n_err = rows = 0
    with open(EDGES_RAW, "w", encoding="utf-8") as f:
        for b in llm.state.read_state().get("batches", []):
            if b.get("stage") != "C8" or b["kind"] != "graph":
                continue
            for cid, ok, payload, _usage in llm.iter_results(b["id"]):
                if not ok:
                    n_err += 1
                    continue
                n_ok += 1
                for e in _parse_triples(cid, payload, atoms):
                    f.write(json.dumps(e, ensure_ascii=False) + "\n")
                    rows += 1
    out = {"responses_ok": n_ok, "responses_err": n_err, "raw_edges": rows}
    print("[C8-harvest] " + json.dumps(out), flush=True)
    return out


def _levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a or not b:
        return len(a) or len(b)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


_TEST_TERM = re.compile(
    r"(?i)\b(mri|ct scan|ct angiogra|ultrasound|echocardiogra|echo|biopsy|x-?ray|radiograph|scan|"
    r"culture|screen(ing)?|studies|imaging|levels?|\btest(ing)?|ecg|eeg|count|crp|esr|endoscopy|"
    r"colonoscopy|gastroscopy|angiogra|dexa|spirometry|fbc|serology|titre|aspirate|fundoscopy|"
    r"tomography|nerve conduction|pcr|urinalysis|swab|film|assay)\b")


def _looks_test(s: str) -> bool:
    return bool(_TEST_TERM.search(s or ""))


def build(_cfg=None) -> dict:
    import networkx as nx
    al = A.Aliases.from_profile()
    raw = [json.loads(l) for l in open(EDGES_RAW, encoding="utf-8")]

    # Sonnet-audit fix: `investigated_by` must read disease→test. Haiku reversed ~6/8 in the audit
    # (e.g. "echocardiography investigated_by coarctation"). Deterministic swap where src is clearly a
    # test/investigation and dst is not — the single highest-yield direction fix (~1/3 of all edge errors).
    swapped = 0
    for e in raw:
        if e["r"] == "investigated_by" and _looks_test(e["s"]) and not _looks_test(e["o"]):
            e["s"], e["o"] = e["o"], e["s"]
            swapped += 1

    # 1) fold concepts by alias key -> canonical node (display = most frequent surface form)
    surface: dict[str, Counter] = defaultdict(Counter)     # merge_key -> {surface_form: count}
    gs_hits: dict[str, int] = defaultdict(int)
    for e in raw:
        for side in ("s", "o"):
            name = e[side]
            k = al.key(name)
            surface[k][name] += 1
            if _GS.search(e.get("rationale", "")):
                gs_hits[k] += 1
    key2id, concepts = {}, []
    for cid, (k, ctr) in enumerate(sorted(surface.items())):
        disp = al.prefers(ctr.most_common(1)[0][0])        # preferred display, UK spelling
        key2id[k] = cid
        concepts.append({"id": cid, "name": disp, "canonical_key": k,
                         "aliases": sorted(x for x in ctr if x != disp),
                         "guideline_sensitive": 1 if gs_hits[k] else 0, "mentions": sum(ctr.values())})

    # 2) dedupe edges by (src,dst,relation); union grounding; count agreement
    edges: dict[tuple, dict] = {}
    for e in raw:
        si, di = key2id[al.key(e["s"])], key2id[al.key(e["o"])]
        if si == di:
            continue
        key = (si, di, e["r"])
        row = edges.setdefault(key, {"src": si, "dst": di, "relation": e["r"], "count": 0,
                                     "source_ids": set(), "rationale": e.get("rationale", "")})
        row["count"] += 1
        if e.get("source_id") is not None:
            row["source_ids"].add(e["source_id"])

    # 3) PageRank over the directed, agreement-weighted graph -> centrality
    G = nx.DiGraph()
    G.add_nodes_from(c["id"] for c in concepts)
    for (si, di, _r), row in edges.items():
        w = (G[si][di]["weight"] + row["count"]) if G.has_edge(si, di) else row["count"]
        G.add_edge(si, di, weight=w)
    pr = nx.pagerank(G, weight="weight") if G.number_of_edges() else {c["id"]: 0.0 for c in concepts}
    mx = max(pr.values()) if pr else 1.0
    for c in concepts:
        c["centrality"] = round(pr.get(c["id"], 0.0) / mx, 6)   # normalised 0..1

    # 4) write concepts + edges
    for p, rows in ((CONCEPTS_OUT, concepts),
                    (EDGES_OUT, [{"src_concept_id": r["src"], "dst_concept_id": r["dst"],
                                  "relation": r["relation"], "agreement": r["count"],
                                  "distinct_source_count": len(r["source_ids"]),
                                  "source_ids": sorted(r["source_ids"]), "rationale": r["rationale"]}
                                 for r in edges.values()])):
        if p.exists():
            p.unlink()
        with open(p, "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")

    # 5) novel-alias PROPOSALS (⛔ gate): distinct keys whose display forms are near-identical -> user review
    names = sorted({(c["canonical_key"], c["name"]) for c in concepts if c["mentions"] >= 3})
    proposals = []
    by_first = defaultdict(list)
    for k, nm in names:
        by_first[k[:4]].append((k, nm))
    for bucket in by_first.values():
        for i in range(len(bucket)):
            for j in range(i + 1, len(bucket)):
                (ka, na), (kb, nb) = bucket[i], bucket[j]
                d = _levenshtein(ka, kb)
                if 0 < d <= 2 and abs(len(ka) - len(kb)) <= 2:   # near-identical but not already merged
                    proposals.append({"a": na, "b": nb, "keys": [ka, kb], "edit_distance": d})
    PROPOSALS_OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(PROPOSALS_OUT, "w", encoding="utf-8") as f:
        f.write("# C8 novel-alias proposals — REVIEW, then add real synonyms to profile.nomenclature.alias_hints.\n")
        f.write("# Each pair is two DISTINCT concept nodes with near-identical keys (possible split entity).\n")
        f.write(f"# generated: {len(proposals)} candidate pairs\n")
        for p_ in proposals[:400]:
            f.write(f"- a: {json.dumps(p_['a'])}\n  b: {json.dumps(p_['b'])}\n  edit_distance: {p_['edit_distance']}\n")

    gs = sum(c["guideline_sensitive"] for c in concepts)
    top = sorted(concepts, key=lambda c: c["centrality"], reverse=True)[:12]
    stats = {"concepts": len(concepts), "edges": len(edges), "raw_edges": len(raw),
             "investigated_by_swapped": swapped,
             "guideline_sensitive": gs, "alias_proposals": len(proposals),
             "relations": dict(Counter(r["relation"] for r in edges.values())),
             "top_central": [(c["name"], c["centrality"]) for c in top]}
    print("[C8] " + json.dumps(stats, ensure_ascii=False), flush=True)
    return stats


def _run_test(sample: int = 24) -> bool:
    atoms = load_atoms()
    strided = atoms[:: max(1, len(atoms) // sample)][:sample]
    reqs = build_requests(strided)
    print(f"TEST(graph): {len(strided)} atoms in {len(reqs)} requests", flush=True)
    bid = llm.submit(reqs, "test", "C8-test")
    print("batch:", bid, flush=True)
    while True:
        st, counts = llm.poll(bid)
        print("  poll:", st, counts, flush=True)
        if st == "ended":
            break
        time.sleep(20)
    tin = tout = errs = 0
    triples = []
    for cid, ok, payload, usage in llm.iter_results(bid):
        if not ok:
            errs += 1
            continue
        tin += usage.input_tokens
        tout += usage.output_tokens
        triples += _parse_triples(cid, payload, strided)
    full_reqs = (len(atoms) + BATCH - 1) // BATCH
    cost = llm._cost(MODEL, tin / max(1, len(reqs)) * full_reqs, tout / max(1, len(reqs)) * full_reqs)
    print(f"\nTEST DONE. errors={errs} | {len(triples)} triples from {len(strided)} atoms", flush=True)
    print(f"  tokens {tin}in/{tout}out over {len(reqs)} reqs | FULL {full_reqs} reqs -> EXTRAPOLATED ${cost:.2f}", flush=True)
    for tr in triples[:12]:
        print(f"    ({tr['s']}) -{tr['r']}-> ({tr['o']})", flush=True)
    ok = errs == 0 and len(triples) > 0 and all(t["r"] in RELATIONS for t in triples)
    print(f"\nTEST {'PASS' if ok else 'FAIL'}", flush=True)
    return ok


if __name__ == "__main__":
    import sys
    mode = sys.argv[1] if len(sys.argv) > 1 else "test"
    if mode == "test":
        _run_test(int(sys.argv[2]) if len(sys.argv) > 2 else 24)
    elif mode == "submit":
        reqs = build_requests(load_atoms())
        print(f"submitting {len(reqs)} graph requests (model={MODEL})", flush=True)
        print("batches:", llm.submit_chunked(reqs, "graph", "C8"), flush=True)
    elif mode == "harvest":
        harvest()
    elif mode == "build":
        build()
