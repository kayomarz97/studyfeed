# Plan — Real app screenshots on the public GitHub repo (2026-07-22)

## Goal
Publish real screenshots of the live StudyFeed app (all key pages) on GitHub so people can see
what it looks like before installing.

## Hard constraints
- **No copyrighted / vendor content.** Do NOT screenshot the real MRCP pack — it contains
  copyrighted question text. Use a **synthetic demo pack** with original, generic, correct content.
- **Exam-agnostic:** demo exam label = "StudyFeed Demo" (never "MRCP").
- The public repo is an **auto-mirror** of `main` (`tools/sync-public.sh` wipes + rebuilds from a
  whitelist + `tools/public-overrides/`). So screenshots must live in `tools/public-overrides/` and
  be referenced from `README.md` to survive the mirror; they publish when `dev`→`main` is merged.

## Steps
1. **Build a synthetic demo pack** (`spec/schema.sql` full schema, all capabilities populated):
   subjects (Cardiology/Neurology/Respiratory), ~10 MCQs (some PYQ, with explanations + distractor
   why-wrong), ~5 flashcards (some cloze), ~4 reels, ~3 passages + quizzes, concepts + edges +
   interlink quizzes (for the map), ~3 topic pages + sections (for the library/dossier), feed_items
   for every playable item. Content = original generic facts, correct, no vendor text.
2. **Boot emulator, install the debug APK, seed the demo pack** into the app's private dir
   (`run-as` + `filesDir/packs/DEMO/`), set active.
3. **Generate a little progress** (drive a few MCQ answers) so Home/Progress show real numbers.
4. **Capture screenshots** (screencap → pull) for: Splash, Home, MCQ (question), MCQ (answered +
   explanation), Flashcard, Reel/Passage, Why sheet, Filter sheet, Library, Dossier, Concept map,
   Progress, Settings. Save as PNGs.
5. **Wire into the repo (durable path):** put PNGs in `tools/public-overrides/docs/screenshots/`,
   add a "Screenshots" gallery to `README.md`. Commit to `dev`, push `dev`.
6. **Immediate visibility:** also push the same images + a README gallery straight to
   `kayomarz97/studyfeed-public` (I have push access), so they show now. Reconciles with the mirror
   once `main` is merged.
7. **Report** the public URLs; tell the user the one manual step (merge `dev`→`main`) that makes the
   README gallery permanent via the mirror.

## Safety
- Secret-scan before any push. Screenshots are synthetic — no vendor names, no real pack content,
  no personal data. Verify the emulator status bar shows nothing personal.
