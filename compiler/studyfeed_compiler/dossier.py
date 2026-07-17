"""C10 — pre-compiled topic dossiers (BUILD_PROMPT §4 dossier-compiler + §C10 + §5d).

Per canonical concept, gather ALL constituent atoms across ALL subjects (alias-resolved) into ONE long
grounded `body_md`, sectioned by subject, plus its concept-map connections and the questions that touch
it. Assembled **deterministically from the atoms** — no LLM freewriting, so nothing is fabricated (every
bullet cites a page) and a rebuild on unchanged inputs is byte-identical. Importance = MAX of the
constituent atoms (§5d: a dossier is as important as its most important fact).

The atom↔concept link is the same one `importance.py` uses: `graph_edges_raw.rationale` = the atom's
`statement[:240]`; `alias.key(s|o)` → `canonical_key` → concept id. `atom_importance.jsonl` is
line-aligned with `atoms_merged.jsonl` (C9 scored atoms in file order), so atom i ↔ importance i.

Scope (profile.dossier): a concept earns a page when it has >= `min_atoms` constituent atoms OR spans
>= `min_subjects` distinct subjects — a 1-atom concept is an atom, not a dossier. Excluded count is
logged (no silent cap). Subject comes from `classification.subject`, which only textbook/pearls carry;
past-paper/qbank atoms are subject-less and land in a trailing "Question-bank & exam notes" section.

Deferred: `figure_ids`/`related_flashcard_ids` are [] here — those assets don't exist until C11; they
are backfilled at C11/C13 packaging.

Output: work/dossiers.jsonl — one row per page (page fields + nested `sections`), which C13 splits into
`topic_pages` + `topic_sections`.
"""
from __future__ import annotations

import hashlib
import json
import re
from collections import Counter, defaultdict

from . import alias as A
from .config import Config, WORK_DIR

OUT = WORK_DIR / "dossiers.jsonl"
_WORD = re.compile(r"[a-z0-9']+")
_EXT = re.compile(r"\.(pdf|zip|rar|docx?|txt|jsonl?)$", re.I)
_LEAD_JUNK = re.compile(r"^[^A-Za-z]+")          # leading numbering / stray "." / punctuation


def _clean_label(name: str) -> str:
    s = _EXT.sub("", name or "").replace("_", " ")
    s = _LEAD_JUNK.sub("", s)
    return re.sub(r"\s+", " ", s).strip()


def _source_labels() -> dict[int, str]:
    """source_id -> a short human citation label (grounding is by page; this is just display)."""
    lab = {}
    for l in open(WORK_DIR / "classification.jsonl", encoding="utf-8"):
        r = json.loads(l)
        wid = r["work_id"]
        if r.get("kind") == "past_paper" and r.get("year"):
            lab[wid] = f"MRCP Part 1 {r['year']}"
            continue
        lab[wid] = _clean_label(r.get("name") or "") or f"work {wid}"
    return lab


def _cite(citations: list[dict], labels: dict[int, str]) -> str:
    """Compact `[Label p.N; Label2 p.M]` from an atom's UNIONed citations (first 2, then +N)."""
    seen, parts = set(), []
    for c in citations or []:
        key = (c.get("source_id"), str(c.get("page_ref")))
        if key in seen:
            continue
        seen.add(key)
        parts.append(f"{labels.get(c.get('source_id'), 'src')} p.{c.get('page_ref')}")
    if not parts:
        return ""
    shown = "; ".join(parts[:2])
    extra = f"; +{len(parts) - 2}" if len(parts) > 2 else ""
    return f"`[{shown}{extra}]`"


def _atom_bullet(atom: dict, labels: dict[int, str]) -> str:
    stmt = (atom.get("statement") or "").strip()
    detail = (atom.get("detail") or "").strip()
    line = f"- **{stmt}**"
    if detail and detail.lower() not in stmt.lower():
        line += f" — {detail}"
    cite = _cite(atom.get("citations"), labels)
    return f"{line} {cite}".rstrip()


def _build_links() -> dict[str, set[int]]:
    """rationale (= atom statement[:240]) -> {concept ids it mentions}. Uses the alias resolver so
    every spelling of an entity folds to its one canonical node (§5e)."""
    al = A.Aliases.from_profile()
    concepts = [json.loads(l) for l in open(WORK_DIR / "concepts.jsonl", encoding="utf-8")]
    key2id = {c["canonical_key"]: c["id"] for c in concepts}
    stmt2cids: dict[str, set[int]] = defaultdict(set)
    for l in open(WORK_DIR / "graph_edges_raw.jsonl", encoding="utf-8"):
        e = json.loads(l)
        rat = e.get("rationale", "")
        for side in ("s", "o"):
            cid = key2id.get(al.key(e[side]))
            if cid is not None:
                stmt2cids[rat].add(cid)
    return stmt2cids


def build(cfg: Config | None = None) -> dict:
    cfg = cfg or Config.for_exam("mrcp_part1")
    dcfg = cfg.dossier
    min_atoms = int(dcfg.get("min_atoms", 3))
    min_subjects = int(dcfg.get("min_subjects", 2))
    max_conn = int(dcfg.get("max_connections", 20))
    max_mcqs = int(dcfg.get("max_related_mcqs", 30))
    min_name_len = int(dcfg.get("min_name_len", 4))

    # profile subject metadata: lower-name -> (display, blueprint weight) for section ordering
    subj_meta = {(s.get("name") or "").strip().lower(): (s.get("name"), float(s.get("weight", 0)))
                 for s in cfg.subjects}
    subj_of: dict[int, str] = {}
    for l in open(WORK_DIR / "classification.jsonl", encoding="utf-8"):
        r = json.loads(l)
        subj_of[r["work_id"]] = (r.get("subject") or "").strip().lower()
    labels = _source_labels()

    concepts = [json.loads(l) for l in open(WORK_DIR / "concepts.jsonl", encoding="utf-8")]
    id2concept = {c["id"]: c for c in concepts}
    stmt2cids = _build_links()

    # gather constituent atoms per concept (atoms_merged line-aligned with atom_importance)
    atoms = [json.loads(l) for l in open(WORK_DIR / "atoms_merged.jsonl", encoding="utf-8")]
    aimp = [json.loads(l) for l in open(WORK_DIR / "atom_importance.jsonl", encoding="utf-8")]
    from . import pack
    excluded = pack.excluded_atom_indices(cfg)                   # verified-wrong atoms (data_corrections) — no bullet
    cid_atoms: dict[int, list[int]] = defaultdict(list)          # concept id -> [atom index]
    for i, a in enumerate(atoms):
        if i in excluded:
            continue
        for cid in stmt2cids.get((a.get("statement", "") or "")[:240], ()):
            cid_atoms[cid].append(i)

    # which concepts qualify for a page (measured scope; excluded count logged, no silent cap)
    def subjects_of(idxs: list[int]) -> set[str]:
        return {s for i in idxs if (s := subj_of.get(atoms[i]["source_id"]))}

    qualified = [c for c in concepts
                 if len(cid_atoms.get(c["id"], ())) >= min_atoms
                 or len(subjects_of(cid_atoms.get(c["id"], []))) >= min_subjects]
    qual_ids = {c["id"] for c in qualified}

    # concept-map connections: only for concepts that get a page (both endpoints) — keyed both directions
    out_edges: dict[int, list[dict]] = defaultdict(list)
    in_edges: dict[int, list[dict]] = defaultdict(list)
    for l in open(WORK_DIR / "concept_edges.jsonl", encoding="utf-8"):
        e = json.loads(l)
        src, dst = e.get("src_concept_id"), e.get("dst_concept_id")
        if src in qual_ids:
            out_edges[src].append(e)
        if dst in qual_ids:
            in_edges[dst].append(e)

    # related MCQs: phrase-match concept name/aliases in stem+explanation. Token prefilter keeps this
    # fast and precise: single-word names match a whole word; multi-word names need their first word
    # present before the (rarer) substring test.
    mcq_imp = [json.loads(l)["importance"] for l in open(WORK_DIR / "mcq_importance.jsonl", encoding="utf-8")]
    mcq_txt, mcq_tok = [], []
    for l in open(WORK_DIR / "mcqs.jsonl", encoding="utf-8"):
        m = json.loads(l)
        t = (m.get("stem", "") + " " + m.get("explanation", "")).lower()
        mcq_txt.append(t)
        mcq_tok.append(set(_WORD.findall(t)))
    # phrase -> concept ids (only qualified concepts)
    phrase2cids: dict[str, set[int]] = defaultdict(set)
    for c in qualified:
        names = {c["name"]} | set(c.get("aliases") or [])
        for nm in names:
            p = (nm or "").strip().lower()
            if len(p) >= min_name_len:
                phrase2cids[p].add(c["id"])
    single = {p: cids for p, cids in phrase2cids.items() if " " not in p}
    multi = [(p, p.split()[0], cids) for p, cids in phrase2cids.items() if " " in p]
    cid_mcqs: dict[int, list[int]] = defaultdict(list)
    for mi, (txt, toks) in enumerate(zip(mcq_txt, mcq_tok)):
        for tok in toks:
            for cid in single.get(tok, ()):
                cid_mcqs[cid].append(mi)
        for p, first, cids in multi:
            if first in toks and p in txt:
                for cid in cids:
                    cid_mcqs[cid].append(mi)

    rows, hist = [], Counter()
    atoms_per, subj_per = [], []
    for c in qualified:
        cid = c["id"]
        idxs = cid_atoms.get(cid, [])
        # section by subject; subject-less atoms → trailing catch-all
        by_subj: dict[str, list[int]] = defaultdict(list)
        for i in idxs:
            by_subj[subj_of.get(atoms[i]["source_id"], "")].append(i)
        known = [s for s in by_subj if s]
        # order: blueprint weight desc, then name; catch-all last
        known.sort(key=lambda s: (-subj_meta.get(s, (s, 0.0))[1], s))
        ordered = known + ([""] if "" in by_subj else [])

        max_imp, max_score, max_sig, max_ref = 1, 0.0, None, None
        sections, body_parts = [], []
        for ord_i, s in enumerate(ordered):
            sec_idxs = sorted(by_subj[s], key=lambda i: (-aimp[i]["importance"], atoms[i].get("statement", "")))
            heading = subj_meta.get(s, (s, 0.0))[0] if s else "Question-bank & exam notes"
            bullets, atom_refs = [], []
            for i in sec_idxs:
                bullets.append(_atom_bullet(atoms[i], labels))
                a = atoms[i]
                atom_refs.append({"source_id": a["source_id"], "page_ref": a.get("page_ref"),
                                  "importance": aimp[i]["importance"]})
                if aimp[i]["importance"] > max_imp or (aimp[i]["importance"] == max_imp
                                                       and aimp[i]["importance_score"] > max_score):
                    max_imp, max_score = aimp[i]["importance"], aimp[i]["importance_score"]
                    max_sig, max_ref = aimp[i].get("signals"), a.get("statement", "")[:60]
            sec_md = f"## {heading}\n" + "\n".join(bullets)
            sections.append({"subject": s or None, "heading": heading, "ord": ord_i, "body_md": sec_md,
                             "atom_refs": atom_refs})
            body_parts.append(sec_md)

        # concept-map connections (dedup, order by source support)
        conns = []
        for e in out_edges.get(cid, []):
            other = id2concept.get(e["dst_concept_id"])
            if other:
                conns.append((e.get("distinct_source_count", 1), e["relation"], "out", other["name"]))
        for e in in_edges.get(cid, []):
            other = id2concept.get(e["src_concept_id"])
            if other:
                conns.append((e.get("distinct_source_count", 1), e["relation"], "in", other["name"]))
        # total ordering (incl. direction x[2]) — a non-total key would leave ties to fall back on
        # set-iteration order, which is hash-seed-dependent → non-reproducible pages (§ reproducible).
        conns = sorted(set(conns), key=lambda x: (-x[0], x[1], x[3], x[2]))[:max_conn]
        conn_md, connections = [], []
        for n_src, rel, direction, other in conns:
            rl = rel.replace("_", " ")
            line = (f"- **{c['name']}** {rl} *{other}*" if direction == "out"
                    else f"- *{other}* {rl} **{c['name']}**")
            conn_md.append(f"{line}  · {n_src} source(s)")
            connections.append({"relation": rel, "dir": direction, "other": other, "sources": n_src})
        if conn_md:
            body_parts.append("## Concept map connections\n" + "\n".join(conn_md))

        # related MCQs (top by importance)
        rel_mcqs = sorted(set(cid_mcqs.get(cid, [])), key=lambda mi: (-mcq_imp[mi], mi))[:max_mcqs]

        n_atoms, n_subj = len(idxs), len(known)
        atoms_per.append(n_atoms)
        subj_per.append(n_subj)
        gflag = " · guideline-sensitive" if c.get("guideline_sensitive") else ""
        header = (f"# {c['name']}\n*{n_atoms} fact{'s' * (n_atoms != 1)} across "
                  f"{n_subj} subject{'s' * (n_subj != 1)} · importance {max_imp}★{gflag}*")
        body_md = header + "\n\n" + "\n\n".join(body_parts)
        stable_key = hashlib.sha256(("topic|" + c["canonical_key"]).encode("utf-8")).hexdigest()[:16]

        hist[max_imp] += 1
        rows.append({
            "concept_id": cid, "title": c["name"], "canonical_key": c["canonical_key"],
            "stable_key": stable_key, "aliases": c.get("aliases") or [],
            "subjects_covered": [subj_meta.get(s, (s, 0))[0] for s in known],
            "importance": max_imp, "importance_score": round(max_score, 4),
            "importance_signals": {"rule": "max_of_atoms", "from_atom": max_ref, "signals": max_sig},
            "n_atoms": n_atoms, "guideline_sensitive": c.get("guideline_sensitive", 0),
            "figure_ids": [], "related_flashcard_ids": [],       # deferred to C11/C13
            "related_mcq_idxs": rel_mcqs, "connections": connections,
            "body_md": body_md, "sections": sections,
        })

    if OUT.exists():
        OUT.unlink()
    with open(OUT, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    n = len(rows)
    stats = {"dossiers": n, "concepts_total": len(concepts), "excluded": len(concepts) - n,
             "importance_hist": {b: hist[b] for b in range(1, 6)},
             "avg_atoms": round(sum(atoms_per) / max(1, n), 1),
             "max_atoms": max(atoms_per, default=0),
             "cross_subject": sum(1 for x in subj_per if x >= min_subjects),
             "with_related_mcqs": sum(1 for r in rows if r["related_mcq_idxs"]),
             "with_connections": sum(1 for r in rows if r["connections"]),
             "thresholds": {"min_atoms": min_atoms, "min_subjects": min_subjects}}
    print("[C10] " + json.dumps(stats, ensure_ascii=False), flush=True)
    return stats


def sample(name: str, cfg: Config | None = None) -> None:
    """Print one dossier's body_md by concept name/canonical_key (verification helper)."""
    cfg = cfg or Config.for_exam("mrcp_part1")
    al = A.Aliases.from_profile()
    want = al.key(name)
    if not OUT.exists():
        build(cfg)
    for l in open(OUT, encoding="utf-8"):
        r = json.loads(l)
        if r["canonical_key"] == want or r["title"].lower() == name.lower():
            print(r["body_md"])
            print(f"\n--- related_mcqs={len(r['related_mcq_idxs'])} connections={len(r['connections'])} "
                  f"stable_key={r['stable_key']} subjects={r['subjects_covered']}")
            return
    print(f"[sample] no dossier for {name!r} (key={want!r}) — below scope or unknown")


if __name__ == "__main__":
    import sys
    cfg = Config.for_exam("mrcp_part1")
    if len(sys.argv) > 1 and sys.argv[1] == "sample":
        sample(sys.argv[2], cfg)
    else:
        build(cfg)
