#!/usr/bin/env bash
# Auto-format the Kotlin files the AI bot just changed (the working-tree edits made by
# ai_fix.py), using ktlint. Runs ONLY on changed .kt/.kts files, so it never reformats
# the whole repo or touches code the bot didn't edit. Best-effort: ktlint fixes what it
# can; anything it can't is left for the verify/CI step — formatting never fails the bot.
set -uo pipefail

KTLINT_VERSION="1.5.0"

# Changed Kotlin files in the working tree (ai_fix.py overwrites existing files in place).
mapfile -t FILES < <(git diff --name-only -- '*.kt' '*.kts')
if [ "${#FILES[@]}" -eq 0 ]; then
  echo "No changed Kotlin files to format."
  exit 0
fi

if [ ! -x ./ktlint ]; then
  echo "Downloading ktlint ${KTLINT_VERSION}…"
  curl -sSLo ktlint "https://github.com/pinterest/ktlint/releases/download/${KTLINT_VERSION}/ktlint" || {
    echo "ktlint download failed — skipping formatting (not fatal)."; exit 0; }
  chmod +x ktlint
fi

echo "Formatting ${#FILES[@]} changed Kotlin file(s):"
printf '  %s\n' "${FILES[@]}"
# -F auto-formats in place; exits non-zero if unfixable issues remain — that's fine,
# formatting is best-effort and CI does not gate on ktlint.
./ktlint -F "${FILES[@]}" || true
echo "Formatting done."
