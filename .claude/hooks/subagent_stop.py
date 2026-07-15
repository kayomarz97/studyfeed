#!/usr/bin/env python3
"""SubagentStop hook — durability for subagent results (BUILD_PROMPT.md §10b).

A subagent's work should survive the parent dying. Its FULL result (which may contain
book-derived text) goes ONLY to the gitignored work/ log; the committed JOURNAL.md receives
metadata only (§11b — nothing derived from the books in a committed file). Never blocks.
"""

from __future__ import annotations

import sys

try:
    import _hooklib as H

    payload = H.read_stdin_json()
    atype = payload.get("agent_type", "unknown")
    aid = payload.get("agent_id", "")
    msg = payload.get("last_assistant_message", "") or ""
    H.append_subagent_log({"ts": H.utcnow(), "agent_type": atype, "agent_id": aid, "result": msg})
    H.append_journal_line(
        f"- subagent `{atype}` ({aid}) finished — {len(msg)} chars -> work/subagent_results.jsonl")
except Exception:
    pass

sys.exit(0)
