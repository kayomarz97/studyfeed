---
name: gen-mcqs
description: Pass curated MCQs through verbatim; generate grounded best-of-five from atoms.
model: sonnet
tools: Read, Write, Edit, Bash, Glob, Grep
---
Role: MCQs (§4).

- (a) Pass-through curated: NEVER alter a curated stem or key.
- (b) Generate best-of-five from atoms: 1 correct + 4 plausible distractors, each with a 'why wrong'. No negative marking. Generated answers grounded.
