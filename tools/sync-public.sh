#!/usr/bin/env bash
# tools/sync-public.sh
# (Re)build ./public — a clean, redacted, content-free snapshot of StudyFeed fit for a PUBLIC repo.
#
#   • Copies ONLY whitelisted paths from the working tree (never the whole tree).
#   • Overlays vendor-referencing files with their proven-redacted versions from the `public` branch
#     (that redaction is hand-verified: comments + identifier renames + publisher-label values).
#   • Excludes every private note, build output, cache, secret, and byte of exam content.
#   • Idempotent. Does NOT touch the private repo's git history.
#
# After running: secret-scan ./public, then commit inside ./public (its OWN git repo) and push to
# the NEW public GitHub repo. NEVER flip the private repo's visibility. NEVER push --all / --tags.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUB="$ROOT/public"

# Files carrying redacted vendor references — sourced verbatim from the `public` branch.
# If you change any of these on `dev`, refresh the `public` branch's redaction too, or the overlay
# below reintroduces stale text. See .claude/plans/2026-07-21-public-release.md for provenance.
REDACTED_FROM_BRANCH=(
  compiler/studyfeed_compiler/bookusage.py
  compiler/studyfeed_compiler/classify.py
  compiler/studyfeed_compiler/cluster.py
  compiler/studyfeed_compiler/dedupe.py
  compiler/studyfeed_compiler/llm.py
  compiler/studyfeed_compiler/unpack.py
  compiler/studyfeed_compiler/workgroups.py
  profiles/mrcp_part1.yaml
)

# Whitelist — the ONLY things that may ship. Anything absent here never goes public.
WHITELIST=(
  README.md GET_STARTED.md AGENTS.md
  .gitignore
  settings.gradle.kts build.gradle.kts gradle.properties
  gradlew gradlew.bat gradle
  build-logic
  app/src app/build.gradle.kts app/proguard-rules.pro app/README.md
  spec profiles
  compiler/studyfeed_compiler compiler/pyproject.toml compiler/uv.lock compiler/README.md
)
# NOTE: `.github` is deliberately NOT whitelisted. The private repo's mirror workflow
# (.github/workflows/publish-public.yml) must not be published; the public repo keeps its own
# workflows (e.g. a release-APK build) added directly there.

echo "Rebuilding $PUB from working tree at $ROOT ..."
# Clean the tree but PRESERVE public/.git — public/ is its own git repo; a naive `rm -rf public/`
# would destroy the repo/history on every re-sync. Remove all top-level entries except .git.
mkdir -p "$PUB"
find "$PUB" -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +

for item in "${WHITELIST[@]}"; do
  src="$ROOT/$item"
  if [ ! -e "$src" ]; then echo "  skip (absent): $item"; continue; fi
  dest="$PUB/$item"
  mkdir -p "$(dirname "$dest")"
  if [ -d "$src" ]; then
    rsync -a \
      --exclude='__pycache__' --exclude='*.pyc' --exclude='.claude' \
      --exclude='build' --exclude='.gradle' --exclude='.DS_Store' \
      "$src/" "$dest/"
  else
    cp "$src" "$dest"
  fi
  echo "  + $item"
done

# Overlay proven-redacted versions.
for f in "${REDACTED_FROM_BRANCH[@]}"; do
  mkdir -p "$PUB/$(dirname "$f")"
  git -C "$ROOT" show "public:$f" > "$PUB/$f"
  echo "  ~ redacted: $f"
done

# ── Public-only file overrides ────────────────────────────────────────────────────────────────
# Clean public versions of files that are intentionally internal on `dev` (e.g. app/README.md,
# which documents the maintainer's machine + internal doc pointers). Anything under
# tools/public-overrides/<path> replaces public/<path>. The private repo keeps its internal copy.
OVERRIDES="$ROOT/tools/public-overrides"
if [ -d "$OVERRIDES" ]; then
  rsync -a --exclude='.DS_Store' "$OVERRIDES/" "$PUB/"
  echo "  ⇄ applied public-overrides"
fi

# ── Sanitize personal filesystem paths (Windows username, home dirs) in docs ──────────────────
# dev's app/README.md documents the maintainer's local machine (e.g. C:\Users\<name>\...). Genericize
# so no username is published. Deterministic and safe (docs only); keeps the doc useful.
if [ -f "$PUB/app/README.md" ]; then
  sed -i -E 's#[A-Za-z]:\\Users\\[^\\]+\\#%USERPROFILE%\\#g' "$PUB/app/README.md"
fi

# ── Post-build guard: prove nothing forbidden slipped through ─────────────────────────────────
echo "Guard:"
fail=0
# Personal filesystem paths that would leak a username.
if grep -rIsqE '[A-Za-z]:\\Users\\[A-Za-z0-9._-]+' "$PUB" \
   || grep -rIsqE '/home/[a-z][a-z0-9._-]+' "$PUB" \
   || grep -rIsq  '/root/' "$PUB"; then
  echo "  !! personal filesystem path (username) in output"; fail=1
fi
# Match REAL Anthropic key shapes (sk-ant-…{20,}) — not doc placeholders like "your-key-here".
if grep -rIsqE 'sk-ant-[A-Za-z0-9_-]{20,}' "$PUB"; then
  echo "  !! possible API key in output"; fail=1
fi
leak="$(grep -rIsl -e 'Pastest' -e 'PassMedicine' -e 'Passmedicine' -e 'SsAcademy' "$PUB" || true)"
if [ -n "$leak" ]; then echo "  !! vendor name leaked:"; echo "$leak" | sed 's/^/     /'; fail=1; fi
for forbidden in compiler/.env BUILD_PROMPT.md JOURNAL.md CLAUDE.md LAPTOP_SETUP.md .claude \
                 compiler/.compiler_state.json compiler/sources compiler/work compiler/dist; do
  if [ -e "$PUB/$forbidden" ]; then echo "  !! private path leaked: $forbidden"; fail=1; fi
done
if [ "$fail" -ne 0 ]; then echo "ABORT — fix the leaks above."; exit 1; fi
echo "  ok — no keys, no vendor names, no private paths, no content"
echo
echo "Done: $PUB"
echo "Next: run secret-scanner over ./public, then commit inside ./public and push to the public repo."
