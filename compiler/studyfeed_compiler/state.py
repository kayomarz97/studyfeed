"""Durability core (BUILD_PROMPT.md §10a).

State lives on disk, never in the conversation. A fresh session must resume from
`compiler/.compiler_state.json` ALONE. This module is the ONLY writer of that file, and it
writes it atomically (temp file + os.replace) so a kill mid-write can never corrupt it.

Two artefacts, both maintained, never substitutes for each other:
  - .compiler_state.json  → machine-readable resume truth (this module)
  - JOURNAL.md            → human prose milestone log (append_journal)

The Claude Code hooks in .claude/hooks/ read these files with plain stdlib and do NOT import
this package, so they keep working even without the compiler venv on sys.path.
"""

from __future__ import annotations

import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

# repo_root/compiler/studyfeed_compiler/state.py  →  parents[1] == compiler/, parents[2] == repo root
COMPILER_DIR: Path = Path(__file__).resolve().parents[1]
REPO_ROOT: Path = Path(__file__).resolve().parents[2]
STATE_PATH: Path = COMPILER_DIR / ".compiler_state.json"
JOURNAL_PATH: Path = REPO_ROOT / "JOURNAL.md"

# The full staged pipeline, so `next_action` / `completed` always have a canonical vocabulary.
STAGES: list[str] = [f"C{i}" for i in range(14)]  # C0..C13 (compiler); app phases are A0..A7

DEFAULT_STATE: dict[str, Any] = {
    "schema": 1,
    "updated": None,
    "stage": "C0",
    "completed": [],
    "next_action": "scaffold spec + compiler",
    "sources_seen": {},          # canonical_path -> "sha256:..."  (skip unchanged on re-run)
    "archives": {},              # original filename -> {sha256, size_bytes, moved_to}
    "batches": [],               # in-flight Batch API jobs — survive session death (§10d)
    "cursors": {},               # e.g. {"atomize_page": 4310, "gen_mcq": 6200}
    "blocked_on": None,          # a question string when waiting at a ⛔ gate
    "decisions": {},             # human decisions recorded so a fresh session honours them
    "token_spend_estimate_usd": 0.0,
}


def _utcnow() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def read_state() -> dict[str, Any]:
    """Return the current state, or a fresh default skeleton if none exists yet."""
    if STATE_PATH.exists():
        with open(STATE_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    return json.loads(json.dumps(DEFAULT_STATE))  # deep copy


def write_state(state: dict[str, Any]) -> dict[str, Any]:
    """Atomically write the whole state dict. Stamps `updated`. The ONLY writer of the file."""
    state["updated"] = _utcnow()
    COMPILER_DIR.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(COMPILER_DIR), prefix=".compiler_state.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(state, f, indent=2, ensure_ascii=False, sort_keys=False)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, STATE_PATH)  # atomic rename on POSIX
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    return state


def update_state(**changes: Any) -> dict[str, Any]:
    """Shallow-merge top-level keys into state and persist atomically."""
    state = read_state()
    state.update(changes)
    return write_state(state)


def record_completed(stage: str, next_stage: str | None = None, next_action: str | None = None) -> dict[str, Any]:
    """Mark `stage` complete; advance `stage`/`next_action` for resumption."""
    state = read_state()
    completed = state.setdefault("completed", [])
    if stage not in completed:
        completed.append(stage)
    if next_stage is not None:
        state["stage"] = next_stage
    if next_action is not None:
        state["next_action"] = next_action
    return write_state(state)


def set_decision(key: str, value: Any) -> dict[str, Any]:
    """Record a human decision (e.g. part2_scope) so a fresh session honours it."""
    state = read_state()
    state.setdefault("decisions", {})[key] = value
    return write_state(state)


def add_batch(batch: dict[str, Any]) -> dict[str, Any]:
    """Persist a Batch API job id BEFORE polling (§10d) — an unrecorded batch id is money burned."""
    state = read_state()
    state.setdefault("batches", []).append(batch)
    return write_state(state)


def append_journal(entry: str, marker: str | None = None) -> None:
    """Append one prose milestone to JOURNAL.md. Never rewrites; only appends."""
    ts = _utcnow()
    head = f"## {ts}" + (f"  {marker}" if marker else "")
    with open(JOURNAL_PATH, "a", encoding="utf-8") as f:
        f.write(f"\n{head}\n\n{entry.rstrip()}\n")


def _cli(argv: list[str]) -> int:
    """Tiny CLI so shells/hooks can poke state without inline python.

    Usage: python -m studyfeed_compiler.state show
           python -m studyfeed_compiler.state journal "message"
    """
    if not argv or argv[0] == "show":
        print(json.dumps(read_state(), indent=2, ensure_ascii=False))
        return 0
    if argv[0] == "journal" and len(argv) > 1:
        append_journal(argv[1])
        print("journal appended")
        return 0
    print("usage: python -m studyfeed_compiler.state [show | journal <msg>]")
    return 2


if __name__ == "__main__":
    import sys

    raise SystemExit(_cli(sys.argv[1:]))
