"""Unicode-block ALLOWLIST script filter (BUILD_PROMPT.md §5g).

Drops Arabic/Bengali spans by proportion (never "strip non-ASCII"); KEEPS Greek + medical notation
(α/β/γ/µ/±/≥/→ are load-bearing). Span = line, threshold-based, so a mostly-English page keeps its
English and only the Arabic watermark line is dropped. Zero tokens. The dropped blocks come from the
profile (scripts.drop), so a future exam changes the profile, not this code.
"""

from __future__ import annotations

import unicodedata

# Named Unicode blocks. The Arabic set also covers Farsi/Persian/Urdu (no separate block, §5g).
_RANGES: dict[str, list[tuple[int, int]]] = {
    "arabic": [(0x0600, 0x06FF), (0x0750, 0x077F), (0x08A0, 0x08FF), (0xFB50, 0xFDFF), (0xFE70, 0xFEFF)],
    "bengali": [(0x0980, 0x09FF)],
}


def dropped_ranges(cfg) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    for name in (cfg.scripts.get("drop") or ["arabic", "bengali"]):
        out += _RANGES.get(name, [])
    return out


def _in_ranges(cp: int, ranges: list[tuple[int, int]]) -> bool:
    return any(a <= cp <= b for a, b in ranges)


def _is_letter(ch: str) -> bool:
    return unicodedata.category(ch).startswith("L")


def filter_text(text: str, ranges: list[tuple[int, int]], threshold: float = 0.30) -> tuple[str, list[str]]:
    """Return (kept_text, dropped_lines). A line is dropped when > threshold of its LETTERS fall in a
    dropped block. Lines with no letters (numbers/punctuation/Greek symbols) are always kept."""
    kept: list[str] = []
    dropped: list[str] = []
    for line in text.splitlines():
        letters = [c for c in line if _is_letter(c)]
        if letters:
            d = sum(1 for c in letters if _in_ranges(ord(c), ranges))
            if d / len(letters) > threshold:
                dropped.append(line)
                continue
        kept.append(line)
    return "\n".join(kept), dropped
