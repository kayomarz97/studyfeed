# AGENTS.md — runbook for a coding assistant helping a non-technical user

You (Claude Code / Gemini CLI / Codex) are helping someone who is **not a software engineer** set
up StudyFeed. Explain every step in plain language first, then run it. Never assume prior tooling.
Confirm before anything destructive. **The app is offline by design — never add networking,
INTERNET permission, or a background component; the build will refuse it and you must not disable
that guard (`verifyOfflineGuarantees`).**

Ask the user which of these they want (they may pick one or both):

- **Goal 1 — Build & install the APP.** No API key needed. Works on any coding-assistant
  subscription.
- **Goal 2 — Build a PACK from their own study PDFs.** Needs a paid Anthropic **API key** (the
  Batch API; a subscription cannot drive it). Only do this if they explicitly have a key.

---

## Goal 1 — Build & install the app

### 1a. Check prerequisites (install what's missing, with their OK)

- **Java JDK 17 or 21** — `java -version`. If absent, install a JDK (e.g. Temurin 21).
- **Android SDK** — needed to compile. Easiest is to install **Android Studio** once (it bundles
  the SDK), or the command-line SDK tools. Set `ANDROID_HOME`/`ANDROID_SDK_ROOT`.
- **A phone or emulator** — a physical Android phone with USB debugging, or an emulator (AVD).
- Git, and this repository already cloned.

### 1b. Verify the offline guarantee (fast, free, no device)

```
./gradlew verifyOfflineGuarantees
```

Must print success. This proves the app has no network/background capability. If it fails, stop
and show the user the message — do not work around it.

### 1c. Build the app

```
./gradlew assembleDebug
```

The installable file lands at `app/build/outputs/apk/debug/app-debug.apk`. (A debug build is fine
for personal use. A signed *release* build for distribution is set up via the project's GitHub
Actions release workflow — see `.github/workflows/`.)

### 1d. Put it on the phone

- **Physical phone** (USB debugging on): `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **No adb / no cable:** copy the `.apk` to the phone and tap it; allow "install unknown apps".

### 1e. Load a pack

The app is empty until it has a pack. Tell the user: open StudyFeed → **Manage packs** →
**Import** → pick the pack **folder** (the one containing `*.db` + `manifest.json`). If they have
no pack, go to Goal 2.

---

## Goal 2 — Build a content pack from the user's own PDFs

⚠️ **Cost & key.** This runs large language-model jobs on the Anthropic **Batch API** and needs the
user's own **paid** `ANTHROPIC_API_KEY`. A subscription will not work here. For a large exam this
can cost roughly **USD ~100+**. The tool **stops at a budget gate** before spending — never bypass
it without the user's explicit yes.

### 2a. Prerequisites

- **uv** (Python package manager) — https://docs.astral.sh/uv/ . It provisions Python 3.11 itself.
- The user's **study archives** (`.zip`/`.rar`/`.7z`/`.tar`, nesting allowed).
- The user's **Anthropic API key**.

### 2b. Set the key (never hardcode it, never commit it)

Create `compiler/.env` with `ANTHROPIC_API_KEY=...`. It is gitignored. Do not print the key back.

### 2c. Drop in their material and describe it

- Put the archives in `compiler/sources/inbox/` (gitignored — their content never enters git).
- Copy `profiles/mrcp_part1.yaml` to a new profile and edit it to match **their** exam: subject
  list, source tiers, and the `drops:` globs that map **their** filenames to a kind. Read the
  comments in that file — they explain each field.

### 2d. Build (resumable — safe to stop and rerun)

```
cd compiler
uv sync
uv run python -m studyfeed_compiler.run --list     # shows stages + progress; offline & free
uv run python -m studyfeed_compiler.run            # builds/resumes; stops at each budget gate
```

The finished pack is written under `compiler/dist/` (`pack_*.db` + `manifest.json` + `figures/`).
Move that folder to the phone and import it (step 1e).

---

## House rules for you, the assistant

- **Offline is sacred.** Do not add any dependency that reaches the network into `app/`. If a build
  step wants to, stop and explain.
- **Secrets never land in git.** Keys live only in `.env`. Check before any commit.
- **No content in git.** Source PDFs, built `.db` files, and figures are gitignored — keep it so.
- **Report reality.** If a command fails, show the real output and fix the cause; don't pretend.
- **Explain in two registers** — plain first, then the technical detail — because the user is a
  clinician, not an engineer.
