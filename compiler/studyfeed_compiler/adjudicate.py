"""C5 Phase 2 — adjudicate the ambiguous band + merge (BUILD_PROMPT §5b, `cluster-adjudicator`).

For pairs with cosine >= MIN_COS, ask Opus "same underlying MCQ question?"; cache verdicts by
pair_hash (re-runs free/reproducible); merge the YES pairs into the Phase-1 clusters and rewrite
mcq_clusters.jsonl + mcq_corrob.jsonl with the updated corroboration counts.

Sync + small thread-pool (results in minutes, not a multi-hour batch). Stems are truncated to ~450
chars in the prompt — enough to judge same-vs-different for already-similar pairs — which keeps the
Opus cost at the approved ~$6 for the >=0.89 band even without the batch discount.
"""
from __future__ import annotations

import json
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

from . import cluster as C
from .config import WORK_DIR

OPUS = "claude-opus-4-8"          # config.MODEL_ROUTING["judge"]
MIN_COS = 0.89
STEM_CHARS = 450
PENDING = WORK_DIR / "mcq_adjudication_pending.jsonl"
VERDICTS = WORK_DIR / "mcq_adjudication_verdicts.jsonl"

SYS = ("You judge whether two multiple-choice question stems are the SAME underlying exam question "
       "(same clinical scenario testing the same teaching point, even if reworded) or DIFFERENT "
       "questions. Reply ONLY with compact JSON: {\"same\": true|false, \"reason\": \"<=12 words\"}.")


def _client():
    from .llm import load_env
    load_env()
    import anthropic
    return anthropic.Anthropic()


def _judge(client, pair: dict, retries: int = 4) -> dict:
    user = (f"Q1: {pair['stem_i'][:STEM_CHARS]}\n\nQ2: {pair['stem_j'][:STEM_CHARS]}\n\n"
            "Same underlying question?")
    for attempt in range(retries):
        try:
            msg = client.messages.create(
                model=OPUS, max_tokens=200,
                system=[{"type": "text", "text": SYS}],
                messages=[{"role": "user", "content": user}])
            text = "".join(b.text for b in msg.content if getattr(b, "type", None) == "text")
            try:
                obj = json.loads(text[text.index("{"):text.rindex("}") + 1])
                same, reason = bool(obj.get("same")), str(obj.get("reason", ""))[:80]
            except Exception:
                same, reason = False, "parse_fail:" + text[:40]
            return {"pair_hash": pair["pair_hash"], "i": pair["i"], "j": pair["j"],
                    "cosine": pair["cosine"], "same": same, "reason": reason,
                    "tok_in": msg.usage.input_tokens, "tok_out": msg.usage.output_tokens}
        except Exception as e:
            if attempt == retries - 1:
                return {"pair_hash": pair["pair_hash"], "i": pair["i"], "j": pair["j"],
                        "cosine": pair["cosine"], "same": False,
                        "reason": f"error:{type(e).__name__}", "tok_in": 0, "tok_out": 0}
            time.sleep(2 * (attempt + 1))


def _load_cache() -> dict:
    if not VERDICTS.exists():
        return {}
    return {(v := json.loads(l))["pair_hash"]: v for l in open(VERDICTS, encoding="utf-8")}


def _merge_and_rewrite(yes_pairs: list[dict]) -> dict:
    mcqs, works = C.load_mcqs(), C.load_works()
    n = len(mcqs)
    norm_stems = [C._norm(m.get("stem", ""))[:1000] for m in mcqs]
    clist = [json.loads(l) for l in open(C.CLUSTERS_OUT, encoding="utf-8")]
    idx2c = {m: r["cluster_id"] for r in clist for m in r["member_idx"]}
    cluster_ids = sorted({r["cluster_id"] for r in clist})
    cmap = {c: i for i, c in enumerate(cluster_ids)}
    uf = C._UF(len(cluster_ids))
    before_multi = sum(1 for r in clist if r["distinct_work_count"] >= 2 and not r["is_boilerplate"])
    for p in yes_pairs:
        ci, cj = idx2c.get(p["i"]), idx2c.get(p["j"])
        if ci is None or cj is None or ci == cj:
            continue
        uf.union(cmap[ci], cmap[cj])
    merged: dict[int, list[int]] = {}
    for r in clist:
        merged.setdefault(uf.find(cmap[r["cluster_id"]]), []).extend(r["member_idx"])
    rows, corrob, _ = C.finalize_clusters(merged, mcqs, norm_stems, works)
    C.write_cluster_outputs(rows, corrob, n)
    after_multi = sum(1 for r in rows if r["distinct_work_count"] >= 2 and not r["is_boilerplate"])
    return {"clusters_before": len(clist), "clusters_after": len(rows),
            "multi_work_before": before_multi, "multi_work_after": after_multi}


def adjudicate(min_cos: float = MIN_COS, workers: int = 5) -> dict:
    pending = [p for p in (json.loads(l) for l in open(PENDING, encoding="utf-8")) if p["cosine"] >= min_cos]
    cached = _load_cache()
    todo = [p for p in pending if p["pair_hash"] not in cached]
    print(f"[C5-adj] band>={min_cos}: {len(pending)} pairs | {len(cached)} cached | {len(todo)} to judge (Opus)", flush=True)
    results = list(cached.values())
    tin = tout = 0
    if todo:
        client = _client()
        with ThreadPoolExecutor(max_workers=workers) as ex:
            futs = [ex.submit(_judge, client, p) for p in todo]
            for k, fut in enumerate(as_completed(futs), 1):
                r = fut.result()
                results.append(r)
                tin += r.get("tok_in", 0)
                tout += r.get("tok_out", 0)
                if k % 100 == 0 or k == len(todo):
                    print(f"[C5-adj]   judged {k}/{len(todo)}", flush=True)
        with open(VERDICTS, "w", encoding="utf-8") as f:
            for r in results:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
    yes = [r for r in results if r["cosine"] >= min_cos and r["same"]]
    merged = _merge_and_rewrite(yes)
    cost = (tin * 15 + tout * 75) / 1e6   # Opus sync (no batch discount)
    out = {"judged": len(todo), "cached_reused": len(cached), "yes": len(yes),
           "no": len(results) - len(yes), "tok_in": tin, "tok_out": tout,
           "cost_usd": round(cost, 2), **merged}
    print("[C5-adj] " + json.dumps(out), flush=True)
    return out


if __name__ == "__main__":
    import sys
    adjudicate(min_cos=float(sys.argv[1]) if len(sys.argv) > 1 else MIN_COS)
