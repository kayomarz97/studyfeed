# StudyFeed compiler

Turns a pile of **your own** study PDFs into a single, self-describing **content pack** the app
plays. It ships **no exam content** — you supply the material, from sources you own or are licensed
to use.

> Not a developer? Don't run this by hand — hand the repo to a coding assistant and follow
> **[`../AGENTS.md`](../AGENTS.md)** (Goal 2). This README is the quick reference.

## What it produces

A pack under `dist/`: `pack_<EXAM>.db` (SQLite) + `manifest.json` + a `figures/` folder. Import
that folder in the app.

## Cost & key (read first)

The heavy stages call the Anthropic **Batch API** and need your **paid** `ANTHROPIC_API_KEY` — a
subscription cannot drive them. A large exam can cost roughly **USD ~100+**. Every spending stage
**stops at a budget gate** first. Extraction, clustering, scoring and packaging are pure Python and
cost nothing.

## Run it

```bash
# 1. Key (gitignored; never commit it)
echo 'ANTHROPIC_API_KEY=your-key-here' > .env

# 2. Your material → the drop zone (gitignored)
#    put your .zip/.rar/.7z/.tar archives in sources/inbox/

# 3. Tell the compiler what they are: copy profiles/mrcp_part1.yaml to your own profile and edit
#    the subjects, source tiers, and the drops: globs that map YOUR filenames to a kind.

# 4. Build (resumable — safe to stop and rerun)
uv sync
uv run python -m studyfeed_compiler.run --list     # stages + progress; offline & free
uv run python -m studyfeed_compiler.run            # build/resume; stops at each budget gate
```

Needs [uv](https://docs.astral.sh/uv/) (it provisions Python 3.11 itself).

## Build without an API key — subscription mode

Don't have (or want to pay for) an Anthropic API key? The two AI-heavy stages (atomize prose, parse
MCQs) can run through your **coding-agent subscription** via its CLI (`claude`, `gemini`, or `codex`)
instead of the paid Batch API. Everything else in the pipeline is already token-free Python. Best for
**small / personal corpora** — a subscription will throttle at whole-exam scale.

```bash
cd compiler
uv sync
unset ANTHROPIC_API_KEY          # IMPORTANT: a set key hijacks `claude` into API-key auth and 401s

# quick end-to-end check (a few units, uses your subscription):
uv run python -m studyfeed_compiler.llm_local local-test

# run the LLM stages on your material via your subscription (bounded for a personal pack):
uv run python -m studyfeed_compiler.llm_local local-atomize 300   # prose -> grounded atoms
uv run python -m studyfeed_compiler.llm_local local-mcq 300       # question pages -> MCQs
```

Choose the CLI with `STUDYFEED_LLM_CLI=claude|gemini|codex` (default `claude`); tune parallelism with
`STUDYFEED_LLM_CONCURRENCY` (default 4). The grounded output (`work/atoms.jsonl`, `work/mcqs.jsonl`)
is identical in shape to the Batch-API path, so the remaining stages (cluster → score → package) run
unchanged. *(Folding this into the single `run` command is a planned follow-up — today you run these
LLM stages, then continue the pipeline.)*

## How it works

A resumable ~14-stage pipeline: ingest → extract (text + OCR) → atomize & ground → corroborate &
de-duplicate → past-paper analysis → concept graph → importance → generate assets → validate →
package. Every stage checkpoints to disk. See the repository **[`README.md`](../README.md)** for the
full picture and **[`../spec/SPEC.md`](../spec/SPEC.md)** for the pack contract.
