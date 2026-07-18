#!/usr/bin/env python3
"""SessionEnd hook — log the stop reason (BUILD_PROMPT.md §10b).

Side effects only; cannot block termination. State is flushed after every stage, so this just
records that a session ended and why.
"""

from __future__ import annotations

import sys

try:
    import _hooklib as H

    reason = H.read_stdin_json().get("reason", "other")
    H.append_journal_line(f"\n_[session ended: {reason} at {H.utcnow()}]_")
except Exception:
    pass

sys.exit(0)
