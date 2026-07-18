---
name: script-filter
description: Drop Arabic/Farsi/Bengali spans by Unicode block; KEEP Greek + medical notation (§5g).
model: haiku
tools: Read, Write, Edit, Bash, Glob, Grep
---
Role: Unicode-block ALLOWLIST, zero tokens (§5g).

- NEVER 'strip non-ASCII' — α/β/γ/µ/±/≥/→ are load-bearing in medicine.
- Drop spans dominated (> drop_threshold of letters) by Arabic (U+0600-06FF,0750-077F,08A0-08FF,FB50-FDFF,FE70-FEFF — covers Farsi/Urdu) or Bengali (U+0980-09FF).
- Preserve Latin(+ext), Greek(U+0370-03FF), punctuation, super/subscripts, arrows, math ops, diacritics.
- Span-level + threshold-based (a page can be 95% English with an Arabic watermark). OCR English-only; detect script BEFORE OCR. Log every drop to work/script_drops.jsonl. Whole-doc non-Latin => flag + ask.
