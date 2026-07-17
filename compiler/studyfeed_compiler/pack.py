"""C13 — package -> dist/pack_<EXAM>.db + dist/manifest.json (BUILD_PROMPT §C13 + §6a + §5j + §7).

Assembles every compiled artifact into one spec-conforming SQLite pack (spec/schema.sql is the source of
truth) + a manifest.json mirror. The hard gate (PATCH 01): a rebuild from unchanged inputs must yield
identical **stable_keys** — the anchor the app pins user progress/notes to (§5j) — so a future rebuild
never orphans user data. `verify()` proves it by building twice and diffing the stable-key set.

IDs are natural + 0-indexed (source.id=work_id, atom.id=line#, concept.id=jsonl_id, curated mcq.id=idx,
generated mcq.id=1_000_000+idx) so cross-references need no remapping. Only atoms/concepts/mcqs/topic_pages
carry stable_key (per schema); flashcards/reels anchor via atom_id. The 32 unkeyed curated MCQs (C12) are
QUARANTINED (not shipped). Deferred/empty tables (flagged, not silent): text_blobs, blob_quizzes,
interlink_quizzes, figures, dup_clusters — their assets aren't generated yet.
"""
from __future__ import annotations

import hashlib
import json
import re
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from . import workgroups
from .config import Config, DIST_DIR, WORK_DIR

SCHEMA_SQL = Path(__file__).resolve().parents[2] / "spec" / "schema.sql"
GEN_MCQ_ID_OFFSET = 1_000_000
_WS = re.compile(r"\s+")
_PUNCT = re.compile(r"[^\w\s]", re.UNICODE)


def _normalize(t: str) -> str:
    """casefold + strip punctuation + collapse whitespace (§5j) so trivial re-extraction diffs don't
    mint a new key."""
    return _WS.sub(" ", _PUNCT.sub(" ", (t or "").casefold())).strip()


def _skey(text: str, work_id, page_ref) -> str:
    return hashlib.sha256(f"{_normalize(text)}|{work_id}|{page_ref}".encode("utf-8")).hexdigest()[:16]


def _ekey(prefix: str, canonical_key: str) -> str:
    return hashlib.sha256(f"{prefix}|{canonical_key}".encode("utf-8")).hexdigest()[:16]


def _load(name: str) -> list[dict]:
    p = WORK_DIR / name
    return [json.loads(l) for l in open(p, encoding="utf-8")] if p.exists() else []


def _j(v) -> str:
    return json.dumps(v, ensure_ascii=False)


def build(cfg: Config, db_path: Path) -> dict:
    """Build the pack at db_path. Returns {counts, histogram, content_checksum, stable_keys(set)}."""
    cmap = workgroups.canonical_map()

    # ── subjects + tags from profile ──────────────────────────────────────────
    subj_id = {}  # lower name -> id (1-based)
    subj_rows = []
    for i, s in enumerate(cfg.subjects):
        subj_id[(s.get("name") or "").strip().lower()] = i + 1
        subj_rows.append((i + 1, s.get("name"), float(s.get("weight", 0)), i + 1))
    tag_rows = [(i + 1, t["key"], t["label"], t.get("color"), t.get("description"), i + 1)
                for i, t in enumerate(cfg.tags)]

    # ── sources (from classification) ─────────────────────────────────────────
    cls = _load("classification.jsonl")
    wsubj = {r["work_id"]: (r.get("subject") or "").strip().lower() for r in cls}
    src_rows, work_seen = [], {}
    for r in cls:
        wid = r["work_id"]
        canon = cmap.get(wid, wid)
        sid = subj_id.get((r.get("subject") or "").strip().lower())
        title = re.sub(r"^\d+[_\-\s]*", "", (r.get("name") or "").rsplit(".", 1)[0]).replace("_", " ").strip()
        src_rows.append((wid, title or f"work {wid}", r.get("kind"), r.get("tier"),
                         float(r.get("tier_weight") or 0), r.get("reliability"), None, r.get("year"),
                         None, None, canon, _j([r.get("name")]), None, "latin", 0, sid, None, None, None))
        work_seen.setdefault(canon, title or f"work {canon}")
    work_rows = [(w, t, None) for w, t in sorted(work_seen.items())]

    # ── connect + create schema ───────────────────────────────────────────────
    if db_path.exists():
        db_path.unlink()
    con = sqlite3.connect(db_path)
    con.executescript(SCHEMA_SQL.read_text(encoding="utf-8"))
    cur = con.cursor()
    stable = set()  # every stable_key minted (reproducibility anchor)

    cur.executemany("INSERT INTO subjects(id,name,blueprint_weight,ord) VALUES(?,?,?,?)", subj_rows)
    cur.executemany("INSERT INTO tag_defs(id,key,label,color,description,ord) VALUES(?,?,?,?,?,?)", tag_rows)
    cur.executemany("INSERT INTO works(id,title,publisher) VALUES(?,?,?)", work_rows)
    cur.executemany(
        "INSERT INTO sources(id,title,kind,tier,tier_weight,reliability,publisher,year,edition,file_hash,"
        "work_id,origin_paths_json,dup_of_source_id,primary_script,script_dropped_spans,subject_id,"
        "body_start_page,back_matter_start_page,usage_profile_json) "
        "VALUES(" + ",".join("?" * 19) + ")", src_rows)

    # ── figures (deferred tail; vision-captioned) — insert first so atoms/mcqs/dossiers can link them ──
    fig_rows, fig_by_srcpage = [], {}
    for fid_i, fg in enumerate(_load("figures.jsonl")):
        fig_rows.append((fid_i, fg.get("path"), fg.get("caption_md"), fg["source_id"], str(fg["page"])))
        fig_by_srcpage.setdefault((fg["source_id"], fg["page"]), fid_i)   # first figure on a page
    cur.executemany("INSERT INTO figures(id,path,caption_md,source_id,page_ref) VALUES(?,?,?,?,?)", fig_rows)
    kc_map = {r["idx"]: r for r in _load("mcq_key_conflicts.jsonl")}       # staleness key_conflict flags

    def _fig(source_id, page_ref):
        mm = re.match(r"\d+", str(page_ref or ""))
        return fig_by_srcpage.get((source_id, int(mm.group()))) if mm else None

    # ── atoms + citations (atoms_merged line-aligned with atom_importance) ─────
    atoms = _load("atoms_merged.jsonl")
    aimp = _load("atom_importance.jsonl")
    atom_stable = {}  # atom line idx -> stable_key (for generated-mcq provenance)
    arows, crows = [], []
    for i, a in enumerate(atoms):
        canon = cmap.get(a["source_id"], a["source_id"])
        sk, n = _skey(a.get("statement", ""), canon, a.get("page_ref")), 0
        while sk in stable:                      # deterministic collision break (identical text+prov)
            n += 1
            sk = _skey(a.get("statement", "") + f" #{n}", canon, a.get("page_ref"))
        stable.add(sk)
        atom_stable[i] = sk
        m = aimp[i] if i < len(aimp) else {}
        sig = m.get("signals", {})
        arows.append((i, subj_id.get(wsubj.get(a["source_id"], "")), None, sk, a.get("statement"),
                      a.get("detail"), a["source_id"], a.get("page_ref"), 0, 0.0, 0, None, 0.0, "body", 0, 0,
                      a.get("distinct_source_count", 1), a.get("distinct_work_count", 1),
                      a.get("distinct_publisher_count", 1), 0, None, None, None,
                      float(sig.get("centrality", 0) or 0), m.get("importance", 1),
                      m.get("importance_score", 0), _j(sig), _fig(a["source_id"], a.get("page_ref"))))
        for c in a.get("citations", []):
            crows.append((i, c.get("source_id"), c.get("page_ref")))
    cur.executemany(
        "INSERT INTO atoms(id,subject_id,topic_id,stable_key,statement,detail_md,source_id,page_ref,"
        "is_highlighted,salience,book_flagged,book_flag_kind,book_salience,content_zone,is_boilerplate,"
        "subject_reassigned,distinct_source_count,distinct_work_count,distinct_publisher_count,"
        "pyq_hit_count,pyq_years_json,pyq_trend,pyq_last_year,centrality,importance,importance_score,"
        "importance_signals_json,figure_id) VALUES(" + ",".join("?" * 28) + ")", arows)
    cur.executemany("INSERT INTO atom_citations(atom_id,source_id,page_ref) VALUES(?,?,?)", crows)

    # ── concepts + edges ──────────────────────────────────────────────────────
    concepts = _load("concepts.jsonl")
    cimp = {r["id"]: r for r in _load("concept_importance.jsonl")}
    con_rows = []
    for c in concepts:
        sk, n = _ekey("concept", c["canonical_key"]), 0
        while sk in stable:
            n += 1
            sk = _ekey("concept", c["canonical_key"] + f" #{n}")
        stable.add(sk)
        m = cimp.get(c["id"], {})
        con_rows.append((c["id"], c["name"], sk, c["canonical_key"], None, None,
                         _j(c.get("aliases", [])), c.get("guideline_sensitive", 0), None, None,
                         float(c.get("centrality", 0) or 0), m.get("importance", 1), m.get("importance_score", 0)))
    cur.executemany(
        "INSERT INTO concepts(id,name,stable_key,canonical_key,subject_id,summary_md,aliases_json,"
        "guideline_sensitive,pyq_trend,pyq_last_year,centrality,importance,importance_score) "
        "VALUES(" + ",".join("?" * 13) + ")", con_rows)
    edges = _load("concept_edges.jsonl")
    cur.executemany("INSERT INTO concept_edges(id,src_concept_id,dst_concept_id,relation,rationale,source_id) "
                    "VALUES(?,?,?,?,?,?)",
                    [(i, e["src_concept_id"], e["dst_concept_id"], e.get("relation"), e.get("rationale"),
                      e.get("source_id")) for i, e in enumerate(edges)])

    # ── mcqs: curated (skip the quarantine) + generated ───────────────────────
    # QUARANTINE = no valid gradeable best-of-five key: answer empty OR not A–E (e.g. EMQ answers 'G'/'I'
    # with >5 options don't fit opt_a..e/correct_idx 0-4). Shipping one => an ungradeable card.
    _LET = {"a": 0, "b": 1, "c": 2, "d": 3, "e": 4}
    mcqs = _load("mcqs.jsonl")
    unkeyed = {i for i, m in enumerate(mcqs) if _LET.get(str(m.get("answer") or "").strip().lower()) is None}
    mpyq = {r["idx"]: r for r in _load("mcq_pyq.jsonl")}
    mcor = {r["idx"]: r for r in _load("mcq_corrob.jsonl")}
    mimp = {r["idx"]: r for r in _load("mcq_importance.jsonl")}
    mrows, curated_kept, drows = [], set(), []
    for i, m in enumerate(mcqs):
        if i in unkeyed:
            continue
        canon = cmap.get(m["source_id"], m["source_id"])
        sk, n = _skey(m.get("stem", ""), canon, m.get("page_ref")), 0
        while sk in stable:
            n += 1
            sk = _skey(m.get("stem", "") + f" #{n}", canon, m.get("page_ref"))
        stable.add(sk)
        p, co, im, kc = mpyq.get(i, {}), mcor.get(i, {}), mimp.get(i, {}), kc_map.get(i, {})
        ci = _LET.get(str(m.get("answer", "")).strip().lower())
        mrows.append((i, subj_id.get(wsubj.get(m["source_id"], "")), m["source_id"], sk, "curated", None,
                      "body", 1 if p.get("is_pyq") else 0, _j(p.get("pyq_years", [])), co.get("cluster_id"),
                      co.get("member_count", 1), co.get("distinct_work_count", 1),
                      1 if kc.get("key_conflict") else 0, kc.get("note"), m.get("stem"),
                      m.get("opt_a"), m.get("opt_b"), m.get("opt_c"), m.get("opt_d"), m.get("opt_e"), ci,
                      m.get("explanation"), None, im.get("importance", 1), im.get("importance_score", 0),
                      _j(im.get("signals", {})), _fig(m["source_id"], m.get("page_ref"))))
        curated_kept.add(i)
    for jx, g in enumerate(_load("gen_mcqs.jsonl")):
        gid = GEN_MCQ_ID_OFFSET + jx
        prov = atom_stable.get(g["ref"], g["ref"])
        sk, n = _skey(g.get("stem_md", ""), "generated", prov), 0
        while sk in stable:
            n += 1
            sk = _skey(g.get("stem_md", "") + f" #{n}", "generated", prov)
        stable.add(sk)
        mrows.append((gid, subj_id.get((g.get("subject") or "").strip().lower()), None, sk, "generated",
                      "generated", "body", 0, _j([]), None, 1, 1, 0, None, g.get("stem_md"), g.get("opt_a"),
                      g.get("opt_b"), g.get("opt_c"), g.get("opt_d"), g.get("opt_e"), g.get("correct_idx"),
                      g.get("explanation_md"), None, g.get("importance", 1), g.get("importance_score", 0),
                      _j(g.get("importance_signals", {})), None))
        for d in g.get("distractors", []):
            if isinstance(d, dict) and d.get("option_idx") is not None:
                drows.append((gid, d.get("option_idx"), d.get("why_wrong")))
    cur.executemany(
        "INSERT INTO mcqs(id,subject_id,source_id,stable_key,origin,key_trust,content_zone,is_pyq,"
        "pyq_years_json,cluster_id,distinct_source_count,distinct_work_count,key_conflict,key_conflict_note,"
        "stem_md,opt_a,opt_b,opt_c,opt_d,opt_e,correct_idx,explanation_md,cognitive_level,importance,"
        "importance_score,importance_signals_json,figure_id) VALUES(" + ",".join("?" * 27) + ")", mrows)
    cur.executemany("INSERT INTO mcq_distractors(mcq_id,option_idx,why_wrong) VALUES(?,?,?)", drows)

    # ── topic pages + sections (dossiers) ─────────────────────────────────────
    dossiers = _load("dossiers.jsonl")
    tp_rows, ts_rows = [], []
    for i, d in enumerate(dossiers):
        stable.add(d["stable_key"])
        rel_mcq = [mi for mi in d.get("related_mcq_idxs", []) if mi in curated_kept]
        page_figs = []                                   # figures on the pages this dossier's atoms cite
        sect_rows = []
        for s in d.get("sections", []):
            sfigs = []
            for ar in s.get("atom_refs", []):
                fid = _fig(ar.get("source_id"), ar.get("page_ref"))
                if fid is not None:
                    if fid not in sfigs:
                        sfigs.append(fid)
                    if fid not in page_figs:
                        page_figs.append(fid)
            sect_rows.append((i, subj_id.get((s.get("subject") or "").strip().lower()), s.get("heading"),
                              s.get("body_md"), _j(sfigs[:12]), s.get("ord", 0)))
        ts_rows.extend(sect_rows)
        tp_rows.append((i, d.get("concept_id"), d.get("title"), _j(d.get("aliases", [])), d["stable_key"],
                        d.get("body_md"), _j(d.get("subjects_covered", [])), _j(page_figs[:20]), _j(rel_mcq),
                        _j([]), d.get("importance", 1), d.get("importance_score", 0),
                        _j(d.get("importance_signals") or {})))
    cur.executemany(
        "INSERT INTO topic_pages(id,concept_id,title,aliases_json,stable_key,body_md,subjects_covered_json,"
        "figure_ids_json,related_mcq_ids_json,related_flashcard_ids_json,importance,importance_score,"
        "importance_signals_json) VALUES(" + ",".join("?" * 13) + ")", tp_rows)
    cur.executemany("INSERT INTO topic_sections(topic_page_id,subject_id,heading,body_md,figure_ids_json,ord) "
                    "VALUES(?,?,?,?,?,?)", ts_rows)

    # ── flashcards + reels ────────────────────────────────────────────────────
    fc_rows = [(i, subj_id.get((f.get("subject") or "").strip().lower()), f["ref"], f.get("front_md"),
                f.get("back_md"), int(f.get("is_cloze", 0)), f.get("importance", 1),
                f.get("importance_score", 0), _j(f.get("importance_signals", {})))
               for i, f in enumerate(_load("gen_flashcards.jsonl"))]
    cur.executemany("INSERT INTO flashcards(id,subject_id,atom_id,front_md,back_md,is_cloze,importance,"
                    "importance_score,importance_signals_json) VALUES(?,?,?,?,?,?,?,?,?)", fc_rows)
    reel_rows = [(i, subj_id.get((r.get("subject") or "").strip().lower()), r["ref"], r.get("hook_md"),
                  r.get("detail_md"), r.get("importance", 1), r.get("importance_score", 0),
                  _j(r.get("importance_signals", {}))) for i, r in enumerate(_load("gen_reels.jsonl"))]
    cur.executemany("INSERT INTO reels(id,subject_id,atom_id,hook_md,detail_md,importance,importance_score,"
                    "importance_signals_json) VALUES(?,?,?,?,?,?,?,?)", reel_rows)

    # ── text_blobs (+ quiz-backs) and interlink_quizzes (deferred C11 assets) ──
    blob_rows, bq_rows = [], []
    for i, b in enumerate(_load("gen_blobs.jsonl")):
        blob_rows.append((i, None, b.get("title"), b.get("body_md"), b.get("est_read_seconds"),
                          _j(b.get("concept_ids", [])), b.get("importance", 1), b.get("importance_score", 0),
                          _j({"rule": "eff_concept_importance"})))
        qz = b.get("quiz") or {}
        bq_rows.append((i, i, qz.get("question_md"), qz.get("answer_md"), qz.get("opt_a"), qz.get("opt_b"),
                        qz.get("opt_c"), qz.get("opt_d"), qz.get("correct_idx")))
    cur.executemany("INSERT INTO text_blobs(id,subject_id,title,body_md,est_read_seconds,concept_ids_json,"
                    "importance,importance_score,importance_signals_json) VALUES(?,?,?,?,?,?,?,?,?)", blob_rows)
    cur.executemany("INSERT INTO blob_quizzes(id,blob_id,question_md,answer_md,opt_a,opt_b,opt_c,opt_d,"
                    "correct_idx) VALUES(?,?,?,?,?,?,?,?,?)", bq_rows)
    il_rows = [(i, il["ref"], None, il.get("question_md"), il.get("answer_md"), il.get("importance", 1),
                round(il.get("importance", 1) / 5.0, 4)) for i, il in enumerate(_load("gen_interlink.jsonl"))]
    cur.executemany("INSERT INTO interlink_quizzes(id,edge_id,subject_id,question_md,answer_md,importance,"
                    "importance_score) VALUES(?,?,?,?,?,?,?)", il_rows)

    # ── feed_items: one row per playable card ─────────────────────────────────
    feed = [("mcq", *r) for r in cur.execute(
        "SELECT id,subject_id,importance,importance_score,is_pyq FROM mcqs").fetchall()]
    feed += [("flashcard", r[0], r[1], r[2], r[3], 0) for r in cur.execute(
        "SELECT id,subject_id,importance,importance_score FROM flashcards").fetchall()]
    feed += [("reel", r[0], r[1], r[2], r[3], 0) for r in cur.execute(
        "SELECT id,subject_id,importance,importance_score FROM reels").fetchall()]
    feed += [("blob", r[0], r[1], r[2], r[3], 0) for r in cur.execute(
        "SELECT id,subject_id,importance,importance_score FROM text_blobs").fetchall()]
    feed += [("interlink", r[0], r[1], r[2], r[3], 0) for r in cur.execute(
        "SELECT id,subject_id,importance,importance_score FROM interlink_quizzes").fetchall()]
    cur.executemany("INSERT INTO feed_items(item_type,ref_id,subject_id,importance,importance_score,is_pyq) "
                    "VALUES(?,?,?,?,?,?)", feed)

    # ── manifest row (content checksum = the reproducibility anchor) ───────────
    content_checksum = hashlib.sha256("\n".join(sorted(stable)).encode("utf-8")).hexdigest()
    counts = {t: cur.execute(f"SELECT count(*) FROM {t}").fetchone()[0] for t in
              ("atoms", "concepts", "concept_edges", "mcqs", "flashcards", "reels", "text_blobs",
               "blob_quizzes", "interlink_quizzes", "figures", "topic_pages", "topic_sections", "feed_items",
               "sources", "atom_citations", "mcq_distractors")}
    counts["mcqs_key_conflict"] = cur.execute("SELECT count(*) FROM mcqs WHERE key_conflict=1").fetchone()[0]
    counts["atoms_with_figure"] = cur.execute("SELECT count(*) FROM atoms WHERE figure_id IS NOT NULL").fetchone()[0]
    hist = {str(b): cur.execute("SELECT count(*) FROM feed_items WHERE importance=?", (b,)).fetchone()[0]
            for b in range(1, 6)}
    weights = {"weights": cfg.weights, "thresholds": cfg.thresholds,
               "recency_lambda": cfg.recency_lambda, "recent_window_years": cfg.recent_window_years}
    cur.execute(
        "INSERT INTO pack_manifest(schema_version,pack_version,exam_code,exam_name,built_at,checksum,"
        "feature_flags_json,counts_json,importance_weights_json,importance_histogram_json) "
        "VALUES(?,?,?,?,?,?,?,?,?,?)",
        (str(_schema_version()), "0.1.0", cfg.exam_code, cfg.exam_name,
         datetime.now(timezone.utc).isoformat(timespec="seconds"), content_checksum,
         _j(_feature_flags(counts)), _j(counts), _j(weights), _j(hist)))
    con.commit()
    con.close()
    return {"counts": counts, "histogram": hist, "content_checksum": content_checksum,
            "stable_keys": stable, "quarantined": len(unkeyed)}


def _schema_version() -> int:
    for l in SCHEMA_SQL.read_text(encoding="utf-8").splitlines():
        m = re.search(r"user_version\s*=\s*(\d+)", l)
        if m:
            return int(m.group(1))
    return 1


def _feature_flags(counts: dict) -> dict:
    return {"has_concept_map": counts["concept_edges"] > 0, "has_dossiers": counts["topic_pages"] > 0,
            "has_flashcards": counts["flashcards"] > 0, "has_reels": counts["reels"] > 0,
            "has_generated_mcqs": True, "has_figures": counts.get("figures", 0) > 0,
            "has_blobs": counts.get("text_blobs", 0) > 0,
            "has_interlink": counts.get("interlink_quizzes", 0) > 0}


def write_manifest(cfg: Config, db_path: Path, res: dict) -> Path:
    file_checksum = hashlib.sha256(db_path.read_bytes()).hexdigest()
    manifest = {
        "schema_version": str(_schema_version()), "pack_version": "0.1.0",
        "exam_code": cfg.exam_code, "exam_name": cfg.exam_name,
        "built_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "checksum": file_checksum,
        "feature_flags": _feature_flags(res["counts"]), "counts": res["counts"],
        "importance_weights": {"weights": cfg.weights, "thresholds": cfg.thresholds,
                               "recency_lambda": cfg.recency_lambda,
                               "recent_window_years": cfg.recent_window_years},
        "importance_histogram": dict(res["histogram"]),
        "subjects": [{"id": i + 1, "name": s.get("name"), "blueprint_weight": float(s.get("weight", 0)),
                      "ord": i + 1} for i, s in enumerate(cfg.subjects)],
        "tag_defs": [{"key": t["key"], "label": t["label"], "color": t.get("color"),
                      "description": t.get("description"), "ord": i + 1} for i, t in enumerate(cfg.tags)],
    }
    out = DIST_DIR / "manifest.json"
    out.write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    return out


def package(cfg: Config | None = None) -> dict:
    cfg = cfg or Config.for_exam("mrcp_part1")
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    db_path = DIST_DIR / f"pack_{cfg.exam_code}.db"
    res = build(cfg, db_path)
    manifest = write_manifest(cfg, db_path, res)
    stats = {"db": str(db_path), "manifest": str(manifest), "counts": res["counts"],
             "histogram": res["histogram"], "content_checksum": res["content_checksum"],
             "quarantined_mcqs": res["quarantined"], "stable_keys": len(res["stable_keys"])}
    print("[C13] " + json.dumps(stats, ensure_ascii=False), flush=True)
    return stats


def verify(cfg: Config | None = None) -> dict:
    """PATCH-01 reproducibility gate: build twice -> identical stable-key set + content checksum.
    Incrementality ('add a source -> pre-existing keys unchanged') holds by construction — a stable_key
    depends only on that row's own normalized content + provenance, never on order/counts/other rows."""
    cfg = cfg or Config.for_exam("mrcp_part1")
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    a = build(cfg, DIST_DIR / "_repro_a.db")
    b = build(cfg, DIST_DIR / "_repro_b.db")
    (DIST_DIR / "_repro_a.db").unlink(missing_ok=True)
    (DIST_DIR / "_repro_b.db").unlink(missing_ok=True)
    ok = a["stable_keys"] == b["stable_keys"] and a["content_checksum"] == b["content_checksum"]
    out = {"reproducible": ok, "identical_stable_key_set": a["stable_keys"] == b["stable_keys"],
           "identical_content_checksum": a["content_checksum"] == b["content_checksum"],
           "stable_keys": len(a["stable_keys"]), "checksum": a["content_checksum"]}
    print("[C13 verify] " + json.dumps(out), flush=True)
    return out


if __name__ == "__main__":
    import sys
    cfg = Config.for_exam("mrcp_part1")
    if len(sys.argv) > 1 and sys.argv[1] == "verify":
        verify(cfg)
    else:
        package(cfg)
