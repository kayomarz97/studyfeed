# Getting started with StudyFeed

StudyFeed is a **fully-offline study app**. It plays a *content pack* — a single file holding
questions, flashcards, topic pages and a concept map. **You bring the pack; the app plays it.**
This repository contains the app and the pack-building tool. It ships **no exam content** — you
supply your own study material.

There are two ways in. Pick the one that fits you.

---

## Path A — Just use the app (no building, no commands)

Best for most people. You install a ready-made app and load a pack someone gives you (or one you
built with Path B).

**1. Install the app on your Android phone**
- On your phone, open this project's **Releases** page and download the latest `app-release.apk`.
- Tap the downloaded file. Android will ask permission to *install unknown apps* the first time —
  allow it for your browser/files app, then tap **Install**.
- *(The app is offline and asks for zero permissions — no internet, no accounts, no tracking.)*

**2. Load a pack**
- Copy your pack **folder** (it contains a `.db` file, a `manifest.json`, and maybe a `figures/`
  folder) onto the phone — e.g. into `Downloads`.
- Open StudyFeed → **Manage packs** → **Import** → pick that folder.
- Done. The app configures itself from the pack: its name, subjects, and every question come from
  the pack, not from the app.

**3. Study — and move between devices**
- Swipe through the feed; spaced repetition schedules what's due.
- **Back up or move your progress:** Settings → **Export progress** writes a `.zip`. On another
  device, install the app, import the same pack, then **Import progress** from that `.zip`. Your
  history follows you; re-importing a pack never wipes it.

> Don't have a pack yet? You'll need to build one from your own study material — see Path B.

---

## Path B — Build it yourself with an AI assistant

Best if you want to build the app from source and/or turn **your own** study PDFs into a pack.
You don't need to be technical — a coding assistant does the work. You **copy the block below**
into **Claude Code**, **Gemini CLI**, or **Codex**, and answer its questions.

**Before you start, tick what applies to you** (the assistant will ask):

- [ ] **I'll use my coding-assistant subscription.** Enough to *build and install the app*. No API
      key needed.
- [ ] **I have an Anthropic API key.** Only needed to *build a content pack* from your own PDFs.
      The pack builder uses the paid Anthropic **Batch API** (roughly USD ~100+ for a large exam);
      a subscription **cannot** drive it. Skip this if you only want to build the app.

**Copy this to your AI assistant:**

```
I have cloned the StudyFeed repository. Please read AGENTS.md in the repo root and follow it to
help me. I am not a software engineer — explain each step simply and run the commands for me.

My situation (I have ticked these):
- [ ] Build the APP only (I'll use my subscription, no API key)
- [ ] Also build a PACK from my own study PDFs (I have an Anthropic API key ready)

Start by checking my machine has what it needs, then walk me through it one step at a time.
```

The full runbook the assistant follows lives in **[`AGENTS.md`](AGENTS.md)**.

---

## What each path gives you

| | Path A — Use the app | Path B — Build it yourself |
|---|---|---|
| Install the app | Download an APK | Build from source (AI-assisted) |
| Get a pack | Someone gives you one | Build from your own PDFs (needs API key) |
| Technical skill | None | None (the assistant drives) |
| Cost | Free | Free for the app; API cost only to build a pack |

## Privacy, offline & content

- The installed app reaches **no network**, runs **no background work**, and keeps **no accounts**.
  This is enforced by the build itself, not left to trust.
- This repo distributes **no exam content**. Packs are built privately from material **you own or
  are licensed to use**. Respect the copyright and terms of your own sources.

New to the project? The **[`README.md`](README.md)** explains the whole design in a few minutes.
