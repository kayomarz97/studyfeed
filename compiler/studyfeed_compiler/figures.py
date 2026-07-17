"""C11 figures (deferred tail) — extract → caption → link (BUILD_PROMPT §4 figure-handler + schema `figures`).

Bounded scope (user choice): pull only plausible medical figures from the DIGITAL textbooks (scanned papers
are whole-page images, not figures), caption each with a vision subagent, link to atoms/pages, and populate
the `figures` table + `figure_id`. Verbatim copyrighted images → stay in the gitignored dist/figures/, never
committed (same personal-use posture as the text atoms).

extract(): each unique embedded image OBJECT once per PDF (dedup by xref, then global content-hash); filter
by size/aspect; drop page-furniture (an image reused on many pages = logo/header). In-scope works only (those
that contributed atoms). → work/figures_raw/<id>.png + work/figures_candidates.jsonl. Captioning + linking
are separate steps (vision subagents write work/figures_captioned.jsonl; pack.py folds them in).
"""
from __future__ import annotations

import glob
import hashlib
import json
import os

from .config import Config, WORK_DIR

RAW_DIR = WORK_DIR / "figures_raw"
CANDIDATES = WORK_DIR / "figures_candidates.jsonl"
MIN_W = MIN_H = 250          # a real figure, not an icon/rule
ASPECT_LO, ASPECT_HI = 0.25, 4.0
FURNITURE_MAX_PAGES = 8      # same image on >N pages ⇒ logo/header furniture, not a figure


def _work_paths() -> dict[int, str]:
    """In-scope DIGITAL textbook/pearls work_id → unpacked PDF path (only works that contributed atoms)."""
    cls = [json.loads(l) for l in open(WORK_DIR / "classification.jsonl", encoding="utf-8")]
    in_scope = {json.loads(l)["source_id"] for l in open(WORK_DIR / "atoms_merged.jsonl", encoding="utf-8")}
    by_name = {os.path.basename(p): p for p in glob.glob(str(WORK_DIR / "unpacked/**/*.pdf"), recursive=True)}
    out = {}
    for r in cls:
        if r.get("scanned") or r.get("kind") not in ("textbook", "pearls") or r["work_id"] not in in_scope:
            continue
        p = by_name.get(r.get("name"))
        if p:
            out[r["work_id"]] = p
    return out


def extract(cfg: Config | None = None) -> dict:
    import fitz  # PyMuPDF
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    for f in glob.glob(str(RAW_DIR / "*.png")):
        os.remove(f)
    paths = _work_paths()
    seen_sha: dict[str, dict] = {}          # global content-dedup across works
    rows, stats = [], {"works": 0, "raw_images": 0, "kept": 0, "furniture": 0, "too_small": 0, "dup": 0}
    for wid, path in sorted(paths.items()):
        try:
            doc = fitz.open(path)
        except Exception:
            continue
        stats["works"] += 1
        xref_pages: dict[int, list[int]] = {}
        for pno, page in enumerate(doc):
            for img in page.get_images(full=True):
                xref_pages.setdefault(img[0], []).append(pno)
        for xref, pages in sorted(xref_pages.items()):
            stats["raw_images"] += 1
            if len(pages) > FURNITURE_MAX_PAGES:
                stats["furniture"] += 1
                continue
            try:
                pix = fitz.Pixmap(doc, xref)
                if pix.n >= 5 or pix.alpha:                 # CMYK / alpha → RGB
                    pix = fitz.Pixmap(fitz.csRGB, pix)
                w, h = pix.width, pix.height
                if w < MIN_W or h < MIN_H or not (ASPECT_LO <= w / max(1, h) <= ASPECT_HI):
                    stats["too_small"] += 1
                    continue
                data = pix.tobytes("png")
            except Exception:
                continue
            sha = hashlib.sha256(data).hexdigest()[:16]
            if sha in seen_sha:
                stats["dup"] += 1
                seen_sha[sha]["occurrences"] += len(pages)
                continue
            out_png = RAW_DIR / f"{sha}.png"
            out_png.write_bytes(data)
            rec = {"fig_id": sha, "source_id": wid, "page": pages[0] + 1, "path": str(out_png),
                   "w": w, "h": h, "occurrences": len(pages)}
            seen_sha[sha] = rec
            rows.append(rec)
            stats["kept"] += 1
        doc.close()
    if CANDIDATES.exists():
        CANDIDATES.unlink()
    with open(CANDIDATES, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("[figures extract] " + json.dumps(stats), flush=True)
    return stats


CAP_IN = WORK_DIR / "figures_caption_input"
CAP_OUT = WORK_DIR / "figures_caption_output"
FIGURES_OUT = WORK_DIR / "figures.jsonl"
DIST_FIGURES = None  # set in collect_captions from config


def prep_caption(chunk_size: int = 30) -> dict:
    """Chunk the candidates into small groups for vision subagents (each Reads the images + decides
    keep/caption). Resumable: a chunk is pending until its output file exists."""
    CAP_IN.mkdir(parents=True, exist_ok=True)
    CAP_OUT.mkdir(parents=True, exist_ok=True)
    cands = [json.loads(l) for l in open(CANDIDATES, encoding="utf-8")]
    ids = []
    for i in range(0, len(cands), chunk_size):
        cid = f"{i // chunk_size:04d}"
        with open(CAP_IN / f"{cid}.jsonl", "w", encoding="utf-8") as f:
            for r in cands[i:i + chunk_size]:
                f.write(json.dumps({"fig_id": r["fig_id"], "path": r["path"]}, ensure_ascii=False) + "\n")
        ids.append(cid)
    print(f"[figures prep-caption] {len(cands)} candidates -> {len(ids)} chunks (size {chunk_size})", flush=True)
    return {"chunks": ids, "count": len(cands)}


def pending_caption() -> list[str]:
    return [os.path.basename(p)[:-6] for p in sorted(glob.glob(str(CAP_IN / "*.jsonl")))
            if not (CAP_OUT / os.path.basename(p)).exists()]


def collect_captions(cfg: Config | None = None) -> dict:
    """Keep vision-confirmed medical figures; copy their PNGs to dist/figures/; write work/figures.jsonl
    {fig_id, source_id, page, caption_md, kind, path}. Deterministic (sorted by fig_id)."""
    import shutil
    from .config import DIST_DIR
    cfg = cfg or Config.for_exam("mrcp_part1")
    dist_fig = DIST_DIR / "figures"
    dist_fig.mkdir(parents=True, exist_ok=True)
    for f in glob.glob(str(dist_fig / "*.png")):
        os.remove(f)
    cand = {r["fig_id"]: r for r in (json.loads(l) for l in open(CANDIDATES, encoding="utf-8"))}
    kept, seen, stats = [], set(), {"seen": 0, "kept": 0, "dropped": 0, "bad": 0}
    for p in sorted(glob.glob(str(CAP_OUT / "*.jsonl"))):
        for l in open(p, encoding="utf-8"):
            l = l.strip()
            if not l:
                continue
            try:
                r = json.loads(l)
            except json.JSONDecodeError:
                stats["bad"] += 1
                continue
            fid = r.get("fig_id")
            if fid not in cand or fid in seen:
                continue
            seen.add(fid)
            stats["seen"] += 1
            cap = (r.get("caption") or "").strip()
            if not r.get("keep") or not cap:
                stats["dropped"] += 1
                continue
            c = cand[fid]
            dst = dist_fig / f"{fid}.png"
            try:
                shutil.copyfile(c["path"], dst)
            except OSError:
                stats["bad"] += 1
                continue
            kept.append({"fig_id": fid, "source_id": c["source_id"], "page": c["page"],
                         "caption_md": cap, "kind": (r.get("kind") or "figure"),
                         "path": f"figures/{fid}.png"})
            stats["kept"] += 1
    kept.sort(key=lambda r: r["fig_id"])
    if FIGURES_OUT.exists():
        FIGURES_OUT.unlink()
    with open(FIGURES_OUT, "w", encoding="utf-8") as f:
        for r in kept:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"[figures collect] {json.dumps(stats)} -> {FIGURES_OUT}", flush=True)
    return stats


if __name__ == "__main__":
    import sys
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "extract":
        extract()
    elif cmd == "prep-caption":
        prep_caption()
    elif cmd == "collect":
        collect_captions()
    elif cmd == "pending":
        print(json.dumps(pending_caption()))
    else:
        print("usage: figures.py [extract|prep-caption|collect|pending]")
