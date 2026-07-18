"""Stage runner — the resumable DAG (BUILD_PROMPT.md §4 tail, §7, §10a).

Each stage checkpoints on success (`work/<stage>.done` + `.compiler_state.json`). A re-run skips
completed stages unless source hashes changed. Stage bodies are implemented incrementally (C1..C13);
until then they raise NotImplementedError so the orchestration is real but nothing silently no-ops.

Usage:
    python -m studyfeed_compiler.run --list
    python -m studyfeed_compiler.run --only C1
    python -m studyfeed_compiler.run --from C1        # run C1 onward
    python -m studyfeed_compiler.run                  # resume from state
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from . import state
from .config import Config, WORK_DIR


@dataclass
class Stage:
    id: str
    title: str
    fn: Callable[[Config], None]
    gate: bool = False  # True => a ⛔ human-approval gate follows this stage


def _todo(stage_id: str):
    def _fn(_cfg: Config) -> None:
        raise NotImplementedError(f"{stage_id} not implemented yet — implemented at its build step")
    return _fn


# The canonical pipeline. Titles/gates mirror BUILD_PROMPT.md §7. Bodies land per build step.
STAGES: list[Stage] = [
    Stage("C0",  "Spec + scaffold",                         _todo("C0"), gate=True),
    Stage("C1",  "Unpack -> file-dedupe -> classify",       _todo("C1"), gate=True),
    Stage("C2",  "Zone -> usage-profile -> ingest+OCR+filter", _todo("C2"), gate=True),
    Stage("C3",  "Highlights",                              _todo("C3")),
    Stage("C4",  "Atomize + ground",                        _todo("C4"), gate=True),
    Stage("C5",  "Clustering + corroboration",              _todo("C5"), gate=True),
    Stage("C6",  "PYQ analysis + trend + staleness",        _todo("C6"), gate=True),
    Stage("C7",  "Dedupe/merge across sources",             _todo("C7")),
    Stage("C8",  "Alias normalization + concept graph",     _todo("C8"), gate=True),
    Stage("C9",  "Importance scoring",                      _todo("C9"), gate=True),
    Stage("C10", "Topic dossiers",                          _todo("C10")),
    Stage("C11", "Generate assets (Batch API)",             _todo("C11"), gate=True),
    Stage("C12", "Validate + QA",                           _todo("C12")),
    Stage("C13", "Package pack_<exam>.db + figures",        _todo("C13")),
]
STAGE_BY_ID = {s.id: s for s in STAGES}


def _done_marker(stage_id: str) -> Path:
    return WORK_DIR / f"{stage_id}.done"


def is_done(stage_id: str) -> bool:
    return _done_marker(stage_id).exists()


def mark_done(stage_id: str) -> None:
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    _done_marker(stage_id).write_text(state._utcnow() + "\n", encoding="utf-8")


def run(from_stage: str | None = None, only: str | None = None, cfg: Config | None = None) -> None:
    cfg = cfg or Config.for_exam("mrcp_part1")
    problems = cfg.validate()
    if problems:
        raise SystemExit(f"profile invalid: {problems}")

    if only:
        todo = [STAGE_BY_ID[only]]
    else:
        start = 0
        if from_stage:
            start = next(i for i, s in enumerate(STAGES) if s.id == from_stage)
        todo = STAGES[start:]

    for s in todo:
        if not only and is_done(s.id):
            print(f"[skip] {s.id} {s.title} (already done)")
            continue
        print(f"[run ] {s.id} {s.title}")
        s.fn(cfg)  # raises NotImplementedError until the stage is built
        mark_done(s.id)
        nxt = STAGES[STAGES.index(s) + 1].id if STAGES.index(s) + 1 < len(STAGES) else None
        state.record_completed(s.id, next_stage=nxt, next_action=f"run {nxt}" if nxt else "done")
        state.append_journal(f"stage {s.id} ({s.title}) completed.", marker=f"[{s.id}]")
        if s.gate:
            print(f"⛔ {s.id} is a human-approval gate — stopping. Review, then continue.")
            state.update_state(blocked_on=f"approve {s.id} ({s.title}) before continuing")
            return


def main() -> None:
    ap = argparse.ArgumentParser(description="StudyFeed compiler stage runner")
    ap.add_argument("--from", dest="from_stage", default=None)
    ap.add_argument("--only", dest="only", default=None)
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--profile", default="mrcp_part1")
    args = ap.parse_args()

    if args.list:
        for s in STAGES:
            flag = "done" if is_done(s.id) else "    "
            gate = " ⛔" if s.gate else ""
            print(f"  [{flag}] {s.id:4s} {s.title}{gate}")
        return

    run(from_stage=args.from_stage, only=args.only, cfg=Config.for_exam(args.profile))


if __name__ == "__main__":
    main()
