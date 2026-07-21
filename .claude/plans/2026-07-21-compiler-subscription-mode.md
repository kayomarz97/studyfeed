# Plan — Compiler subscription mode (LLM via `claude`/`gemini`/`codex` CLI, no API key)

## Context (the why)
The public README now tells doctors they can build a personal pack "without an API key" using their
coding-agent subscription. To make that true through the *actual compiler* (not an ad-hoc agent), the
compiler needs a second LLM transport: instead of the paid **Batch API**, route the atomize/MCQ calls
through a subscription CLI (`claude -p`, `gemini`, `codex`). Scoped for **small/personal corpora** —
at MRCP scale a subscription would hit usage caps (why the Batch API exists).

## Key findings (verified on this box)
- `claude` CLI headless flags exist: `-p/--print`, `--append-system-prompt`, `--model`,
  `--output-format text`, `--max-budget-usd`.
- **Gotcha (root cause of a 401):** an `ANTHROPIC_API_KEY` in the environment (the compiler `.env`
  sets one) **hijacks the `claude` CLI** → it uses API-key auth and 401s instead of the claude.ai
  subscription. The backend MUST run the CLI subprocess with `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN`
  **stripped**.
- Extraction data (`work/extracted/*.jsonl` ×98 + `classification.jsonl`) is present to test against.
- A `claude` call **nested inside a Claude Code session** is flaky; live verification is done with a
  **mock CLI** here, and standalone by the user.

## Design (reuse first — llm.py is backend-agnostic except transport)
New module `compiler/studyfeed_compiler/llm_local.py` that imports the existing request builders and
parsers from `llm.py` (`build_atomize`, `build_mcq`, `build_mcq_textbook`, `_parse_atoms`,
`_parse_mcq`, `_kinds`, `MODEL_ATOMIZE`, `MODEL_MCQ`, `load_env`) — NO duplication, NO change to the
Batch-API path.
- `_cli_call(model, sys, user) -> (ok, text)`: builds argv for the configured backend, runs it via
  `subprocess.run` with a **sanitized env** (drop `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN`), user
  text on **stdin**, returns stdout. Per-call timeout; non-zero/timeout → `(False, err)`.
  - `claude`: `claude -p --output-format text --append-system-prompt <sys> --model <haiku|sonnet>`
  - `gemini` / `codex`: combine sys+user (no system flag); documented as best-effort.
- Config via env: `STUDYFEED_LLM_CLI` (claude|gemini|codex, default claude), `STUDYFEED_LLM_CLI_BIN`
  (override binary — used to inject a **mock** for tests), `STUDYFEED_LLM_CONCURRENCY` (default 4).
- `run_local(requests, kind, out_path, concurrency)`: ThreadPool over requests → `_cli_call` →
  reuse `_parse_atoms`/`_parse_mcq` → append rows to jsonl. Same output shape as `process()`.
- CLI modes in `__main__`: `local-test` (small real sample → PASS/FAIL + samples),
  `local-atomize` / `local-mcq` / `local-mcq-textbook` (bounded by `--limit`, for personal corpora).

## Verification
1. **Plumbing (here, deterministic):** a mock `claude` shell script that echoes a canned atoms JSON;
   set `STUDYFEED_LLM_CLI_BIN=<mock>`; run `run_local` over ~3 real atomize requests; assert rows>0
   and content parses. Same for a mock MCQ response.
2. **Live (here, best-effort):** one real `claude -p` call with the key unset (the backgrounded probe)
   to confirm the subscription path returns text.
3. **Standalone (user):** documented commands to run `local-test` from a normal shell (not nested).
4. `verifier` on the compiler package; `syntax-compatibility-checker` not needed (stdlib subprocess).

## Docs
- `.claude/PLAYBOOK.md`: the `ANTHROPIC_API_KEY`-hijacks-`claude` gotcha + the subscription-mode usage.
- `compiler/README.md` + `AGENTS.md`: a "no API key — subscription mode" section with the exact
  commands; keep the honest scope (personal corpora; big packs → Batch API).

## Out of scope (flag, don't silently skip)
- Full run.py DAG wiring of subscription mode (this adds the transport + CLI entry points; wiring the
  resumable stage graph to auto-select it is a follow-up).
- Concept-graph / generation stages (C8/C11) — same pattern later if wanted.
