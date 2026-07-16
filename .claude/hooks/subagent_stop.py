#!/usr/bin/env python3
"""SubagentStop hook — durability for subagent results (BUILD_PROMPT.md §10b).

A subagent's work should survive the parent dying. Its FULL result (which may contain
book-derived text) goes ONLY to the gitignored work/ log (work/subagent_results.jsonl).
Never blocks.

NOTE: we deliberately do NOT append a per-subagent breadcrumb to the committed JOURNAL.md.
That was pure noise at scale — a single stage (e.g. C11 asset generation) fans out dozens
of gen-* subagents, which flooded the human milestone log with 170+ "subagent finished" lines
and raced on concurrent finishes. The durable record already lives in the work/ log; JOURNAL.md
is for human milestones (written by state.py), not per-subagent metadata.
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
except Exception:
    pass

sys.exit(0)
