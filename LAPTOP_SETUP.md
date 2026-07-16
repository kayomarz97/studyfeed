# Laptop setup — Claude Code + master rules + Android Studio (Phase 2 dev machine)

Paste everything below the line into Claude on the laptop. Select-all and paste is safe —
the whole file is the prompt.

Target machine: 16GB Ryzen 5 laptop with Android Studio already installed.
This laptop builds the **Android app only**. The compiler stays on the Hetzner box.

---

I'm setting up a new dev machine: Claude Code, my master rules, and Android Studio linked.
Do this for me and VERIFY each step actually worked — don't assume, and don't move to the
next step until the current one is proven. If a step fails, STOP and tell me the real error.

MY MACHINE: 16GB RAM, Ryzen 5, Android Studio already installed.
Detect my OS and Android Studio version FIRST and report them before running anything.

This laptop is for building the **Android app only**. The Python compiler runs on a
separate Linux box and must never be set up here.

VERIFIED FACTS (from official Anthropic docs, checked 2026-07-16 — use these exactly,
do NOT improvise or recall from memory):

1. Claude Code CLI install — use the NATIVE installer (auto-updates), not npm:
   - Linux/macOS/WSL:    curl -fsSL https://claude.ai/install.sh | bash
   - Windows PowerShell: irm https://claude.ai/install.ps1 | iex
   - Windows CMD:        curl -fsSL https://claude.ai/install.cmd -o install.cmd && install.cmd && del install.cmd
2. Plugin: "Claude Code Beta" — JetBrains Marketplace, plugin ID 27310
   https://plugins.jetbrains.com/plugin/27310-claude-code-beta-
   Android Studio is officially supported (it's IntelliJ-based). Install via
   Settings → Plugins → Marketplace. Requires a FULL restart, sometimes more than one.
3. Reference docs: https://code.claude.com/docs/en/jetbrains.md
                   https://code.claude.com/docs/en/quickstart.md


## PART A — Claude Code CLI

A1. Detect and report OS + Android Studio version.
A2. Install the CLI with the correct command for my OS.
A3. Verify in a NEW shell: `claude --version` must print a version. If "command not
    found", the PATH isn't picked up — fix PATH and re-verify. Do not proceed on a
    failed check.
A4. Run `claude` once so I can complete login.


## PART B — my master rules (do this BEFORE any project work)

B1. GitHub CLI must be logged in first — both repos below are PRIVATE.
    Check `gh auth status`. If not logged in, tell me to run `! gh auth login` myself
    (it's interactive; you can't drive it). Do NOT guess or invent credentials.

B2. Bootstrap my master Claude config from my private rulebook repo:
      gh repo clone kayomarz97/claude-master ~/claude-master 2>/dev/null || git -C ~/claude-master pull
      bash ~/claude-master/install.sh

    Plain language: this downloads my personal rulebook and installs it globally on this
    device, so every Claude session here follows the same working agreement as my other
    machines. It's safe to re-run — it backs up whatever it replaces.

B3. Verify the install landed:
      - `~/.claude/CLAUDE.md` exists (the master rulebook)
      - `~/.claude/agents/` contains 7 agents
      - `~/.claude/MISTAKES.md` exists (global mistakes ledger)
    Report what you actually find. If any are missing, stop and tell me.

B4. Do **NOT** run `~/claude-master/setup-project.sh` for the studyfeed repo.
    That script scaffolds project files into a repo that lacks them — studyfeed already
    has its own `CLAUDE.md`, `.claude/INDEX.md`, and `.claude/PLAYBOOK.md` checked into
    git, and they arrive with the clone. Running it there is at best pointless.
    (Only use setup-project.sh on a brand-new project that has no `.claude/` yet.)

B5. Read `~/.claude/CLAUDE.md` now and follow it for the rest of this session and every
    session on this machine. Note especially: plan-first, no temporary patches, secrets
    only in `.env`, stage files by name (never `git add .`), never push `main`.


## PART C — Android Studio ↔ Claude Code link

C1. Install the "Claude Code Beta" plugin from Marketplace (see VERIFIED FACTS #2).
    If you cannot drive the GUI, give me exact click-by-click steps and WAIT for me
    to confirm.
C2. Have me fully restart Android Studio.
C3. Verify the link: in Android Studio, press Ctrl+Esc. Claude Code should open inside
    the IDE. This is the actual proof the setup worked — don't skip it.
    - If "Cannot launch Claude Code" appears, the CLI isn't on the IDE's PATH.
      Get the absolute path (`which claude` / `where claude`) and set it in
      Settings → Tools → Claude Code [Beta] → Claude command. Then re-test.
C4. Inside Claude Code run `/config` and set `diff tool` = `auto`, so changes render in
    Android Studio's diff viewer instead of the terminal.
C5. If Esc won't interrupt Claude in the IDE terminal:
    Settings → Tools → Terminal → uncheck "Move focus to the editor with Escape".
C6. If I'm on Windows via WSL2 and Claude Code reports "No available IDEs detected",
    that's firewall/NAT blocking — add a Windows Firewall rule for the WSL2 subnet,
    or switch WSL2 to mirrored networking (Windows 11 22H2+).


## PART D — Android toolchain

D1. Open the SDK Manager (Settings → Languages & Frameworks → Android SDK) and report
    what's installed. I need:
      - Android SDK Platform (latest stable)
      - Android SDK Build-Tools
      - Android SDK Command-line Tools  ← often missing; needed for CLI Gradle builds
      - Android SDK Platform-Tools (gives us `adb`)
    Install any that are missing.

D2. Do NOT install: the NDK, any emulator system image, or Android Auto/TV/Wear images.
    I test on a physical phone. Emulator images cost 2–4GB of RAM and fake AMOLED black
    as grey, which would make me mis-tune the app's dark theme.

D3. JDK: Android Studio ships its own JetBrains Runtime and Gradle will use it. Do not
    install a separate JDK unless something actually fails — report the bundled version.

D4. Physical device setup:
    - On my phone: Settings → About → tap Build number 7× → Developer Options →
      enable USB debugging. Tell me to do this; you can't.
    - Plug the phone in, then run `adb devices`.
    - It must list my device with status `device`. Status `unauthorized` means I haven't
      accepted the RSA prompt on the phone — tell me to accept it, then re-check.
      An empty list means the cable is charge-only or the driver is missing (Windows).

D5. Do NOT create the Android project, add dependencies, or write any app code.
    The project skeleton is stage A0 and is fully specified in `BUILD_PROMPT.md §8` —
    it has hard requirements (no INTERNET permission, no background components, a pinned
    FSRS version) that I don't want improvised. Setup ends when the toolchain is ready.


## PART E — the project repo

E1. Clone it (PRIVATE — needs the `gh auth login` from B1):
      gh repo clone kayomarz97/studyfeed ~/projects/studyfeed
      cd ~/projects/studyfeed && git checkout dev
    Confirm we're on branch `dev`, not `main`. I never push `main`.

E2. Do NOT run anything under `compiler/` — no `uv sync`, no Python deps, no
    `studyfeed_compiler` commands. That's ~9GB of tooling this laptop has no use for,
    and the compiler is mid-run on the other machine.

E3. Do NOT commit or push anything during setup.

E4. Final check: start `claude` inside `~/projects/studyfeed` and confirm it loads BOTH
    my global rulebook (`~/.claude/CLAUDE.md`) and the project's own `CLAUDE.md`, and
    that `.claude/INDEX.md` and `.claude/PLAYBOOK.md` are present from the clone.


## PART F — start the app-side journal (and log a known spec divergence)

F1. Create `JOURNAL_APP.md` at the repo root. This is the Phase 2 (app) human log, and
    it is OWNED BY THIS LAPTOP.

    Why it's a separate file rather than an entry in `JOURNAL.md`: `JOURNAL.md` is
    appended by the compiler itself — `compiler/studyfeed_compiler/state.py` is its sole
    writer, running on the other machine. If this laptop also appended to it, every sync
    would collide on the same lines. Two machines, two journals, one repo, no conflicts.

F2. Seed it with exactly this content:

    ---
    # JOURNAL_APP — Phase 2 (Android app) human log

    Companion to `JOURNAL.md` (Phase 1 / compiler, written by `state.py` on the Hetzner
    box). This file is owned by the laptop. Never append compiler progress here, and
    never append app progress to `JOURNAL.md` — that split is what keeps the two
    machines from colliding.

    ## Known divergence from BUILD_PROMPT — READ THIS BEFORE "FIXING" ANYTHING

    **Status: KNOWN, INTENTIONAL, pending a spec amendment at A0. Not a bug. Do not
    re-litigate this every session, and do NOT edit `BUILD_PROMPT.md` to resolve it.**

    `BUILD_PROMPT.md` §0 "Locked parameters" (~line 49) says:

        build_env:  hetzner_headless_claude_code   # Linux, no GUI; Gradle CLI; sideload APK

    That is no longer where the app is built. As of 2026-07-16 the Android app is built
    on a 16GB Ryzen 5 laptop with Android Studio and a physical phone.

    Why the change:
    - The box cannot run a Gradle build. It had ~2.7GB RAM available with swap 96%
      exhausted; a Gradle daemon plus the Kotlin compile daemon would trip the OOM
      killer, whose first target is the largest process — a running compiler session.
    - The box is headless. §8 A2 says the Study Feed is to be built first and "made
      excellent" — infinite pager, swipe, tap-to-flip, AMOLED dark. That cannot be
      judged without a screen and a real AMOLED panel.
    - Disk: the box was at 84% (24GB free) with 9GB already in `sources/` + `work/`.

    What survives unchanged:
    - §8 A0's "buildable headless via `./gradlew assembleDebug`" — the INTENT (the app
      must build from the CLI and must not depend on IDE-only configuration) is KEPT,
      and is satisfied by running `./gradlew assembleDebug` from the laptop terminal.
      Only the "over SSH" clause is moot.
    - §1432 `./gradlew assembleDebug` as the dev loop — still true, different machine.
    - The decoupling contract is untouched. The compiler still runs on the box and still
      emits a pack `.db`; nothing about the pack format changes.

    Open item for A0 (needs the user's explicit decision — §0 params are LOCKED):
    - Amend §0 `build_env` to reflect laptop-builds-app / box-runs-compiler.
    - Keystore (listed as an A0 gate in `.claude/plans/2026-07-15-studyfeed-kickoff.md`)
      is now generated on the laptop. It must be backed up off-machine and must never be
      committed — `*.jks` and `keystore.properties` are gitignored for exactly this
      reason ("losing these is bad, committing them is worse").

    How the pack reaches this laptop: `scp` from the box's `compiler/dist/` (gitignored —
    it is derived from copyrighted sources and must never enter git), then `adb push` or
    SAF-import onto the phone.

    ## Log

    - 2026-07-16 — Laptop provisioned as the Phase 2 build machine. Claude Code + master
      rules installed, Android Studio linked. Divergence above recorded. No app code yet;
      A0 not started.
    ---

F3. Add a line for `JOURNAL_APP.md` to `.claude/INDEX.md`'s file map, right after the
    `JOURNAL.md` row — my rules require the codebase map to be updated in the same change
    that adds a file. Keep it to one line, matching the existing style.

F4. Leave both files uncommitted for now; they'll go in with A0's first commit. Do not
    push during setup.

F5. If you ever hit the §0 `build_env` contradiction in a later session: read this
    ledger, accept it, move on. The permanent fix is a §0 amendment that only I can
    approve, and it happens at A0 — not by a session quietly editing the spec.


## REPORT BACK

- My OS and Android Studio version
- `claude --version` output
- Whether `~/.claude/CLAUDE.md` + the 7 agents installed from claude-master
- Whether Ctrl+Esc actually opened Claude inside the IDE (the real proof)
- What `adb devices` printed
- Whether the repo is checked out on `dev`
- Whether `JOURNAL_APP.md` was created and `.claude/INDEX.md` updated

If any step failed, say exactly which one and paste the real error. Do not paper over a
failure or call something done that you didn't verify.
