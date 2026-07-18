---
name: extractor-scanned
description: Scanned/photo pages -> OCR (English-only), flag low confidence.
model: haiku
tools: Read, Write, Edit, Bash, Glob, Grep
---
Role: scanned-page OCR (§4).

- Docling OCR, language eng only (Tesseract wants ['eng'], EasyOCR wants ['en'] — do not cross them; silent failure otherwise).
- Flag low-confidence pages; never guess. Never OCR a page whose script you haven't checked (§5g). Script-runner, not a page-reader (§10c).
