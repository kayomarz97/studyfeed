#!/usr/bin/env python3
"""SessionStart hook — THE resume mechanism (BUILD_PROMPT.md §10a/§10b).

Injects compiler state + JOURNAL tail as additionalContext (hard cap 10,000 chars). A fresh
session must be able to resume from this ALONE. Cannot block session entry; always exit 0.
"""

from __future__ import annotations

import json
import sys


def main() -> None:
    import _hooklib as H

    payload = H.read_stdin_json()
    source = payload.get("source", "unknown")
    st = H.read_state()

    lines = [
        "=== StudyFeed build — resume state (SessionStart hook) ===",
        "Authoritative spec: BUILD_PROMPT.md (repo root). Resume truth: compiler/.compiler_state.json.",
        "If unsure what you are building, re-read BUILD_PROMPT.md. Do NOT start over — resume from state.",
        f"(hook source: {source})",
    ]
    if st:
        lines.append(f"stage: {st.get('stage')}  |  completed: {st.get('completed')}")
        lines.append(f"next_action: {st.get('next_action')}")
        if st.get("blocked_on"):
            lines.append(f"BLOCKED_ON (⛔ gate awaiting the user): {st.get('blocked_on')}")
        for k, v in (st.get("decisions") or {}).items():
            lines.append(f"decision: {k} = {v}")
        live = [b for b in (st.get("batches") or [])
                if b.get("status") not in ("ended", "cancelled", "completed", "done")]
        if live:
            lines.append("IN-FLIGHT BATCHES — re-poll BEFORE submitting new work:")
            for b in live:
                lines.append(f"  - {b.get('id')} [{b.get('kind')}/{b.get('stage')}] status={b.get('status')}")
        if st.get("cursors"):
            lines.append(f"cursors: {st.get('cursors')}")
        if st.get("archives"):
            lines.append(f"archives (compiler/sources/inbox/): {', '.join(st.get('archives'))}")
        lines.append(f"token_spend_estimate_usd: {st.get('token_spend_estimate_usd')}")
    else:
        lines.append("(no compiler/.compiler_state.json yet — fresh build; start at BUILD_PROMPT.md §13)")

    tail = H.journal_tail(3000)
    if tail:
        lines.append("--- JOURNAL.md (tail) ---")
        lines.append(tail)

    ctx = "\n".join(lines)
    if len(ctx) > 9500:
        ctx = ctx[:9500] + "\n…[truncated to fit the 10k additionalContext cap]"

    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "SessionStart", "additionalContext": ctx}}))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # never disrupt session entry
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": "SessionStart",
            "additionalContext": f"[SessionStart hook error: {e}] "
                                 "Read BUILD_PROMPT.md and compiler/.compiler_state.json to resume."}}))
    sys.exit(0)
