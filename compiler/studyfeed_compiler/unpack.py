"""Recursive archive expansion -> flat, path-qualified PDF inventory (BUILD_PROMPT.md §7 C1a).

Expands every drop in sources/inbox/ into work/unpacked/<drop>/, following nested archives at mixed
depth (the loose PDFs AND the nested Pastest/Passmedicine RARs). Guards: depth <= max_depth,
total expansion <= max_expanded_gb (archive-bomb guard). Ignores __MACOSX/.DS_Store/thumbs.
Password-protected / corrupt / unknown archives are recorded as issues and surfaced for the human
(never skipped silently). This stage runs SCRIPTS; it never reads a page into a context window (§10c).

Idempotent: a drop whose `_root` already exists is not re-extracted; the inventory is rebuilt fresh
from disk on every run. RAR/7z go through `unar` (RAR5-capable, verified at C0); zip/tar via stdlib.
"""

from __future__ import annotations

import fnmatch
import json
import shutil
import subprocess
import tarfile
import zipfile
from pathlib import Path
from typing import Any

from . import state
from .config import Config, SOURCES_INBOX, UNPACKED_DIR, WORK_DIR

ARCHIVE_EXTS = {".zip", ".cbz", ".rar", ".7z", ".tar", ".tgz"}
_UNAR_EXTS = {".rar", ".7z"}


def _ignored(name: str, patterns: list[str]) -> bool:
    base = Path(name).name
    if base in {".DS_Store", "Thumbs.db"} or base.startswith("._"):
        return True
    if "__MACOSX" in Path(name).parts:
        return True
    return any(fnmatch.fnmatch(name, pat) or fnmatch.fnmatch(base, pat) for pat in patterns)


def _is_archive(p: Path) -> bool:
    n = p.name.lower()
    return p.suffix.lower() in ARCHIVE_EXTS or n.endswith(".tar.gz")


def _extract(arch: Path, dest: Path, issues: list[dict]) -> bool:
    """Extract one archive into dest. Returns True on success; records an issue and returns False otherwise."""
    dest.mkdir(parents=True, exist_ok=True)
    name = arch.name.lower()
    ext = arch.suffix.lower()
    try:
        if ext in {".zip", ".cbz"}:
            with zipfile.ZipFile(arch) as z:
                for m in z.infolist():
                    if m.is_dir() or _ignored(m.filename, []):
                        continue
                    try:
                        z.extract(m, dest)
                    except RuntimeError as e:  # e.g. encrypted member
                        issues.append({"archive": str(arch), "member": m.filename, "error": f"zip member: {e}"})
        elif ext in {".tar", ".tgz"} or name.endswith(".tar.gz"):
            with tarfile.open(arch) as t:
                t.extractall(dest, filter="data")
        elif ext == ".rar" and shutil.which("unrar"):
            # unrar (RARLAB reference) is robust on RAR5; unar/libarchive SILENTLY TRUNCATES some
            # files ("attempted to read more data than was available"). Prefer unrar for .rar.
            r = subprocess.run(
                ["unrar", "x", "-o+", "-inul", str(arch), str(dest) + "/"],
                capture_output=True, text=True, timeout=3600,
            )
            if r.returncode != 0:
                issues.append({"archive": str(arch), "error": f"unrar rc={r.returncode}: {(r.stderr or r.stdout)[:200]}"})
                return False
        elif ext in _UNAR_EXTS:  # .7z, or .rar fallback if unrar is unavailable
            r = subprocess.run(
                ["unar", "-quiet", "-force-overwrite", "-no-directory", "-output-directory", str(dest), str(arch)],
                capture_output=True, text=True, timeout=3600,
            )
            if r.returncode != 0:
                issues.append({"archive": str(arch), "error": f"unar rc={r.returncode}: {(r.stderr or r.stdout)[:200]}"})
                return False
        else:
            issues.append({"archive": str(arch), "error": f"unsupported archive extension {ext}"})
            return False
        return True
    except (zipfile.BadZipFile, tarfile.TarError, OSError) as e:
        issues.append({"archive": str(arch), "error": f"{type(e).__name__}: {e}"})
        return False
    except subprocess.TimeoutExpired:
        issues.append({"archive": str(arch), "error": "unar timeout (>3600s)"})
        return False


def _dir_bytes(root: Path) -> int:
    return sum(f.stat().st_size for f in root.rglob("*") if f.is_file())


def run(cfg: Config, force: bool = False) -> dict[str, Any]:
    budget = int(cfg.max_expanded_gb * (1024 ** 3))
    ignore = list(cfg.archives.get("ignore_globs", []))
    max_depth = int(cfg.archives.get("max_depth", 8))
    UNPACKED_DIR.mkdir(parents=True, exist_ok=True)

    issues: list[dict] = []
    expanded_nested: list[dict] = []
    # queue items: (archive_path, dest_dir, depth, drop, origin_lineage)
    queue: list[tuple[Path, Path, int, str, str]] = []
    for d in cfg.drops:
        glob = d.get("glob", "")
        for arch in sorted(SOURCES_INBOX.glob(glob)):
            drop = arch.stem
            root = UNPACKED_DIR / drop / "_root"
            if root.exists() and any(root.rglob("*.pdf")) and not force:
                continue  # idempotent: already extracted
            queue.append((arch, root, 0, drop, arch.name))

    counter = 0
    while queue:
        arch, dest, depth, drop, origin = queue.pop(0)
        if depth > max_depth:
            issues.append({"archive": str(arch), "error": f"depth exceeded {max_depth}", "origin": origin})
            continue
        if not _extract(arch, dest, issues):
            continue
        for f in sorted(p for p in dest.rglob("*") if p.is_file()):
            if _ignored(str(f.relative_to(dest)), ignore):
                f.unlink(missing_ok=True)
                continue
            if _is_archive(f):
                counter += 1
                sub = UNPACKED_DIR / drop / f"_nested{counter:03d}"
                queue.append((f, sub, depth + 1, drop, f"{origin} > {f.name}"))
                expanded_nested.append({"drop": drop, "archive": f.name, "from": origin, "depth": depth + 1})
        # archive-bomb guard after each expansion
        total = _dir_bytes(UNPACKED_DIR)
        if total > budget:
            issues.append({"error": f"archive-bomb guard: expansion {total / 1024 ** 3:.1f}GB > {cfg.max_expanded_gb}GB"})
            _write_reports(issues, expanded_nested)
            raise SystemExit(f"⛔ archive-bomb guard tripped at {total / 1024 ** 3:.1f}GB — aborting.")

    # nested archive files are now expanded -> delete them to reclaim disk (reconstructable from inbox)
    for f in list(UNPACKED_DIR.rglob("*")):
        if f.is_file() and _is_archive(f):
            f.unlink(missing_ok=True)

    # build inventory fresh from disk: every surviving PDF, path-qualified by drop + nesting
    inventory: list[dict] = []
    other: list[dict] = []
    for f in sorted(p for p in UNPACKED_DIR.rglob("*") if p.is_file()):
        rel = f.relative_to(UNPACKED_DIR)
        drop = rel.parts[0]
        row = {"drop": drop, "path": str(rel), "name": f.name, "size": f.stat().st_size}
        if f.suffix.lower() == ".pdf":
            inventory.append(row)
        elif not _ignored(f.name, ignore):
            other.append({**row, "ext": f.suffix.lower()})

    _write_inventory(inventory, other)
    _write_reports(issues, expanded_nested)

    per_drop: dict[str, int] = {}
    per_drop_bytes: dict[str, int] = {}
    for r in inventory:
        per_drop[r["drop"]] = per_drop.get(r["drop"], 0) + 1
        per_drop_bytes[r["drop"]] = per_drop_bytes.get(r["drop"], 0) + r["size"]

    return {
        "pdf_total": len(inventory), "per_drop": per_drop,
        "per_drop_mb": {k: round(v / 1024 ** 2, 1) for k, v in per_drop_bytes.items()},
        "nested_expanded": len(expanded_nested), "other_files": len(other),
        "issues": len(issues), "expanded_gb": round(_dir_bytes(UNPACKED_DIR) / 1024 ** 3, 2),
    }


def _write_inventory(inventory: list[dict], other: list[dict]) -> None:
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    with open(WORK_DIR / "inventory.jsonl", "w", encoding="utf-8") as f:
        for row in inventory:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    with open(WORK_DIR / "unpack_other.jsonl", "w", encoding="utf-8") as f:
        for row in other:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def _write_reports(issues: list[dict], expanded_nested: list[dict]) -> None:
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    with open(WORK_DIR / "unpack_issues.jsonl", "w", encoding="utf-8") as f:
        for row in issues:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    with open(WORK_DIR / "unpack_nested.jsonl", "w", encoding="utf-8") as f:
        for row in expanded_nested:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    import sys

    cfg = Config.for_exam("mrcp_part1")
    force = "--force" in sys.argv
    free_gb = shutil.disk_usage(str(WORK_DIR.parent)).free / 1024 ** 3
    print(f"free disk: {free_gb:.1f}GB | budget: {cfg.max_expanded_gb}GB | inbox: {SOURCES_INBOX}")
    summ = run(cfg, force=force)
    print("\n=== UNPACK SUMMARY ===")
    print(f"  PDFs total:        {summ['pdf_total']}")
    print(f"  per drop:          {summ['per_drop']}")
    print(f"  per drop (MB):     {summ['per_drop_mb']}")
    print(f"  nested expanded:   {summ['nested_expanded']}")
    print(f"  other (non-pdf):   {summ['other_files']}  (see work/unpack_other.jsonl)")
    print(f"  issues:            {summ['issues']}  (see work/unpack_issues.jsonl)")
    print(f"  expanded on disk:  {summ['expanded_gb']}GB")
    state.update_state(cursors={**state.read_state().get("cursors", {}), "unpack_pdfs": summ["pdf_total"]})
