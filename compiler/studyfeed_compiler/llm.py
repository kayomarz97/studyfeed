"""Bulk LLM ingest via the Message Batches API (BUILD_PROMPT.md §10d).

Two routes, both grounded (source_id + page_ref carried from the extracted JSONL):
  - ATOMIZE prose (pearls + textbook) -> atomic clinical facts        [Haiku]
  - PARSE MCQs (past_paper + qbank)   -> structured MCQs with keys     [Sonnet]

Transport rules the spec insists on: reconcile by custom_id (order NOT guaranteed); persist every
batch id to .compiler_state.json the moment it is submitted (before polling); prompt-cache the system
prompt (identical across thousands of requests); design for per-request partial failure. Pages never
enter THIS process's model context — we only read the extracted text off disk and hand it to the API.
"""

from __future__ import annotations

import json
import os
import re
import time
from pathlib import Path
from typing import Any, Iterator

from . import state
from .config import COMPILER_DIR, WORK_DIR

EXTRACT_DIR = WORK_DIR / "extracted"
MODEL_ATOMIZE = "claude-haiku-4-5-20251001"
MODEL_MCQ = "claude-sonnet-5"
MAX_BATCH_REQUESTS = 20000  # well under the 100k / 256MB API cap; keeps batches manageable

ATOMIZE_SYS = (
    "You extract atomic clinical facts from MRCP(UK) exam-revision text for a study app. "
    "Output ONLY a compact JSON array; each element {\"s\":\"<one self-contained factual statement>\","
    "\"d\":\"<optional one-sentence detail, else empty>\"}. One fact per element; split compound "
    "sentences. Include ONLY facts explicitly in the text (no outside knowledge, no inference). Ignore "
    "navigation, UI, timestamps, page numbers, URLs, image/video links, headers. If the text has no "
    "clinical facts (menu/index/blank), output []."
)
MCQ_SYS = (
    "You parse ONE MRCP(UK) best-of-five MCQ from OCR'd revision text (Pastest/PassMedicine "
    "screenshots). Return ONE JSON object, or the literal null if there is no question. Object: "
    "{\"stem\":\"<vignette + lead-in>\",\"options\":{\"A\":\"..\",\"B\":\"..\",\"C\":\"..\",\"D\":\"..\","
    "\"E\":\"..\"},\"answer\":\"<A-E or null if not shown>\",\"explanation\":\"<if shown, else empty>\"}. "
    "Transcribe faithfully; fix obvious OCR errors in medical terms only when unambiguous. STRIP all UI "
    "chrome: 'Question N of M', Pastest, PassMedicine, LOG OUT, Previous/Next Question, Save and Leave, "
    "Peer Responses, percentages/bar-chart numbers, the Q1 Q2… navigator, timestamps, URLs. Include only "
    "the options actually present. Output null if the page has no question (stats/index/blank)."
)

_CHROME = re.compile(
    r"^\s*(\d{1,2}/\d{1,2}/\d{4}.*|https?://\S+|.*passmedicine\.com.*|LOG OUT|Previous Question|"
    r"Save and Leave|Next Question|Peer Responses.*|Total number of patients.*|Q\d+\s*)\s*$",
    re.I | re.M,
)


def load_env() -> None:
    envf = COMPILER_DIR / ".env"
    if envf.exists():
        for line in envf.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ[k.strip()] = v.strip()  # .env overrides a stale shell value


def client():
    load_env()
    import anthropic
    return anthropic.Anthropic()


# ── request building ──────────────────────────────────────────────────────────────────────────
def _clean(text: str) -> str:
    return _CHROME.sub("", text).strip()


def _req(custom_id: str, model: str, sys: str, user: str, max_tokens: int) -> dict:
    return {
        "custom_id": custom_id,
        "params": {
            "model": model, "max_tokens": max_tokens,
            "system": [{"type": "text", "text": sys, "cache_control": {"type": "ephemeral"}}],
            "messages": [{"role": "user", "content": user}],
        },
    }


def _kinds() -> dict[int, str]:
    return {json.loads(l)["work_id"]: json.loads(l)["kind"]
            for l in open(WORK_DIR / "classification.jsonl", encoding="utf-8")}


def _pages(kinds: dict[int, str], want: set[str]) -> Iterator[tuple[int, int, str]]:
    for fp in sorted(EXTRACT_DIR.glob("*.jsonl")):
        wid = int(fp.name[:3])
        if kinds.get(wid) not in want:
            continue
        for rec in (json.loads(l) for l in open(fp, encoding="utf-8")):
            yield wid, rec["page"], rec["text"]


def build_atomize(kinds: dict[int, str], limit: int | None = None) -> list[dict]:
    reqs = []
    for wid, page, text in _pages(kinds, {"pearls", "textbook"}):
        body = _clean(text)
        if len(body) < 200:  # skip sparse nav/index pages
            continue
        reqs.append(_req(f"atom:{wid}:{page}", MODEL_ATOMIZE, ATOMIZE_SYS, body[:12000], 1200))
        if limit and len(reqs) >= limit:
            break
    return reqs


def build_mcq(kinds: dict[int, str], limit: int | None = None) -> list[dict]:
    reqs = []
    for wid, page, text in _pages(kinds, {"past_paper", "qbank"}):
        if not re.search(r"correct answer|\bexplanation\b", text, re.I):  # answer-bearing pages only
            continue
        body = _clean(text)
        if len(body) < 120:
            continue
        reqs.append(_req(f"mcq:{wid}:{page}", MODEL_MCQ, MCQ_SYS, body[:12000], 1200))
        if limit and len(reqs) >= limit:
            break
    return reqs


# ── transport ─────────────────────────────────────────────────────────────────────────────────
def submit(requests: list[dict], kind: str, stage: str) -> str:
    c = client()
    batch = c.messages.batches.create(requests=requests)
    state.add_batch({"id": batch.id, "kind": kind, "stage": stage, "n": len(requests),
                     "status": batch.processing_status, "submitted": state._utcnow()})
    return batch.id


def poll(batch_id: str):
    b = client().messages.batches.retrieve(batch_id)
    return b.processing_status, b.request_counts


def iter_results(batch_id: str) -> Iterator[tuple[str, bool, Any, Any]]:
    """Yield (custom_id, ok, text_or_error, usage). Reconcile by custom_id — order is not guaranteed."""
    for r in client().messages.batches.results(batch_id):
        if r.result.type == "succeeded":
            m = r.result.message
            yield r.custom_id, True, m.content[0].text, m.usage
        else:
            yield r.custom_id, False, r.result.type, None


def submit_chunked(requests: list[dict], kind: str, stage: str) -> list[str]:
    ids = []
    for i in range(0, len(requests), MAX_BATCH_REQUESTS):
        ids.append(submit(requests[i:i + MAX_BATCH_REQUESTS], kind, stage))
    return ids


# ── result processing (reconcile by custom_id -> grounded rows on disk) ──────────────────────────
def _parse_atoms(cid: str, text: str) -> list[dict]:
    _, wid, page = cid.split(":")
    try:
        arr = json.loads(text[text.index("["):text.rindex("]") + 1])
    except Exception:
        return []
    out = []
    for a in arr:
        s = (a.get("s") or "").strip()
        if s:
            out.append({"source_id": int(wid), "page_ref": page, "statement": s,
                        "detail": (a.get("d") or "").strip()})
    return out


def _parse_mcq(cid: str, text: str) -> dict | None:
    _, wid, page = cid.split(":")
    t = text.strip()
    if t.lower().startswith("null") or t == "{}":
        return None
    try:
        obj = json.loads(t[t.index("{"):t.rindex("}") + 1])
    except Exception:
        return None
    if not obj.get("stem"):
        return None
    opts = obj.get("options") or {}
    return {"source_id": int(wid), "page_ref": page, "stem": obj["stem"],
            "opt_a": opts.get("A", ""), "opt_b": opts.get("B", ""), "opt_c": opts.get("C", ""),
            "opt_d": opts.get("D", ""), "opt_e": opts.get("E", ""),
            "answer": obj.get("answer"), "explanation": obj.get("explanation", "")}


def process(batch_id: str, kind: str, out_path: Path) -> dict:
    n_ok = n_err = tok_in = tok_out = 0
    rows = 0
    with open(out_path, "a", encoding="utf-8") as f:
        for cid, ok, payload, usage in iter_results(batch_id):
            if not ok:
                n_err += 1
                continue
            n_ok += 1
            tok_in += usage.input_tokens
            tok_out += usage.output_tokens
            items = _parse_atoms(cid, payload) if kind == "atomize" else (
                [m] if (m := _parse_mcq(cid, payload)) else [])
            for it in items:
                f.write(json.dumps(it, ensure_ascii=False) + "\n")
                rows += 1
    return {"ok": n_ok, "err": n_err, "rows": rows, "tok_in": tok_in, "tok_out": tok_out}


# ── CLI ─────────────────────────────────────────────────────────────────────────────────────
_RATES = {MODEL_ATOMIZE: (1.0, 5.0), MODEL_MCQ: (3.0, 15.0)}  # ~USD/1M tok (ESTIMATE); batch = 0.5x


def _cost(model: str, tin: float, tout: float) -> float:
    ri, ro = _RATES[model]
    return (tin / 1e6 * ri + tout / 1e6 * ro) * 0.5


def _run_test() -> bool:
    kinds = _kinds()
    a, m = build_atomize(kinds, 20), build_mcq(kinds, 20)
    reqs = a + m
    print(f"TEST: {len(a)} atomize + {len(m)} mcq = {len(reqs)} requests", flush=True)
    bid = submit(reqs, "test", "C4-test")
    print("batch:", bid, "(recorded to state)", flush=True)
    while True:
        st, counts = poll(bid)
        print("  poll:", st, counts, flush=True)
        if st == "ended":
            break
        time.sleep(20)
    atoms = mcqs = errs = ta_i = ta_o = tm_i = tm_o = 0
    sa = sm = None
    for cid, ok, payload, usage in iter_results(bid):
        if not ok:
            errs += 1
            continue
        if cid.startswith("atom:"):
            ta_i += usage.input_tokens; ta_o += usage.output_tokens
            items = _parse_atoms(cid, payload); atoms += len(items); sa = sa or (items[0] if items else None)
        else:
            tm_i += usage.input_tokens; tm_o += usage.output_tokens
            mm = _parse_mcq(cid, payload)
            if mm:
                mcqs += 1; sm = sm or mm
    full_a, full_m = len(build_atomize(kinds)), len(build_mcq(kinds))
    ca = _cost(MODEL_ATOMIZE, ta_i / max(1, len(a)) * full_a, ta_o / max(1, len(a)) * full_a)
    cm = _cost(MODEL_MCQ, tm_i / max(1, len(m)) * full_m, tm_o / max(1, len(m)) * full_m)
    print(f"\nTEST DONE. errors={errs} | atoms={atoms} (from {len(a)} pages) | mcqs={mcqs} (from {len(m)} pages)", flush=True)
    print(f"  tokens: atomize {ta_i}in/{ta_o}out  mcq {tm_i}in/{tm_o}out", flush=True)
    print(f"  FULL counts: {full_a} atomize + {full_m} mcq requests", flush=True)
    print(f"  EXTRAPOLATED full cost (est rates, batch 0.5x): atomize ${ca:.2f} + mcq ${cm:.2f} = ${ca + cm:.2f}", flush=True)
    print(f"  SAMPLE atom: {sa}", flush=True)
    print(f"  SAMPLE mcq: stem={(sm or {}).get('stem', '')[:160]!r} answer={(sm or {}).get('answer')}", flush=True)
    ok = errs == 0 and atoms > 0 and mcqs > 0
    print(f"\nTEST {'PASS' if ok else 'FAIL'}", flush=True)
    state.update_state(cursors={**state.read_state().get("cursors", {}),
                                "test_full_atomize_reqs": full_a, "test_full_mcq_reqs": full_m,
                                "test_est_cost_usd": round(ca + cm, 2)})
    return ok


if __name__ == "__main__":
    import sys

    mode = sys.argv[1] if len(sys.argv) > 1 else "test"
    if mode == "test":
        _run_test()
    elif mode == "submit":
        k = _kinds()
        a, m = build_atomize(k), build_mcq(k)
        print(f"submitting {len(a)} atomize + {len(m)} mcq requests", flush=True)
        print("atomize batches:", submit_chunked(a, "atomize", "C4"), flush=True)
        print("mcq batches:", submit_chunked(m, "mcq", "C4"), flush=True)
    elif mode == "status":
        for b in state.read_state().get("batches", []):
            try:
                st, c = poll(b["id"]); print(b["id"], b["kind"], st, c, flush=True)
            except Exception as e:
                print(b["id"], "ERR", e, flush=True)
    elif mode == "harvest":
        for b in state.read_state().get("batches", []):
            if b["kind"] == "test":
                continue
            out = WORK_DIR / ("atoms.jsonl" if b["kind"] == "atomize" else "mcqs.jsonl")
            print(b["id"], b["kind"], process(b["id"], b["kind"], out), flush=True)
