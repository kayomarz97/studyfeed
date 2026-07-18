#!/usr/bin/env python3
"""PreCompact hook — backstop (BUILD_PROMPT.md §10b).

Fires immediately before compaction. Cannot (and must not, here) block it. State is already on
disk after every stage (§10a), so this only drops a marker into JOURNAL.md. Fast + synchronous.
"""

from __future__ import annotations

import sys

try:
    import _hooklib as H

    H.read_stdin_json()  # drain stdin
    H.append_journal_line(
        f"\n**[compacted at {H.utcnow()}]** — context compacted; disk state is the truth (§10a).")
except Exception:
    pass

sys.exit(0)
