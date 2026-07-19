"""C2 extraction (BUILD_PROMPT.md §7 C2 / §4 extractor-textpdf + extractor-scanned).

Per page: PyMuPDF text in reading order; if a page has ~no text (scanned), render it (capped to a
fixed pixel width — the scans have huge native dimensions) and OCR with tesseract (English-only).
Then apply the §5g script filter. Writes per-work JSONL with page refs. Zero tokens — scripts run,
pages never enter a context window (§10c). Scanned works OCR in parallel across all cores. Checkpointed
per work (skips already-extracted works) so a killed run resumes. `smoke` proves quality on a spread of
sample pages before the full run.
"""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import multiprocessing as mp
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path
from typing import Any

import fitz  # PyMuPDF

from .. import scriptfilter
from ..config import Config, UNPACKED_DIR, WORK_DIR

EXTRACT_DIR = WORK_DIR / "extracted"
MIN_TEXT_CHARS = 20        # a page below this via PyMuPDF is treated as scanned -> OCR
OCR_TARGET_PX = 2500       # render scanned pages capped to this width (native dims are huge)
NWORKERS = max(1, os.cpu_count() or 4)

SMOKE = [
    "3-Respiratory.pdf", "16-Clinical Science.pdf", "100 commonly tested facts for MRCP Part 1.pdf",
    "Step to MRCP 3rd Edition.pdf", "24_MRCP One August 2022.pdf", "9. Cli Pharma (C).pdf",
]


def _render_ocr(page: "fitz.Page", target_px: int = OCR_TARGET_PX) -> str:
    zoom = target_px / page.rect.width if page.rect.width else 2.0
    pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom))
    fd, tmp = tempfile.mkstemp(suffix=".png")
    os.close(fd)
    try:
        pix.save(tmp)
        r = subprocess.run(["tesseract", tmp, "-", "-l", "eng", "--oem", "1", "--psm", "3"],
                           capture_output=True, text=True, timeout=240,
                           env={**os.environ, "OMP_THREAD_LIMIT": "1"})  # 1 thread/proc -> workers own the cores
        return r.stdout if r.returncode == 0 else ""
    finally:
        os.unlink(tmp)


def _ocr_chunk(args: tuple[str, list[int], int]) -> list[tuple[int, str]]:
    """Top-level worker (picklable): OCR a set of pages of one PDF. Opens the doc once."""
    path, pnos, target_px = args
    doc = fitz.open(path)
    out = [(pno, _render_ocr(doc[pno - 1], target_px)) for pno in pnos]
    doc.close()
    return out


def _finalize_page(text: str, ranges, thr: float, work_id: int, pno: int, method: str,
                   drops: list[dict]) -> dict:
    ftext, dropped = scriptfilter.filter_text(text, ranges, thr)
    for d in dropped:
        drops.append({"work_id": work_id, "page": pno, "span": d[:160]})
    return {"page": pno, "method": method, "chars": len(ftext.strip()), "text": ftext}


def extract_pdf(path: Path, work_id: int, name: str, cfg: Config,
                sample: int | None = None) -> tuple[dict, list[dict], list[dict]]:
    """Serial extraction (used by smoke; run_all parallelizes scanned works separately)."""
    doc = fitz.open(str(path))
    n = doc.page_count
    pages = _sample_pages(n, sample) if sample else list(range(1, n + 1))
    ranges = scriptfilter.dropped_ranges(cfg)
    thr = float(cfg.scripts.get("drop_threshold", 0.30))
    records, drops, methods = [], [], {"text": 0, "ocr": 0}
    for pno in pages:
        page = doc[pno - 1]
        text = page.get_text("text", sort=True)
        method = "text"
        if len(text.strip()) < MIN_TEXT_CHARS:
            text = _render_ocr(page)
            method = "ocr"
        methods[method] += 1
        records.append(_finalize_page(text, ranges, thr, work_id, pno, method, drops))
    doc.close()
    return _summary(work_id, name, n, records, methods, drops), records, drops


def _summary(work_id, name, n, records, methods, drops) -> dict:
    return {"work_id": work_id, "name": name, "pages_total": n, "pages_extracted": len(records),
            "methods": methods, "script_drops": len(drops),
            "avg_chars": round(sum(r["chars"] for r in records) / max(1, len(records)))}


def _sample_pages(n: int, k: int) -> list[int]:
    if n <= k:
        return list(range(1, n + 1))
    return sorted({max(1, min(n, round((i + 0.5) / k * n))) for i in range(k)})


def _resolve(names: list[str]) -> list[tuple[str, str | None]]:
    canon: dict[str, str] = {}
    for line in open(WORK_DIR / "dedupe_canonicals.jsonl", encoding="utf-8"):
        c = json.loads(line)
        canon.setdefault(c["name"], c["path"])
    return [(nm, canon.get(nm)) for nm in names]


def run_smoke(cfg: Config, names: list[str] | None = None, sample: int = 8) -> None:
    for nm, rel in _resolve(names or SMOKE):
        if not rel:
            print(f"\n### {nm}\n  !! NOT FOUND")
            continue
        summ, recs, _ = extract_pdf(UNPACKED_DIR / rel, -1, nm, cfg, sample=sample)
        print(f"\n### {nm}\n  {summ['pages_total']}pp | sampled {summ['pages_extracted']} | "
              f"methods={summ['methods']} | ~{summ['avg_chars']} chars/pg | drops={summ['script_drops']}")
        rich = max(recs, key=lambda r: r["chars"])
        print(f"  p{rich['page']} [{rich['method']}]: {' '.join(rich['text'].split())[:420]}")


def _safe(name: str) -> str:
    return "".join(c if c.isalnum() else "_" for c in name)[:60]


def run_all(cfg: Config, target_px: int = OCR_TARGET_PX) -> dict[str, Any]:
    """Full extraction of every non-excluded classified work, checkpointed per work.
    Scanned works OCR in parallel across all cores; text works extract serially (fast)."""
    EXTRACT_DIR.mkdir(parents=True, exist_ok=True)
    cls = {json.loads(l)["work_id"]: json.loads(l) for l in open(WORK_DIR / "classification.jsonl", encoding="utf-8")}
    canon = [json.loads(l) for l in open(WORK_DIR / "dedupe_canonicals.jsonl", encoding="utf-8")]
    ranges = scriptfilter.dropped_ranges(cfg)
    thr = float(cfg.scripts.get("drop_threshold", 0.30))
    todo = [c for c in canon if cls.get(c["work_id"], {}).get("kind") != "excluded"
            and not (EXTRACT_DIR / f"{c['work_id']:03d}__{_safe(c['name'])}.jsonl").exists()]
    print(f"to extract: {len(todo)} works ({NWORKERS} OCR workers)", flush=True)

    drops_f = open(WORK_DIR / "script_drops.jsonl", "a", encoding="utf-8")
    done = ocr_pages = text_pages = 0
    with ProcessPoolExecutor(max_workers=NWORKERS, mp_context=mp.get_context("fork")) as pool:
        for c in todo:
            wid, path, name = c["work_id"], UNPACKED_DIR / c["path"], c["name"]
            out = EXTRACT_DIR / f"{wid:03d}__{_safe(name)}.jsonl"
            drops: list[dict] = []
            if c.get("scanned"):
                doc = fitz.open(str(path)); n = doc.page_count; doc.close()
                chunks = [list(range(1, n + 1))[i::NWORKERS] for i in range(NWORKERS)]
                page_text: dict[int, str] = {}
                for res in pool.map(_ocr_chunk, [(str(path), ch, target_px) for ch in chunks if ch]):
                    for pno, txt in res:
                        page_text[pno] = txt
                records = [_finalize_page(page_text.get(p, ""), ranges, thr, wid, p, "ocr", drops)
                           for p in range(1, n + 1)]
                ocr_pages += n
            else:
                summ, records, drops = extract_pdf(path, wid, name, cfg)
                n = summ["pages_total"]; text_pages += n
            with open(out, "w", encoding="utf-8") as f:
                for r in records:
                    f.write(json.dumps(r, ensure_ascii=False) + "\n")
            for d in drops:
                drops_f.write(json.dumps(d, ensure_ascii=False) + "\n")
            done += 1
            avg = round(sum(r["chars"] for r in records) / max(1, len(records)))
            print(f"[{done}/{len(todo)}] {name[:42]:42s} {n:>4}pp {'OCR' if c.get('scanned') else 'txt'} ~{avg}c/pg", flush=True)
    drops_f.close()
    return {"works": done, "ocr_pages": ocr_pages, "text_pages": text_pages}


if __name__ == "__main__":
    import sys

    cfg = Config.for_exam("mrcp_part1")
    mode = sys.argv[1] if len(sys.argv) > 1 else "smoke"
    if mode == "smoke":
        run_smoke(cfg)
    elif mode == "all":
        print(run_all(cfg))
