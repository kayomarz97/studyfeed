"""C11 — asset generation orchestration (BUILD_PROMPT §C11 + §4 generators + §5d).

The GENERATION itself is done by the Claude Code `gen-*` subagents (CC-plan usage, NOT the paid API —
user directive for C11); this module is the deterministic scaffolding around them:

  prep(asset)     -> chunk scored inputs into work/gen_input/<asset>/NNNN.jsonl, IMPORTANCE-DESCENDING
                     (so a partial run keeps the highest-value items), + a resumable work/gen_manifest.json.
  [subagents]     -> each pending chunk: a gen-<asset> subagent reads its input chunk and writes
                     work/gen_output/<asset>/NNNN.jsonl. Highest-importance chunks first.
  validate(asset) -> deterministic grounding gate (ref resolves; answer non-empty + shares salient tokens
                     with the cited atom; MCQ has exactly one key + 4 distractors). Ungrounded -> quarantined.
  collect(asset)  -> merge validated chunk outputs into work/gen_<asset>.jsonl (feed_items built at C13).

Everything is idempotent/resumable (§1): re-running prep preserves done chunks; collect only takes
validated rows. Generated prose is NOT required to be byte-identical (only importance scores are, §repro);
we generate ONCE and store, and packaging upserts incrementally so rebuilds don't churn.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from .config import Config, WORK_DIR

GEN_IN = WORK_DIR / "gen_input"
GEN_OUT = WORK_DIR / "gen_output"
MANIFEST = WORK_DIR / "gen_manifest.json"
_WORD = re.compile(r"[a-z0-9]+")
_STOP = set("the a an of to in and or is are for with on at by as be from into this that these those "
            "may can will should must not no if it its their can cause causes caused due when after "
            "before which who whom whose than then also more most less least very such use used using "
            "patient patients present presents presenting associated common".split())


def _salient(text: str) -> set[str]:
    # keep real words (len>=4) AND short-but-critical alphanumerics/numbers (DR4, p53, B12, HLA-B27,
    # "70-80%") — dropping tokens<=3 chars would blank out a short-answer card's key term and false-reject
    # a perfectly grounded card (e.g. atom "...HLA antigen DR4" / card back "HLA-DR4.").
    out = set()
    for w in _WORD.findall((text or "").lower()):
        if w in _STOP:
            continue
        if len(w) >= 4 or any(c.isdigit() for c in w):
            out.add(w)
    return out


def _subject_of() -> dict[int, str]:
    m = {}
    for l in open(WORK_DIR / "classification.jsonl", encoding="utf-8"):
        r = json.loads(l)
        m[r["work_id"]] = (r.get("subject") or "").strip().lower()
    return m


def _read_manifest() -> dict:
    return json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}


def _write_manifest(m: dict) -> None:
    MANIFEST.write_text(json.dumps(m, ensure_ascii=False, indent=1))


def _chunk_and_manifest(asset: str, items: list[dict], chunk_size: int, params: dict) -> dict:
    """Write importance-descending input chunks; preserve any chunk already marked done (resumable)."""
    (GEN_IN / asset).mkdir(parents=True, exist_ok=True)
    (GEN_OUT / asset).mkdir(parents=True, exist_ok=True)
    man = _read_manifest()
    prev = {c["id"]: c for c in man.get(asset, {}).get("chunks", [])}
    chunks = []
    for i in range(0, len(items), chunk_size):
        cid = f"{i // chunk_size:04d}"
        p = GEN_IN / asset / f"{cid}.jsonl"
        with open(p, "w", encoding="utf-8") as f:
            for it in items[i:i + chunk_size]:
                f.write(json.dumps(it, ensure_ascii=False) + "\n")
        out = GEN_OUT / asset / f"{cid}.jsonl"
        done = prev.get(cid, {}).get("status") == "done" and out.exists()
        chunks.append({"id": cid, "in": str(p), "out": str(out), "n": len(items[i:i + chunk_size]),
                       "status": "done" if done else "pending"})
    man[asset] = {"params": params, "count": len(items), "chunk_size": chunk_size, "chunks": chunks}
    _write_manifest(man)
    return man[asset]


def _scored_atom_items(min_star: int, top_n: int | None = None) -> list[dict]:
    """Atoms with importance >= min_star, importance-DESCENDING (so partial runs keep the best);
    optionally truncated to the top N. Deterministic order (importance, score, ref)."""
    subj = _subject_of()
    atoms = [json.loads(l) for l in open(WORK_DIR / "atoms_merged.jsonl", encoding="utf-8")]
    imp = [json.loads(l) for l in open(WORK_DIR / "atom_importance.jsonl", encoding="utf-8")]
    items = []
    for i, (a, m) in enumerate(zip(atoms, imp)):
        if m["importance"] < min_star:
            continue
        items.append({"ref": i, "importance": m["importance"], "score": m["importance_score"],
                      "subject": subj.get(a["source_id"]) or None,
                      "statement": a.get("statement", ""), "detail": a.get("detail", ""),
                      "citations": a.get("citations", [])})
    items.sort(key=lambda x: (-x["importance"], -x["score"], x["ref"]))
    return items[:top_n] if top_n else items


def prep_flashcards(cfg: Config | None = None, min_star: int = 4, chunk_size: int = 200) -> dict:
    """One flashcard per atom with importance >= min_star (trimmed default 4★), highest-value first."""
    items = _scored_atom_items(min_star)
    info = _chunk_and_manifest("flashcards", items, chunk_size, {"min_star": min_star})
    print(f"[C11 prep flashcards] {info['count']} atoms -> {len(info['chunks'])} chunks "
          f"(chunk_size={chunk_size}, pending={sum(c['status']=='pending' for c in info['chunks'])})", flush=True)
    return info


def prep_reels(cfg: Config | None = None, min_star: int = 4, top_n: int = 3000, chunk_size: int = 250) -> dict:
    """Reels (one-hook fact cards) from the top-N highest-importance >=min_star atoms."""
    items = _scored_atom_items(min_star, top_n)
    info = _chunk_and_manifest("reels", items, chunk_size, {"min_star": min_star, "top_n": top_n})
    print(f"[C11 prep reels] {info['count']} atoms -> {len(info['chunks'])} chunks "
          f"(pending={sum(c['status']=='pending' for c in info['chunks'])})", flush=True)
    return info


def prep_mcqs(cfg: Config | None = None, min_star: int = 4, top_n: int = 2000, chunk_size: int = 100) -> dict:
    """Generated best-of-five MCQs from the top-N highest-importance >=min_star atoms (smaller chunks:
    MCQs are heavier per item)."""
    items = _scored_atom_items(min_star, top_n)
    info = _chunk_and_manifest("mcqs", items, chunk_size, {"min_star": min_star, "top_n": top_n})
    print(f"[C11 prep mcqs] {info['count']} atoms -> {len(info['chunks'])} chunks "
          f"(pending={sum(c['status']=='pending' for c in info['chunks'])})", flush=True)
    return info


_BULLET = re.compile(r"^- \*\*(.+?)\*\*", re.M)


def _eff_importance() -> dict[int, tuple[int, float]]:
    """Effective concept importance = the concept's DOSSIER importance where it has one (max-of-atoms),
    else its concept_importance. The concept-importance tail is known-noisy (C12) → this is the better
    signal for the substantial concepts blobs/interlinks cover. Returns {concept_id: (star, score)}."""
    eff = {}
    for l in open(WORK_DIR / "concept_importance.jsonl", encoding="utf-8"):
        r = json.loads(l)
        eff[r["id"]] = (r["importance"], r["importance_score"])
    for l in open(WORK_DIR / "dossiers.jsonl", encoding="utf-8"):
        d = json.loads(l)
        eff[d["concept_id"]] = (d.get("importance", 1), d.get("importance_score", 0))  # dossier wins
    return eff


def prep_blobs(cfg: Config | None = None, top_n: int = 3000, chunk_size: int = 150) -> dict:
    """Blobs from the top-N dossiers by importance; each carries the top ~12 grounded fact-bullets."""
    dossiers = [json.loads(l) for l in open(WORK_DIR / "dossiers.jsonl", encoding="utf-8")]
    dossiers.sort(key=lambda d: (-d.get("importance", 1), -d.get("importance_score", 0), d["concept_id"]))
    items = []
    for d in dossiers[:top_n]:
        facts = _BULLET.findall(d.get("body_md", ""))[:12]
        if not facts:
            continue
        items.append({"ref": d["concept_id"], "title": d.get("title"),
                      "subjects": d.get("subjects_covered", []), "importance": d.get("importance", 1),
                      "score": d.get("importance_score", 0), "facts": facts})
    info = _chunk_and_manifest("blobs", items, chunk_size, {"top_n": top_n})
    print(f"[C11 prep blobs] {info['count']} concepts -> {len(info['chunks'])} chunks "
          f"(pending={sum(c['status']=='pending' for c in info['chunks'])})", flush=True)
    return info


def prep_interlink(cfg: Config | None = None, top_n: int = 3000, chunk_size: int = 200) -> dict:
    """Interlink quizzes from the best concept_edges (exclude vague 'associated_with'); ranked by both-
    endpoints-substantial, corroboration, endpoint importance. ref = edge line index (→ interlink.edge_id)."""
    concepts = {c["id"]: c["name"] for c in (json.loads(l) for l in
                open(WORK_DIR / "concepts.jsonl", encoding="utf-8"))}
    eff = _eff_importance()
    seen, cand = set(), []
    for i, l in enumerate(open(WORK_DIR / "concept_edges.jsonl", encoding="utf-8")):
        e = json.loads(l)
        s, o, rel = e.get("src_concept_id"), e.get("dst_concept_id"), e.get("relation")
        if rel == "associated_with" or s not in concepts or o not in concepts:
            continue
        key = (s, rel, o)
        if key in seen:
            continue
        seen.add(key)
        si, oi = eff.get(s, (1, 0)), eff.get(o, (1, 0))
        both_dossier = int(s in eff and o in eff)
        imp = max(1, min(5, round((si[0] + oi[0]) / 2)))
        cand.append({"ref": i, "src": s, "dst": o, "relation": rel, "src_name": concepts[s],
                     "dst_name": concepts[o], "rationale": e.get("rationale", ""), "importance": imp,
                     "_rank": (both_dossier, e.get("distinct_source_count", 1), si[0] + oi[0])})
    cand.sort(key=lambda x: (-x["_rank"][0], -x["_rank"][1], -x["_rank"][2], x["ref"]))
    items = [{k: v for k, v in c.items() if k != "_rank"} for c in cand[:top_n]]
    info = _chunk_and_manifest("interlink", items, chunk_size, {"top_n": top_n})
    print(f"[C11 prep interlink] {info['count']} edges -> {len(info['chunks'])} chunks "
          f"(pending={sum(c['status']=='pending' for c in info['chunks'])})", flush=True)
    return info


def _input_map(asset: str, keys: tuple) -> dict:
    """ref -> selected input fields, loaded from the prepped gen_input chunks (for validation/importance)."""
    out = {}
    for c in _read_manifest().get(asset, {}).get("chunks", []):
        p = Path(c["in"])
        if not p.exists():
            continue
        for l in open(p, encoding="utf-8"):
            r = json.loads(l)
            out[r["ref"]] = {k: r.get(k) for k in keys}
    return out


def validate_flashcards() -> dict:
    """Deterministic grounding gate: ref resolves; front/back non-empty; back shares >=1 salient token
    with its atom (statement+detail). Ungrounded/malformed -> rejected + logged (evidence floor §5)."""
    atoms = [json.loads(l) for l in open(WORK_DIR / "atoms_merged.jsonl", encoding="utf-8")]
    imp = [json.loads(l) for l in open(WORK_DIR / "atom_importance.jsonl", encoding="utf-8")]
    man = _read_manifest().get("flashcards", {})
    kept, rej = [], {"missing_field": 0, "bad_ref": 0, "ungrounded": 0, "dup_ref": 0}
    seen = set()
    for c in man.get("chunks", []):
        out = Path(c["out"])
        if not out.exists():
            continue
        for l in open(out, encoding="utf-8"):
            l = l.strip()
            if not l:
                continue
            try:
                r = json.loads(l)
            except json.JSONDecodeError:
                rej["missing_field"] += 1
                continue
            ref, front, back = r.get("ref"), (r.get("front_md") or "").strip(), (r.get("back_md") or "").strip()
            if not isinstance(ref, int) or not (0 <= ref < len(atoms)):
                rej["bad_ref"] += 1
                continue
            if not front or not back:
                rej["missing_field"] += 1
                continue
            if ref in seen:
                rej["dup_ref"] += 1
                continue
            # ground-check the whole card (front+back+why): a Q/A card's grounding is in the pairing —
            # "Which HLA antigen…RA?" / "HLA-DR4." — not in the terse answer alone.
            atom_sal = _salient(atoms[ref].get("statement", "") + " " + atoms[ref].get("detail", ""))
            card_sal = _salient(front + " " + back + " " + (r.get("why_md") or ""))
            if atom_sal and not (card_sal & atom_sal):
                rej["ungrounded"] += 1
                continue
            seen.add(ref)
            kept.append({"ref": ref, "front_md": front, "back_md": back,
                         "why_md": (r.get("why_md") or "").strip(), "is_cloze": int(r.get("is_cloze", 0)),
                         "subject": None, "importance": imp[ref]["importance"],
                         "importance_score": imp[ref]["importance_score"],
                         "importance_signals": imp[ref].get("signals"),
                         "citations": atoms[ref].get("citations", [])})
    stats = {"kept": len(kept), "rejected": sum(rej.values()), "reject_breakdown": rej,
             "reject_rate_pct": round(100 * sum(rej.values()) / max(1, len(kept) + sum(rej.values())), 1)}
    print(f"[C11 validate flashcards] {json.dumps(stats)}", flush=True)
    return {"kept": kept, "stats": stats}


def collect_flashcards() -> dict:
    res = validate_flashcards()
    out = WORK_DIR / "gen_flashcards.jsonl"
    with open(out, "w", encoding="utf-8") as f:
        for r in res["kept"]:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"[C11 collect flashcards] wrote {len(res['kept'])} -> {out}", flush=True)
    return res["stats"]


def _iter_output(asset: str):
    """Yield parsed rows across all existing output chunks for an asset (skips blank/corrupt lines)."""
    for c in _read_manifest().get(asset, {}).get("chunks", []):
        out = Path(c["out"])
        if not out.exists():
            continue
        for l in open(out, encoding="utf-8"):
            l = l.strip()
            if not l:
                continue
            try:
                yield json.loads(l)
            except json.JSONDecodeError:
                yield {"__parse_error__": True}


def _atom_ctx():
    atoms = [json.loads(l) for l in open(WORK_DIR / "atoms_merged.jsonl", encoding="utf-8")]
    imp = [json.loads(l) for l in open(WORK_DIR / "atom_importance.jsonl", encoding="utf-8")]
    return atoms, imp


def validate_reels() -> dict:
    """Grounding gate for reels: ref resolves; hook non-empty; hook+detail share a salient token with atom."""
    atoms, imp = _atom_ctx()
    kept, rej, seen = [], {"parse": 0, "bad_ref": 0, "missing_field": 0, "ungrounded": 0, "dup_ref": 0}, set()
    for r in _iter_output("reels"):
        if r.get("__parse_error__"):
            rej["parse"] += 1; continue
        ref, hook, det = r.get("ref"), (r.get("hook_md") or "").strip(), (r.get("detail_md") or "").strip()
        if not isinstance(ref, int) or not (0 <= ref < len(atoms)):
            rej["bad_ref"] += 1; continue
        if not hook:
            rej["missing_field"] += 1; continue
        if ref in seen:
            rej["dup_ref"] += 1; continue
        asl = _salient(atoms[ref].get("statement", "") + " " + atoms[ref].get("detail", ""))
        if asl and not (_salient(hook + " " + det) & asl):
            rej["ungrounded"] += 1; continue
        seen.add(ref)
        kept.append({"ref": ref, "hook_md": hook, "detail_md": det, "subject": None,
                     "importance": imp[ref]["importance"], "importance_score": imp[ref]["importance_score"],
                     "importance_signals": imp[ref].get("signals"), "citations": atoms[ref].get("citations", [])})
    stats = {"kept": len(kept), "rejected": sum(rej.values()), "reject_breakdown": rej}
    print(f"[C11 validate reels] {json.dumps(stats)}", flush=True)
    return {"kept": kept, "stats": stats}


def validate_mcqs() -> dict:
    """Grounding gate for generated MCQs: ref resolves; stem+explanation non-empty; exactly 5 non-empty
    options; correct_idx in 0..4; explanation shares a salient token with the atom. origin=generated."""
    atoms, imp = _atom_ctx()
    kept, rej, seen = [], {"parse": 0, "bad_ref": 0, "missing_field": 0, "bad_options": 0,
                           "bad_key": 0, "ungrounded": 0, "dup_ref": 0}, set()
    for r in _iter_output("mcqs"):
        if r.get("__parse_error__"):
            rej["parse"] += 1; continue
        ref = r.get("ref")
        if not isinstance(ref, int) or not (0 <= ref < len(atoms)):
            rej["bad_ref"] += 1; continue
        stem, expl = (r.get("stem_md") or "").strip(), (r.get("explanation_md") or "").strip()
        opts = [(r.get(f"opt_{k}") or "").strip() for k in "abcde"]
        if not stem or not expl:
            rej["missing_field"] += 1; continue
        if any(not o for o in opts) or len(set(opts)) != 5:
            rej["bad_options"] += 1; continue
        ci = r.get("correct_idx")
        if not isinstance(ci, int) or not (0 <= ci <= 4):
            rej["bad_key"] += 1; continue
        if ref in seen:
            rej["dup_ref"] += 1; continue
        asl = _salient(atoms[ref].get("statement", "") + " " + atoms[ref].get("detail", ""))
        if asl and not (_salient(stem + " " + expl + " " + " ".join(opts)) & asl):
            rej["ungrounded"] += 1; continue
        seen.add(ref)
        dz = [d for d in (r.get("distractors") or []) if isinstance(d, dict)]
        kept.append({"ref": ref, "origin": "generated", "stem_md": stem,
                     "opt_a": opts[0], "opt_b": opts[1], "opt_c": opts[2], "opt_d": opts[3], "opt_e": opts[4],
                     "correct_idx": ci, "explanation_md": expl, "distractors": dz, "subject": None,
                     "importance": imp[ref]["importance"], "importance_score": imp[ref]["importance_score"],
                     "importance_signals": imp[ref].get("signals"), "citations": atoms[ref].get("citations", [])})
    stats = {"kept": len(kept), "rejected": sum(rej.values()), "reject_breakdown": rej}
    print(f"[C11 validate mcqs] {json.dumps(stats)}", flush=True)
    return {"kept": kept, "stats": stats}


def validate_blobs() -> dict:
    """Grounding gate for blobs: ref resolves; body 40-220 words + shares a salient token with its source
    facts; quiz has 4 distinct non-empty options + correct_idx in 0..3 + non-empty answer."""
    src = _input_map("blobs", ("facts", "title", "subjects", "importance", "score"))
    kept, rej, seen = [], {"parse": 0, "bad_ref": 0, "missing_field": 0, "bad_quiz": 0,
                           "ungrounded": 0, "dup_ref": 0}, set()
    for r in _iter_output("blobs"):
        if r.get("__parse_error__"):
            rej["parse"] += 1; continue
        ref, body = r.get("ref"), (r.get("body_md") or "").strip()
        meta = src.get(ref)
        if meta is None:
            rej["bad_ref"] += 1; continue
        if not body or not (40 <= len(body.split()) <= 220):
            rej["missing_field"] += 1; continue
        q = r.get("quiz") or {}
        opts = [(q.get(f"opt_{k}") or "").strip() for k in "abcd"]
        ci = q.get("correct_idx")
        if (not (q.get("question_md") or "").strip() or not (q.get("answer_md") or "").strip()
                or any(not o for o in opts) or len(set(opts)) != 4 or not isinstance(ci, int)
                or not (0 <= ci <= 3)):
            rej["bad_quiz"] += 1; continue
        if ref in seen:
            rej["dup_ref"] += 1; continue
        fact_sal = _salient(" ".join(meta.get("facts") or []))
        if fact_sal and not (_salient(body) & fact_sal):
            rej["ungrounded"] += 1; continue
        seen.add(ref)
        words = len(body.split())
        kept.append({"ref": ref, "title": r.get("title") or meta.get("title"), "body_md": body,
                     "est_read_seconds": max(15, round(words / 3.3)), "concept_ids": [ref],
                     "quiz": {"question_md": q["question_md"].strip(), "answer_md": q["answer_md"].strip(),
                              "opt_a": opts[0], "opt_b": opts[1], "opt_c": opts[2], "opt_d": opts[3],
                              "correct_idx": ci},
                     "importance": meta.get("importance", 1), "importance_score": meta.get("score", 0)})
    stats = {"kept": len(kept), "rejected": sum(rej.values()), "reject_breakdown": rej}
    print(f"[C11 validate blobs] {json.dumps(stats)}", flush=True)
    return {"kept": kept, "stats": stats}


def validate_interlink() -> dict:
    """Grounding gate for interlink: ref resolves; question+answer non-empty and together mention BOTH
    endpoint concept names (the edge it must test)."""
    src = _input_map("interlink", ("src_name", "dst_name", "importance"))
    kept, rej, seen = [], {"parse": 0, "bad_ref": 0, "missing_field": 0, "ungrounded": 0, "dup_ref": 0}, set()
    for r in _iter_output("interlink"):
        if r.get("__parse_error__"):
            rej["parse"] += 1; continue
        ref = r.get("ref")
        meta = src.get(ref)
        if meta is None:
            rej["bad_ref"] += 1; continue
        qm, am = (r.get("question_md") or "").strip(), (r.get("answer_md") or "").strip()
        if not qm or not am:
            rej["missing_field"] += 1; continue
        if ref in seen:
            rej["dup_ref"] += 1; continue
        blob = (qm + " " + am).lower()
        a, b = (meta.get("src_name") or "").lower(), (meta.get("dst_name") or "").lower()
        if not (a in blob and b in blob):        # must test the actual A—B link
            rej["ungrounded"] += 1; continue
        seen.add(ref)
        kept.append({"ref": ref, "question_md": qm, "answer_md": am,
                     "importance": meta.get("importance", 1)})
    stats = {"kept": len(kept), "rejected": sum(rej.values()), "reject_breakdown": rej}
    print(f"[C11 validate interlink] {json.dumps(stats)}", flush=True)
    return {"kept": kept, "stats": stats}


def collect(asset: str) -> dict:
    """Validate + write work/gen_<asset>.jsonl for reels/mcqs/blobs/interlink (flashcards: collect_flashcards)."""
    res = {"reels": validate_reels, "mcqs": validate_mcqs, "blobs": validate_blobs,
           "interlink": validate_interlink}[asset]()
    out = WORK_DIR / f"gen_{asset}.jsonl"
    with open(out, "w", encoding="utf-8") as f:
        for r in res["kept"]:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"[C11 collect {asset}] wrote {len(res['kept'])} -> {out}", flush=True)
    return res["stats"]


def pending(asset: str) -> list[dict]:
    return [c for c in _read_manifest().get(asset, {}).get("chunks", []) if c["status"] != "done"]


def mark_done(asset: str, chunk_id: str) -> None:
    man = _read_manifest()
    for c in man.get(asset, {}).get("chunks", []):
        if c["id"] == chunk_id:
            c["status"] = "done"
    _write_manifest(man)


if __name__ == "__main__":
    import sys
    cfg = Config.for_exam("mrcp_part1")
    cmd = sys.argv[1] if len(sys.argv) > 1 else "prep"
    asset = sys.argv[2] if len(sys.argv) > 2 else "flashcards"
    _PREP = {"flashcards": prep_flashcards, "reels": prep_reels, "mcqs": prep_mcqs,
             "blobs": prep_blobs, "interlink": prep_interlink}
    _COLLECT = {"flashcards": collect_flashcards, "reels": lambda: collect("reels"),
                "mcqs": lambda: collect("mcqs"), "blobs": lambda: collect("blobs"),
                "interlink": lambda: collect("interlink")}
    _VALIDATE = {"flashcards": validate_flashcards, "reels": validate_reels, "mcqs": validate_mcqs,
                 "blobs": validate_blobs, "interlink": validate_interlink}
    if cmd == "prep" and asset in _PREP:
        _PREP[asset](cfg)
    elif cmd == "prep-all":
        for a in ("blobs", "interlink"):
            _PREP[a](cfg)
    elif cmd == "validate" and asset in _VALIDATE:
        _VALIDATE[asset]()
    elif cmd == "collect" and asset in _COLLECT:
        _COLLECT[asset]()
    elif cmd == "pending":
        print(json.dumps(pending(asset), indent=1))
    else:
        print("usage: generate.py [prep <asset>|prep-all|validate <asset>|collect <asset>|pending <asset>]"
              "  assets: flashcards reels mcqs")
