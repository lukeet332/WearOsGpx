#!/usr/bin/env bash
#
# Locks main to PR-only and turns on auto-merge. Run this ONCE, AFTER the repo is
# public (GitHub Free) or on GitHub Pro — these features 403 on private Free repos.
# Needs `gh` authenticated to an account with admin on the repo.
#
#   bash .github/scripts/enable_branch_protection.sh [owner/repo]
#
set -euo pipefail
REPO="${1:-lukeet332/WearOsGpx}"

echo "Enabling auto-merge / squash / delete-branch on $REPO…"
gh api -X PATCH "repos/$REPO" \
  -F allow_auto_merge=true -F allow_squash_merge=true -F delete_branch_on_merge=true >/dev/null

echo "Protecting main (PR required, no direct pushes, must pass CI / unit-tests)…"
gh api -X PUT "repos/$REPO/branches/main/protection" --input - >/dev/null <<'JSON'
{
  "required_status_checks": { "strict": false, "contexts": ["unit-tests"] },
  "enforce_admins": true,
  "required_pull_request_reviews": { "required_approving_review_count": 0 },
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "restrictions": null
}
JSON

echo "Done. main now requires a PR whose 'unit-tests' check passes; bot PRs auto-merge."
