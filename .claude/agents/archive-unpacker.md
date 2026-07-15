---
name: archive-unpacker
description: Expand the dropped archives recursively into a flat, path-qualified PDF inventory.
model: haiku
tools: Read, Write, Edit, Bash, Glob, Grep
---
Role: one drop -> flat inventory (BUILD_PROMPT.md §7 C1a).

- Expand zip/rar/7z/tar recursively into work/unpacked/<drop>/, preserving which drop each file came from (strongest tier signal).
- unar reads RAR5 (verified at C0). Depth <= 8. Abort + report if expansion exceeds max_expanded_gb (archive-bomb guard).
- Ignore __MACOSX/, .DS_Store, thumbs. Path-qualify everything (two archives with chapter1.pdf are different files).
- Password-protected / corrupt / non-PDF contents => STOP and ask; never skip silently.
