"""C8a — alias normalization (BUILD_PROMPT §5e, `alias-normalizer`). ONE entity = ONE node.

Two deterministic mechanisms, IN ORDER, no LLM:
(a) orthographic `canonical_key`: casefold + ae|æ→e + oe|œ→e + -ise→-ize + -our→-or + -re→-er + strip
    punctuation. A MATCH KEY ONLY — the app always shows the UK display spelling (haemoglobin, never
    hemoglobin). Catches orthographic splits (haemoglobin/hemoglobin, oedema/edema) wholesale & free.
(b) synonym map from `profile.nomenclature.alias_hints`: the 2003 BAN→rINN switch (frusemide→furosemide),
    the adrenaline/epinephrine exception, UK↔US divergences, abbreviations — what no rule can derive.

`key(name)` returns the merge key: two entity strings are the SAME concept iff their keys are equal.
Genuinely novel unmatched look-alikes are PROPOSED to the user (never silently merged, §5e).
"""
from __future__ import annotations

import re

from .config import Config

_PUNCT = re.compile(r"[^\w\s-]")
_WS = re.compile(r"\s+")
_CONS_RE = re.compile(r"([bcdfghjklmnpqrstvwxz])re\b")   # centre→center, fibre→fiber (spare are/more/score)


def canonical_key(name: str) -> str:
    """§5e-a orthographic match key. Deterministic, reproducible, NEVER shown to the user."""
    s = (name or "").strip().lower()
    s = s.replace("æ", "ae").replace("œ", "oe")
    s = s.replace("ae", "e").replace("oe", "e")           # haemoglobin→hemoglobin, oedema→edema
    s = re.sub(r"our\b", "or", s)                          # tumour→tumor, colour→color
    s = re.sub(r"isation\b", "ization", s)
    s = re.sub(r"ise\b", "ize", s)                         # -ise→-ize
    s = _CONS_RE.sub(r"\1er", s)                           # British -re→-er
    s = _PUNCT.sub(" ", s)                                 # strip punctuation (keep word chars + hyphen)
    return _WS.sub(" ", s).strip()


class Aliases:
    def __init__(self, alias_hints: list[list[str]]):
        # merge: canonical_key(any variant) -> canonical_key(group head = preferred display form)
        self.merge: dict[str, str] = {}
        self.display: dict[str, str] = {}                 # head key -> the preferred display string
        for group in alias_hints or []:
            if not group:
                continue
            head = group[0]
            hk = canonical_key(head)
            self.display[hk] = head
            for variant in group:
                self.merge[canonical_key(variant)] = hk

    @classmethod
    def from_profile(cls, exam: str = "mrcp_part1") -> "Aliases":
        cfg = Config.for_exam(exam)
        return cls(cfg.nomenclature.get("alias_hints", []))

    def key(self, name: str) -> str:
        """Merge key: synonym-mapped orthographic key. Same entity iff equal."""
        ck = canonical_key(name)
        return self.merge.get(ck, ck)

    def prefers(self, name: str) -> str:
        """The preferred DISPLAY form if this name is in the synonym map, else the name unchanged."""
        return self.display.get(self.key(name), name)


if __name__ == "__main__":   # quick self-check on the §5e worked examples
    a = Aliases.from_profile()
    same = lambda x, y: a.key(x) == a.key(y)
    checks = [
        ("frusemide", "furosemide", True), ("lignocaine", "lidocaine", True),
        ("adrenaline", "epinephrine", True), ("noradrenaline", "norepinephrine", True),
        ("haemoglobin", "hemoglobin", True), ("oedema", "edema", True),
        ("ischaemia", "ischemia", True), ("tumour", "tumor", True), ("coeliac", "celiac", True),
        ("paracetamol", "acetaminophen", True), ("salbutamol", "albuterol", True),
        ("GTN", "glyceryl trinitrate", True), ("Adrenaline", "ADRENALINE", True),
        ("furosemide", "spironolactone", False), ("adrenaline", "ephedrine", False),
        ("haemoglobin", "haematocrit", False),
    ]
    ok = 0
    for x, y, want in checks:
        got = same(x, y)
        flag = "OK " if got == want else "FAIL"
        ok += got == want
        print(f"  [{flag}] key({x!r})={a.key(x)!r}  key({y!r})={a.key(y)!r}  same={got} want={want}")
    print(f"\n{ok}/{len(checks)} alias checks passed")
