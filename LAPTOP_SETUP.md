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


## PART F — read the journal, then import the pack

> **Rewritten 2026-07-17.** F1–F5 used to have you CREATE `JOURNAL_APP.md` and record a §0
> `build_env` divergence. Both are gone: the two journals were merged into a single
> `JOURNAL.md`, and PATCH 02 amended §0 so the spec and reality agree. There is no
> divergence left to log and no journal left to create — the clone already has it. If a
> session ever tells you to "fix" §0 `build_env`, it is working from the old text.

F1. Read `JOURNAL.md` — it is the single human log and the resume truth for app work.
    Read it in this order:
    - the **⛔ standing orders at the top** — they bind every session, read them FIRST;
    - the **Phase 2 — app** section at the bottom — newest last, that is where app work
      resumes and where you append new app entries by hand.

    (`compiler/studyfeed_compiler/state.py` also appends to this file, but only from the
    box, and the compiler is finished and dormant. Nothing on this laptop writes it
    automatically.)

F2. Import the content pack. It is NOT in git — it is derived from copyrighted sources and
    `compiler/dist/` is gitignored, so a fresh clone has no pack and the app has nothing to
    play. Copy it from the box:

        scp -i ~/.ssh/id_ed25519 root@<box>:/root/studyfeed_pack.tar \
            ~/projects/studyfeed/compiler/dist/
        cd ~/projects/studyfeed/compiler/dist && tar xf studyfeed_pack.tar

    That yields `pack_MRCP_PART1.db` (~135 MB), `manifest.json`, and `figures/` (1,121 PNGs).

F3. **VERIFY the pack before trusting it.** Its sha256 must equal the `checksum` field in
    `manifest.json` — a truncated transfer otherwise surfaces later as mystery corruption:

        certutil -hashfile pack_MRCP_PART1.db SHA256    # Windows
        sha256sum pack_MRCP_PART1.db                    # Linux/macOS

    If it does not match, STOP and re-transfer. On match, delete `studyfeed_pack.tar`.

F4. Never commit the pack, the figures, or the manifest — `.gitignore` already covers
    `compiler/dist/`. Confirm with `git status` that they do not appear.

F5. Do not push during setup. Nothing gets pushed without an explicit ask (standing order
    #4 in `JOURNAL.md`).


## REPORT BACK

- My OS and Android Studio version
- `claude --version` output
- Whether `~/.claude/CLAUDE.md` + the 7 agents installed from claude-master
- Whether Ctrl+Esc actually opened Claude inside the IDE (the real proof)
- What `adb devices` printed
- Whether the repo is checked out on `dev`
- Whether the pack imported and its sha256 MATCHED `manifest.json`'s `checksum` (paste both)

If any step failed, say exactly which one and paste the real error. Do not paper over a
failure or call something done that you didn't verify.
