"""Shared helpers for StudyFeed .claude/hooks/* (BUILD_PROMPT.md §10b).

Pure stdlib, NO import of the compiler package — hooks must run under whatever Python the
harness uses, without the compiler venv on sys.path. Every helper swallows its own errors:
a hook must NEVER disrupt the session, so callers still default to exit 0 on any failure.

Content policy (§11b): full subagent results go to the GITIGNORED work/ log; the committed
JOURNAL.md only ever receives metadata (counts/ids/decisions), never book-derived content.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

# .claude/hooks/_hooklib.py -> parents[2] == repo root
REPO_ROOT = Path(__file__).resolve().parents[2]
STATE_PATH = REPO_ROOT / "compiler" / ".compiler_state.json"
JOURNAL_PATH = REPO_ROOT / "JOURNAL.md"
SUBAGENT_LOG = REPO_ROOT / "compiler" / "work" / "subagent_results.jsonl"  # gitignored


def utcnow() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def read_stdin_json() -> dict:
    try:
        raw = sys.stdin.read()
        return json.loads(raw) if raw and raw.strip() else {}
    except Exception:
        return {}


def read_state() -> dict:
    try:
        with open(STATE_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def journal_tail(max_chars: int = 3000) -> str:
    try:
        return JOURNAL_PATH.read_text(encoding="utf-8")[-max_chars:]
    except Exception:
        return ""


def append_journal_line(line: str) -> None:
    try:
        with open(JOURNAL_PATH, "a", encoding="utf-8") as f:
            f.write(line.rstrip("\n") + "\n")
    except Exception:
        pass


def append_subagent_log(obj: dict) -> None:
    try:
        SUBAGENT_LOG.parent.mkdir(parents=True, exist_ok=True)
        with open(SUBAGENT_LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps(obj, ensure_ascii=False) + "\n")
    except Exception:
        pass
