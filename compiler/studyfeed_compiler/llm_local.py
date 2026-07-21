"""Subscription (CLI) LLM backend — build packs with NO Anthropic API key.

The Batch-API path in `llm.py` is fast and cheap for a *whole exam* corpus but needs a paid key.
This module offers the SAME atomize / MCQ-parse stages routed through a coding-agent **CLI on your
subscription** (`claude -p`, or `gemini` / `codex`) instead. It reuses `llm.py`'s request builders
and result parsers verbatim — only the *transport* differs — so the grounded output is identical in
shape (work/atoms.jsonl, work/mcqs.jsonl).

Scope: this is for **small / personal corpora** (your own notes, a topic, a question set). At full
MRCP scale a subscription would hit usage caps and be slow — that is exactly why the Batch API path
exists. Use whichever fits.

⚠ CRITICAL (verified 2026-07-21): a set `ANTHROPIC_API_KEY` (the compiler `.env` sets one) HIJACKS
the `claude` CLI — it switches to API-key auth and 401s instead of using your claude.ai subscription
login. So every CLI call here runs with `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN` STRIPPED from the
child environment. That is the whole trick to "no API key".

Config (env):
  STUDYFEED_LLM_CLI          claude | gemini | codex        (default: claude)
  STUDYFEED_LLM_CLI_BIN      override the binary/path        (default: the CLI name; used for tests)
  STUDYFEED_LLM_CONCURRENCY  parallel CLI calls              (default: 4)
  STUDYFEED_LLM_TIMEOUT      per-call timeout, seconds       (default: 240)
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Iterable

from .config import WORK_DIR
from .llm import (
    MODEL_ATOMIZE,
    MODEL_MCQ,
    _parse_atoms,
    _parse_mcq,
    build_atomize,
    build_mcq,
    build_mcq_textbook,
    _kinds,
)

# claude --model accepts short aliases; they resolve to whatever the subscription grants.
_CLI_MODEL = {MODEL_ATOMIZE: "haiku", MODEL_MCQ: "sonnet"}


def _child_env() -> dict:
    """Env for the CLI subprocess with API-key auth stripped so the subscription login is used."""
    env = dict(os.environ)
    for k in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"):
        env.pop(k, None)
    return env


def _argv_and_stdin(model: str, sys_prompt: str, user: str) -> tuple[list[str], str | None]:
    backend = os.environ.get("STUDYFEED_LLM_CLI", "claude").lower()
    binary = os.environ.get("STUDYFEED_LLM_CLI_BIN") or backend
    if backend == "claude":
        argv = [binary, "-p", "--output-format", "text", "--append-system-prompt", sys_prompt]
        alias = _CLI_MODEL.get(model)
        if alias:
            argv += ["--model", alias]
        return argv, user  # user text on stdin (avoids arg-length limits on long stitched pages)
    if backend == "gemini":
        return [binary, "-p", f"{sys_prompt}\n\n{user}"], None
    if backend == "codex":
        return [binary, "exec", f"{sys_prompt}\n\n{user}"], None
    raise ValueError(f"unknown STUDYFEED_LLM_CLI={backend!r} (want claude|gemini|codex)")


def cli_call(model: str, sys_prompt: str, user: str) -> tuple[bool, str]:
    """Run one CLI completion. Returns (ok, text_or_error). Never raises on a per-call failure."""
    argv, stdin = _argv_and_stdin(model, sys_prompt, user)
    timeout = float(os.environ.get("STUDYFEED_LLM_TIMEOUT", "240"))
    try:
        # Neutral cwd so the CLI does not load THIS project's CLAUDE.md / tools for a pure Q->A.
        r = subprocess.run(argv, input=stdin, capture_output=True, text=True,
                           timeout=timeout, env=_child_env(), cwd=tempfile.gettempdir())
    except FileNotFoundError:
        return False, f"CLI not found: {argv[0]!r} — is it installed and on PATH?"
    except subprocess.TimeoutExpired:
        return False, f"timeout after {timeout:.0f}s"
    if r.returncode != 0:
        return False, (r.stderr or r.stdout or "non-zero exit").strip()[:300]
    return True, (r.stdout or "").strip()


def run_local(requests: Iterable[dict], kind: str, out_path: Path,
              concurrency: int | None = None) -> dict:
    """Process request dicts (from llm.build_*) via the CLI, APPENDING grounded rows to out_path.

    Same output shape as llm.process(); reuses llm._parse_atoms / _parse_mcq so the grounded JSONL is
    byte-for-byte the same format as the Batch-API path."""
    requests = list(requests)
    concurrency = concurrency or int(os.environ.get("STUDYFEED_LLM_CONCURRENCY", "4"))
    n_ok = n_err = rows = 0

    def work(req: dict) -> tuple[str, bool, str]:
        p = req["params"]
        system = p["system"]
        sys_prompt = system[0]["text"] if isinstance(system, list) else system
        user = p["messages"][0]["content"]
        ok, text = cli_call(p["model"], sys_prompt, user)
        return req["custom_id"], ok, text

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "a", encoding="utf-8") as f:
        with ThreadPoolExecutor(max_workers=max(1, concurrency)) as ex:
            for cid, ok, text in ex.map(work, requests):
                if not ok:
                    n_err += 1
                    continue
                n_ok += 1
                items = _parse_atoms(cid, text) if kind == "atomize" else (
                    [m] if (m := _parse_mcq(cid, text)) else [])
                for it in items:
                    f.write(json.dumps(it, ensure_ascii=False) + "\n")
                    rows += 1
    return {"ok": n_ok, "err": n_err, "rows": rows}


# ── CLI ─────────────────────────────────────────────────────────────────────────────────────────
def _sample(reqs: list[dict], n: int) -> list[dict]:
    return reqs[:: max(1, len(reqs) // n)][:n] if reqs else []


def _run_local_test(n: int = 3) -> bool:
    """Tiny end-to-end check on real extracted data via the configured CLI (subscription). No cost gate
    because it is a handful of calls — keep n small."""
    kinds = _kinds()
    a = _sample(build_atomize(kinds), n)
    m = _sample(build_mcq(kinds), n)
    backend = os.environ.get("STUDYFEED_LLM_CLI", "claude")
    print(f"local-test via {backend!r}: {len(a)} atomize + {len(m)} mcq units", flush=True)
    tmp = Path(tempfile.mkdtemp()) / "local_test.jsonl"
    ra = run_local(a, "atomize", tmp)
    rm = run_local(m, "mcq", tmp.with_name("local_test_mcq.jsonl"))
    print(f"  atomize: {ra}", flush=True)
    print(f"  mcq:     {rm}", flush=True)
    ok = ra["err"] == 0 and rm["err"] == 0 and ra["rows"] > 0 and rm["rows"] > 0
    print(f"\nLOCAL-TEST {'PASS' if ok else 'FAIL'}", flush=True)
    return ok


def _limit_arg() -> int | None:
    for arg in sys.argv[2:]:
        if arg.isdigit():
            return int(arg)
    return None


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "local-test"
    if mode == "local-test":
        sys.exit(0 if _run_local_test() else 1)
    elif mode == "local-atomize":
        k = _kinds()
        print(run_local(build_atomize(k, _limit_arg()), "atomize", WORK_DIR / "atoms.jsonl"), flush=True)
    elif mode == "local-mcq":
        k = _kinds()
        print(run_local(build_mcq(k, _limit_arg()), "mcq", WORK_DIR / "mcqs.jsonl"), flush=True)
    elif mode == "local-mcq-textbook":
        k = _kinds()
        print(run_local(build_mcq_textbook(k, _limit_arg()), "mcq", WORK_DIR / "mcqs.jsonl"), flush=True)
    else:
        print(f"unknown mode {mode!r}; want local-test | local-atomize | local-mcq | local-mcq-textbook",
              flush=True)
        sys.exit(2)
