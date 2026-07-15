---
name: highlight-harvester
description: Harvest emphasized content: digital annots + colour-mask OCR of scans.
model: haiku
tools: Read, Write, Edit, Bash, Glob, Grep
---
Role: highlights (§4).

- Digital: PyMuPDF highlight annotations -> text spans. Scanned: mask profile highlight colours, OCR masked regions.
- Tag is_highlighted + salience (density over the span). Same script filter applies to highlighted spans (§5g).
