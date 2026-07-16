"""Config loader — turns a profile YAML into typed access + resolves paths + model routing.

Everything exam-specific enters through the profile (BUILD_PROMPT.md §9). The compiler code never
hardcodes MRCP anything. Model routing here is for the Batch API transport (§10d): Haiku does
mechanical cleanup, Sonnet compiles/generates, Opus judges. (The Claude Code *subagents* in
.claude/agents/ carry their own frontmatter model — that is a separate axis.)
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

COMPILER_DIR: Path = Path(__file__).resolve().parents[1]
REPO_ROOT: Path = Path(__file__).resolve().parents[2]
PROFILES_DIR: Path = REPO_ROOT / "profiles"
SOURCES_INBOX: Path = COMPILER_DIR / "sources" / "inbox"
USAGE_NOTES: Path = COMPILER_DIR / "sources" / "usage_notes"
WORK_DIR: Path = COMPILER_DIR / "work"
UNPACKED_DIR: Path = WORK_DIR / "unpacked"
DIST_DIR: Path = COMPILER_DIR / "dist"

# Batch API model routing (BUILD_PROMPT.md §4/§10d). Full IDs so re-runs are pinned + reproducible.
MODEL_ROUTING: dict[str, str] = {
    "mechanics": "claude-haiku-4-5-20251001",   # extraction cleanup, script filtering helpers
    "compile": "claude-sonnet-5",               # atomize, parse, generate assets
    "judge": "claude-opus-4-8",                 # calibration, cluster adjudication, QA
}


@dataclass
class Config:
    """Parsed exam profile + resolved runtime paths."""

    profile_path: Path
    raw: dict[str, Any] = field(default_factory=dict)

    # ── convenience typed views (read from raw) ──
    @property
    def exam_code(self) -> str:
        return self.raw["exam_code"]

    @property
    def exam_name(self) -> str:
        return self.raw["exam_name"]

    @property
    def importance(self) -> dict[str, Any]:
        return self.raw.get("importance", {})

    @property
    def weights(self) -> dict[str, float]:
        return self.importance.get("weights", {})

    @property
    def thresholds(self) -> dict[str, float]:
        return self.importance.get("thresholds", {})

    @property
    def floors(self) -> dict[str, Any]:
        return self.importance.get("floors", {})

    @property
    def recency_lambda(self) -> float:
        return float(self.importance.get("recency_lambda", 0.15))

    @property
    def recent_window_years(self) -> int:
        return int(self.importance.get("recent_window_years", 10))

    @property
    def source_tiers(self) -> dict[str, Any]:
        return self.raw.get("source_tiers", {})

    @property
    def drops(self) -> list[dict[str, Any]]:
        return self.raw.get("drops", [])

    @property
    def scripts(self) -> dict[str, Any]:
        return self.raw.get("scripts", {})

    @property
    def dedupe(self) -> dict[str, Any]:
        return self.raw.get("dedupe", {})

    @property
    def archives(self) -> dict[str, Any]:
        return self.raw.get("archives", {})

    @property
    def zoning(self) -> dict[str, Any]:
        return self.raw.get("zoning", {})

    @property
    def nomenclature(self) -> dict[str, Any]:
        return self.raw.get("nomenclature", {})

    @property
    def dossier(self) -> dict[str, Any]:
        return self.raw.get("dossier", {})

    @property
    def subjects(self) -> list[dict[str, Any]]:
        return self.raw.get("subjects", [])

    @property
    def tags(self) -> list[dict[str, Any]]:
        return self.raw.get("tags", [])

    @property
    def max_expanded_gb(self) -> float:
        return float(self.archives.get("max_expanded_gb", 25))

    def validate(self) -> list[str]:
        """Return a list of problems (empty == OK). Cheap structural checks, no I/O of sources."""
        problems: list[str] = []
        for key in ("exam_code", "exam_name"):
            if not self.raw.get(key):
                problems.append(f"missing required key: {key}")
        w = self.weights
        if w and abs(sum(abs(v) for v in w.values()) - 1.0) > 1e-6:
            problems.append(f"importance.weights do not sum to 1.0 (Σ|w|={sum(abs(v) for v in w.values()):.4f})")
        th = self.thresholds
        if th:
            order = [th.get("five"), th.get("four"), th.get("three"), th.get("two")]
            if any(x is None for x in order) or not all(order[i] > order[i + 1] for i in range(3)):
                problems.append(f"importance.thresholds must be strictly descending five>four>three>two: {th}")
        return problems

    @classmethod
    def load(cls, profile_path: str | Path) -> "Config":
        p = Path(profile_path)
        with open(p, "r", encoding="utf-8") as f:
            raw = yaml.safe_load(f)
        return cls(profile_path=p, raw=raw)

    @classmethod
    def for_exam(cls, exam_slug: str) -> "Config":
        return cls.load(PROFILES_DIR / f"{exam_slug}.yaml")


if __name__ == "__main__":
    import sys

    cfg = Config.for_exam(sys.argv[1] if len(sys.argv) > 1 else "mrcp_part1")
    probs = cfg.validate()
    print(f"profile: {cfg.profile_path.name} | exam: {cfg.exam_code} ({cfg.exam_name})")
    print(f"weights Σ|w| = {sum(abs(v) for v in cfg.weights.values()):.4f} | thresholds = {cfg.thresholds}")
    print(f"drops = {[d.get('glob') for d in cfg.drops]} | max_expanded_gb = {cfg.max_expanded_gb}")
    print("VALIDATION:", "OK ✅" if not probs else probs)
    raise SystemExit(0 if not probs else 1)
