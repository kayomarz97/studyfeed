-- StudyFeed — canonical content-pack schema (SOURCE OF TRUTH).
-- BUILD_PROMPT.md §6a mirrors this file; if they ever disagree, THIS file wins and the app +
-- compiler are regenerated from it. Read-only at runtime; ATTACH-ed beside a separate
-- read-write progress DB (schema in spec/SPEC.md §progress) so re-importing content never
-- wipes progress. schema_version below tracks breaking changes (see spec/SPEC.md versioning).

PRAGMA user_version = 1;   -- bump on any breaking change to this DDL

-- ── exam identity + config the APP reads (this is what makes the app generic) ──
CREATE TABLE pack_manifest (
  schema_version TEXT, pack_version TEXT,
  exam_code TEXT, exam_name TEXT,               -- e.g. 'MRCP_PART1', 'MRCP(UK) Part 1'
  built_at TEXT, checksum TEXT,
  feature_flags_json TEXT, counts_json TEXT,
  importance_weights_json TEXT,                 -- calibrated weights + thresholds (audit trail)
  importance_histogram_json TEXT
);
CREATE TABLE subjects ( id INTEGER PRIMARY KEY, name TEXT, blueprint_weight REAL, ord INTEGER );
CREATE TABLE topics   ( id INTEGER PRIMARY KEY, subject_id INTEGER, name TEXT );
CREATE TABLE tag_defs ( id INTEGER PRIMARY KEY, key TEXT, label TEXT, color TEXT,
  description TEXT, ord INTEGER );

CREATE TABLE sources (
  id INTEGER PRIMARY KEY, title TEXT,
  kind TEXT,                                     -- past_paper | qbank | textbook | pearls | reference
  tier INTEGER, tier_weight REAL,
  reliability TEXT,                              -- official | recall | unknown
  publisher TEXT, year INTEGER,                  -- paper year for past_paper; NULL if unknown
  edition TEXT, file_hash TEXT,                  -- sha256 of the canonical file
  work_id INTEGER,                               -- corroboration counts DISTINCT WORKS, not files (§5b)
  origin_paths_json TEXT,                        -- every path this file was found at (zip + loose)
  dup_of_source_id INTEGER NULL,                 -- set only if kept for audit; excluded from counts
  primary_script TEXT,                           -- latin | arabic | bengali | mixed
  script_dropped_spans INTEGER DEFAULT 0,        -- how much §5g removed; audit signal
  subject_id INTEGER,                            -- books are subject-wise => default subject for body atoms
  body_start_page INTEGER,                       -- everything before this is front matter (§5i)
  back_matter_start_page INTEGER,
  usage_profile_json TEXT                        -- the book's own emphasis conventions (§5h)
);
CREATE TABLE works (                             -- canonical bibliographic work
  id INTEGER PRIMARY KEY, title TEXT, publisher TEXT
);

-- ── grounded knowledge ──
CREATE TABLE atoms (
  id INTEGER PRIMARY KEY, subject_id INTEGER, topic_id INTEGER,
  stable_key TEXT UNIQUE,                         -- content-derived (§5j); survives rebuild. NEVER anchor user data to id.
  statement TEXT, detail_md TEXT,
  source_id INTEGER, page_ref TEXT,              -- grounding (required)
  is_highlighted INTEGER DEFAULT 0, salience REAL DEFAULT 0,
  book_flagged INTEGER DEFAULT 0,                -- the BOOK's own high-yield marker (§5h)
  book_flag_kind TEXT,                           -- which convention fired: star_3 | boxed_grey | ...
  book_salience REAL DEFAULT 0,
  content_zone TEXT DEFAULT 'body',              -- front_matter | body | back_matter (§5i)
  is_boilerplate INTEGER DEFAULT 0,              -- series' shared demo content. EXCLUDED from pack
  subject_reassigned INTEGER DEFAULT 0,          -- content subject != book subject; logged, not silent
  distinct_source_count INTEGER DEFAULT 1,
  distinct_work_count INTEGER DEFAULT 1,         -- THIS drives S_corrob, not source_count (§5b)
  distinct_publisher_count INTEGER DEFAULT 1,
  pyq_hit_count INTEGER DEFAULT 0, pyq_years_json TEXT,
  pyq_trend TEXT,                                -- rising | steady | fading | none
  pyq_last_year INTEGER,                         -- most recent paper year touching this
  centrality REAL DEFAULT 0,
  importance INTEGER DEFAULT 1,                  -- 1..5  (sortable)
  importance_score REAL DEFAULT 0,               -- 0..1  continuous
  importance_signals_json TEXT,                  -- the "why" breakdown
  figure_id INTEGER NULL
);
CREATE INDEX idx_atoms_stable ON atoms(stable_key);
CREATE TABLE atom_citations (                    -- unioned on merge; source count IS the signal
  atom_id INTEGER, source_id INTEGER, page_ref TEXT
);
CREATE TABLE concepts (
  id INTEGER PRIMARY KEY, name TEXT,             -- DISPLAY name: UK spelling, as I'll sit the exam
  stable_key TEXT UNIQUE,                         -- content-derived (§5j); survives rebuild.
  canonical_key TEXT,                            -- orthographic match key (§5e) — never displayed
  subject_id INTEGER, summary_md TEXT, aliases_json TEXT,
  guideline_sensitive INTEGER DEFAULT 0,         -- 1 = answer may go stale (targets/thresholds/mgmt)
  pyq_trend TEXT, pyq_last_year INTEGER,
  centrality REAL DEFAULT 0, importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0
);
CREATE INDEX idx_concept_canonical ON concepts(canonical_key);
CREATE INDEX idx_concepts_stable ON concepts(stable_key);
CREATE TABLE concept_edges (
  id INTEGER PRIMARY KEY, src_concept_id INTEGER, dst_concept_id INTEGER,
  relation TEXT, rationale TEXT, source_id INTEGER
);

-- ── near-dup clustering (the corroboration engine) ──
CREATE TABLE dup_clusters (
  id INTEGER PRIMARY KEY, canonical_mcq_id INTEGER,
  member_count INTEGER, distinct_source_count INTEGER, distinct_work_count INTEGER,
  distinct_publisher_count INTEGER,
  is_boilerplate INTEGER DEFAULT 0,              -- verbatim + same early position across works (§5i)
  pyq_years_json TEXT, topic_id INTEGER
);
CREATE TABLE cluster_members ( cluster_id INTEGER, mcq_id INTEGER, source_id INTEGER );

-- ── pre-compiled cross-subject topic dossiers ("read up on X") ──
CREATE TABLE topic_pages (
  id INTEGER PRIMARY KEY, concept_id INTEGER, title TEXT, aliases_json TEXT,
  stable_key TEXT UNIQUE,                         -- content-derived (§5j); survives rebuild.
  body_md TEXT,                                  -- full long page, already assembled
  subjects_covered_json TEXT, figure_ids_json TEXT,
  related_mcq_ids_json TEXT, related_flashcard_ids_json TEXT,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0, importance_signals_json TEXT
);
CREATE INDEX idx_topic_pages_stable ON topic_pages(stable_key);
CREATE TABLE topic_sections (
  id INTEGER PRIMARY KEY, topic_page_id INTEGER, subject_id INTEGER,
  heading TEXT, body_md TEXT, figure_ids_json TEXT, ord INTEGER
);

-- ── playable assets (every one carries importance) ──
CREATE TABLE flashcards ( id INTEGER PRIMARY KEY, subject_id INTEGER, atom_id INTEGER,
  front_md TEXT, back_md TEXT, is_cloze INTEGER DEFAULT 0,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0, importance_signals_json TEXT );

CREATE TABLE mcqs ( id INTEGER PRIMARY KEY, subject_id INTEGER, source_id INTEGER,
  stable_key TEXT UNIQUE,                         -- content-derived (§5j); survives rebuild.
  origin TEXT,                                   -- curated | generated
  key_trust TEXT,                                -- from the ITEM, not the source tier (§5a)
  content_zone TEXT DEFAULT 'body',              -- body | back_matter. NEVER front_matter (§5i)
  is_pyq INTEGER DEFAULT 0, pyq_years_json TEXT,
  cluster_id INTEGER NULL, distinct_source_count INTEGER DEFAULT 1,
  distinct_work_count INTEGER DEFAULT 1,
  key_conflict INTEGER DEFAULT 0, key_conflict_note TEXT,
  stem_md TEXT, opt_a TEXT, opt_b TEXT, opt_c TEXT, opt_d TEXT, opt_e TEXT,
  correct_idx INTEGER, explanation_md TEXT, cognitive_level TEXT,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0, importance_signals_json TEXT,
  figure_id INTEGER NULL );
CREATE INDEX idx_mcqs_stable ON mcqs(stable_key);
CREATE TABLE mcq_distractors ( mcq_id INTEGER, option_idx INTEGER, why_wrong TEXT );

CREATE TABLE reels ( id INTEGER PRIMARY KEY, subject_id INTEGER, atom_id INTEGER,
  hook_md TEXT, detail_md TEXT,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0, importance_signals_json TEXT );

CREATE TABLE text_blobs ( id INTEGER PRIMARY KEY, subject_id INTEGER, title TEXT, body_md TEXT,
  est_read_seconds INTEGER, concept_ids_json TEXT,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0, importance_signals_json TEXT );
CREATE TABLE blob_quizzes ( id INTEGER PRIMARY KEY, blob_id INTEGER, question_md TEXT,
  answer_md TEXT, opt_a TEXT, opt_b TEXT, opt_c TEXT, opt_d TEXT, correct_idx INTEGER );

CREATE TABLE interlink_quizzes ( id INTEGER PRIMARY KEY, edge_id INTEGER, subject_id INTEGER,
  question_md TEXT, answer_md TEXT,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0 );

CREATE TABLE figures ( id INTEGER PRIMARY KEY, path TEXT, caption_md TEXT, source_id INTEGER,
  page_ref TEXT );

-- ── convenience: one row per playable card so the app pages + sorts the feed cheaply ──
CREATE TABLE feed_items ( id INTEGER PRIMARY KEY,
  item_type TEXT,   -- reel | blob | image | flashcard | mcq | interlink
  ref_id INTEGER, subject_id INTEGER,
  importance INTEGER DEFAULT 1, importance_score REAL DEFAULT 0,
  is_pyq INTEGER DEFAULT 0 );
CREATE INDEX idx_feed_importance ON feed_items(importance DESC, importance_score DESC);
CREATE INDEX idx_feed_subject    ON feed_items(subject_id, importance DESC);
CREATE INDEX idx_feed_pyq        ON feed_items(is_pyq, importance DESC);
