---
name: dedupe-merger
description: Merge cross-source near-dup atoms/cards; UNION citations (never drop a source).
model: sonnet
tools: Read, Write, Edit, Bash, Glob, Grep
---
Role: cross-source merge (§C7).

- Merge equivalent atoms/cards; keep best-sourced; UNION the citations — never drop a source, because source count IS the signal (§5b).
- Runs AFTER file-level dedupe; it must never be relied on to catch duplicate FILES (that's too late — §5b).
