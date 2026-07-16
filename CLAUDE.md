# CLAUDE.md — Working Agreement for StudyFeed

> The rules Claude Code follows **every session** in this repo. Kept short on purpose: a long
> file gets half-ignored. This complements the global `~/.claude/CLAUDE.md` (plan-first,
> long-term fixes, secrets, git discipline, agents, ledgers) — it does NOT repeat it.
> Only the two sections below are project-specific; everything else is inherited.

---

## Project at a glance (the WHAT)

**StudyFeed** is a fully-offline, **exam-agnostic** MCQ study app. A Python 3.11 *compiler*
(uv-managed) turns copyrighted source PDFs into a versioned SQLite *content pack*; a generic
Kotlin/Jetpack-Compose Android app (Room + FSRS-6, AMOLED-dark) plays any pack, configuring its
subjects/tags/exam label at runtime from the pack manifest. Three decoupled parts — `spec/`,
`compiler/`, `app/` — joined only by the pack format; first profile is MRCP(UK) Part 1. Tokens are
spent at build time only — the shipped app has zero network.

**Status (2026-07-17): Phase 1 COMPLETE** — the compiler is done (C0–C13) and dormant;
`compiler/dist/pack_MRCP_PART1.db` is built (26,965 playable items). **Phase 2 (the app) is the live
front: A0 done, `§8 A1` next.** Do not drive the compiler; do not invent pack data — a real pack exists.

**Two machines** (§0 `build_env`, amended by PATCH 02): the **compiler** ran on the headless Hetzner
box; the **app is built HERE, on the laptop** (Android Studio + a physical phone). The app must still
build from the CLI with no IDE-only config.

> **`BUILD_PROMPT.md` (repo root) is the AUTHORITATIVE spec — read it from disk before acting, never
> from memory.** **PATCH 02 (2026-07-17) rewrote it in place:** §7 and §13 and START HERE steps 1–4 are
> now *history — do not execute them* (they would re-run a finished kickoff). A fresh session doing app
> work goes to **§8 A1**.
>
> **Resume truth — two machines, two journals, never cross-write them:**
> - **App / this laptop → `JOURNAL_APP.md`** (also holds the ⛔ standing orders — read them first).
> - Compiler / box (dormant) → `compiler/.compiler_state.json` + `JOURNAL.md` (written by `state.py`).
>
> **State on disk is the truth, never the transcript.**

> ⚠ **The app must never contain an exam-specific string.** Every exam-facing label resolves from
> `pack_manifest` at runtime — that is what lets a future pack (MRCP Part 2, USMLE, …) play with **no
> code change** (§0 `multi_pack`). One hardcoded "MRCP" silently makes it a single-exam app.

**Codebase map:** scan `.claude/INDEX.md` FIRST — it says what every file does and how the
system works. Navigate by the map, not by exploratory greps; keep it true in the same commit
as any file add/move/repurpose.

**Directories that matter** (point, don't describe):
- `spec/` — the pack **contract**: `schema.sql` (source of truth) + `SPEC.md` + `manifest.schema.json` + `SCORING.md`.
- `profiles/` — per-exam config (`mrcp_part1.yaml`). Everything exam-specific lives here, never in code.
- `compiler/studyfeed_compiler/` — the compiler; `run.py` is the resumable stage DAG, `state.py` owns `.compiler_state.json`.
- `compiler/sources/inbox/` — the dropped archives (gitignored); `compiler/work/` scratch + `compiler/dist/` outputs (both gitignored).
- `app/` — the generic Kotlin/Compose player (Phase 2).
- `.claude/` — `agents/` (27 subagents + model routing), `hooks/` (durability), `PLAYBOOK.md` (verified API notes + mistakes ledger).

## Commands (exact — don't guess)

**App (this laptop — the live front).** ⚠ `JAVA_HOME` is set at Windows **user** level → Android
Studio's JBR 21; there is **no `java` on PATH**, and a shell may not inherit it (Git Bash typically
does not). If Gradle says "JAVA_HOME is not set", set it for the invocation rather than hardcoding it
into the committed `gradle.properties` — that would break every other machine:

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME','User')   # only if the shell lacks it
.\gradlew.bat verifyOfflineGuarantees            # THE quick check — the offline/no-background guard
.\gradlew.bat assembleDebug                      # dev loop; APK → app/build/outputs/apk/debug/
.\gradlew.bat testDebugUnitTest                  # FSRS-6 tests (add --rerun-tasks; UP-TO-DATE proves nothing)
adb install -r app/build/outputs/apk/debug/app-debug.apk   # sideload over USB (§11c)
```

`verifyOfflineGuarantees` runs automatically on every `assemble<Variant>`: a violating APK (INTERNET
permission, any background component, a networking dep) **cannot be built**. Never disable it.

**Compiler (the Hetzner box — DONE, dormant; do not run these here).** §11b: this laptop must never
`uv sync` — ~9GB of tooling it has no use for.

```bash
cd compiler && uv sync                                   # install compiler deps (provisions Python 3.11+)
uv run python -m studyfeed_compiler.run --list           # THE quick check (offline/free): stages + resume state
uv run python -m studyfeed_compiler.run                  # resume the build from .compiler_state.json
```

⚠️ The compiler spends money only at **mass-LLM stages via the Batch API** (BYOK `ANTHROPIC_API_KEY`);
every such stage stops at a ⛔ budget gate first (§10e). Extraction, clustering, scoring and packaging
are pure Python and cost **zero tokens** — and a PDF must never enter a context window (§10c).

**Branches:** work on and push `dev`; **never push `main`** — the user merges it.

---

## Everything else is global

- Golden loop (plan → read docs → check ledgers → build → verify → log stumbles),
  the non-negotiable rules, and the agent routing table live in `~/.claude/CLAUDE.md`.
- Project gotchas, verified commands, and API notes live in `.claude/PLAYBOOK.md`
  (read on demand — deliberately not auto-loaded, to save context).
- Stumbles in THIS repo → `mistake-tracker` logs them to the playbook's Mistakes ledger.
