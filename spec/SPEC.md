# StudyFeed — Content Pack Specification (the contract)

This is the seam. The **app knows only this spec**; it learns everything exam-specific by reading a
pack at runtime. New exam = new profile + new sources → new pack; **app code and compiler code do not
change.** If anything in the app reads as MRCP-specific, it's a bug.

Authoritative files:
- **`schema.sql`** — canonical content-pack DDL (source of truth; `PRAGMA user_version` tracks breaks).
- **`manifest.schema.json`** — JSON Schema for the `manifest.json` shipped beside each pack.
- **`SCORING.md`** — the deterministic 1–5 importance model.

---

## 1. What a pack is

A pack is a directory the app imports via SAF (Storage Access Framework):

```
pack_<EXAM_CODE>/
├── pack_<EXAM_CODE>.db     # SQLite, schema.sql, opened READ-ONLY at runtime
├── manifest.json           # conforms to manifest.schema.json (redundant mirror of pack_manifest)
└── figures/                # extracted images referenced by figures.path (relative)
```

The DB carries everything playable (atoms, MCQs, flashcards, reels, blobs, dossiers, concept graph,
`feed_items`) plus the `pack_manifest` row and the exam's `subjects` / `topics` / `tag_defs`. Every
playable row carries `importance ∈ 1..5`, `importance_score ∈ [0,1]`, and `importance_signals_json`.

## 2. Two databases, never merged

Re-importing content must never wipe progress, so content and progress are separate SQLite files:

| DB | Written by | Opened at runtime | Lifetime |
|---|---|---|---|
| `pack_<EXAM>.db` | the compiler | **read-only**, `ATTACH`-ed | replaced wholesale on re-import |
| `progress_<EXAM>.db` | the app | read-write | **never wiped**; per `exam_code`; export/import (§11e) |

The app `ATTACH`es the read-only content DB beside the read-write progress DB and joins across them
(`fsrs_cards`, `mistakes`, `stars`, `item_tags` reference `feed_items`/asset ids by `(item_type, item_id)`).
Progress is namespaced per `exam_code`; the app keeps a registry of imported packs to list/switch exams.

Progress DB schema (created by the app on first import of an exam; **content importance never lives here**):

```sql
CREATE TABLE fsrs_cards ( item_type TEXT, item_id INTEGER, stability REAL, difficulty REAL, due TEXT,
  last_review TEXT, state INTEGER, reps INTEGER, lapses INTEGER, PRIMARY KEY (item_type, item_id) );
CREATE TABLE reviews ( id INTEGER PRIMARY KEY AUTOINCREMENT, item_type TEXT, item_id INTEGER,
  rating INTEGER, ts TEXT, elapsed_ms INTEGER );
CREATE TABLE mistakes ( id INTEGER PRIMARY KEY AUTOINCREMENT, item_type TEXT, item_id INTEGER,
  subject_id INTEGER, chosen_idx INTEGER, ts TEXT, resolved INTEGER DEFAULT 0 );
CREATE TABLE stars ( item_type TEXT, item_id INTEGER, ts TEXT, PRIMARY KEY (item_type, item_id) );
CREATE TABLE item_tags ( item_type TEXT, item_id INTEGER, tag_key TEXT, ts TEXT,
  PRIMARY KEY (item_type, item_id, tag_key) );
CREATE TABLE subject_mastery ( subject_id INTEGER PRIMARY KEY, mastery REAL, updated TEXT );
CREATE TABLE sessions ( id INTEGER PRIMARY KEY AUTOINCREMENT, mode TEXT, started TEXT, ended TEXT,
  seen INTEGER, correct INTEGER );
CREATE TABLE blob_reads ( blob_id INTEGER PRIMARY KEY, read_at TEXT, quizzed_at TEXT, reread_due TEXT );
CREATE TABLE feed_state ( key TEXT PRIMARY KEY, value TEXT );
CREATE TABLE settings ( key TEXT PRIMARY KEY, value TEXT );
```

`priority = importance_normalized × weakness` is composed **on the phone** (weakness from FSRS
retrievability + lapse rate). Packs stay shareable/rebuildable; personalization stays local. No runtime
generation of any kind (§runtime).

## 3. The manifest (how the app configures itself)

`pack_manifest` (one row) + the `subjects` / `tag_defs` tables are everything the app needs to render a
generic UI: exam name in the app bar, subject filter chips, tag chips, importance weights (for the "why"
sheet), feature flags. `manifest.json` is a redundant human-readable mirror validated by
`manifest.schema.json` — the app may read either; the DB row is authoritative.

## 4. Versioning policy

Two independent versions live in `pack_manifest`:

- **`schema_version`** — the shape of the DB (this `schema.sql`). Mirrors `PRAGMA user_version`. A
  breaking DDL change bumps it. The app declares the max `schema_version` it supports and **refuses to
  import a newer one** rather than crash; a compatible older pack still loads.
- **`pack_version`** — the content build (`<exam>.<major>.<minor>`), independent of schema. Re-importing
  a newer `pack_version` at the same `schema_version` preserves progress (matched by `(item_type,
  item_id)`); the compiler's packaging step upserts by stable ids so ids are durable across rebuilds.

Compatibility rule of thumb: **schema is backward-compatible within a major**; add nullable columns and
new tables freely (old apps ignore them), never repurpose or drop a column without a `schema_version`
major bump. Stable ids are a contract — the compiler must not renumber existing rows on rebuild.

## 5. Runtime invariants (the app MUST honor)

- **Offline / no tokens / no network**: no INTERNET permission, no AI SDK, no keys. Every screen renders
  pre-built pack content. Importance is read, never computed.
- **No background execution**: no services, WorkManager/JobScheduler, boot receiver, alarms, wake locks.
  Resurfacing (blob re-reads, FSRS due) happens **in-app on next open**.
- **No realtime generation**: dossiers, distractors, quiz-backs, scores — all pre-compiled.
- **AMOLED-first**: pure `#000000` default surfaces; light/dim optional.

## 6. Adding a new exam (proof the decoupling works)

1. Copy `profiles/<exam>.yaml`; edit taxonomy, tiers, weights, tags, alias hints, script rules.
2. Drop that exam's archives into `compiler/sources/inbox/`.
3. Re-run the compiler → `pack_<EXAM>.db` + `figures/` + `manifest.json`.
4. Import in the app → it configures subjects/tags/exam label from the manifest. Same app binary.

The compiler is exam-agnostic: everything exam-specific enters through the profile and exits through the
manifest. See `SCORING.md` for the importance model and `../BUILD_PROMPT.md` for the full build spec.
