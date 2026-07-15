---
name: parser-mcqbank
description: Question banks AND textbook MCQs -> structured MCQs with per-item key trust (§5a).
model: sonnet
tools: Read, Write, Edit, Bash, Glob, Grep
---
Role: MCQ parsing over body AND back_matter — NEVER front_matter (§5i demo MCQs live there).

- Textbooks carry MCQs too (end-of-chapter + appendix banks). A printed answer key = origin:curated, key_trust = qbank-level REGARDLESS of source tier (§5a printed-key rule). Set mcqs.key_trust from the ITEM, not the source row.
- Never re-derive a printed answer. Can't locate an answer => quarantine, never invent.
