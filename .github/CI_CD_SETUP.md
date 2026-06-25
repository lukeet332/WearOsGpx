# CI/CD + AI maintenance — one-time setup

Everything here is **free** (GitHub Actions free minutes + Google AI Studio free tier).
The pipeline:

| File | Does |
|---|---|
| `dependabot.yml` | Weekly dependency PRs (Gradle catalog + Actions) |
| `workflows/ci.yml` | JVM unit tests on every PR → the required status check |
| `workflows/ai-maintenance.yml` + `scripts/ai_fix.py` | 2×/week AI fix bot → PR (model from `ai_model.json`) |
| `workflows/auto-merge.yml` | Auto-merges bot PRs once CI is green |
| `workflows/release.yml` | On a meaningful change to `main`: AI version → GitHub Release **and** Play internal-testing upload (same version), gated by the `PLAY_DEPLOY_ENABLED` variable |

## 0. Note on the models
The fix bot's active model lives in `.github/ai_model.json` (default: GitHub Models
`openai/gpt-4.1`, Gemini fallback) and is re-evaluated weekly by `ai-model-review.yml`.
The **version classifier** (`release.yml` → `scripts/gemini_version.py`) still uses Google
AI Studio's free **`gemini-2.5-flash`** (override via the `GEMINI_MODEL` env in `release.yml`).

## 1. Secrets (Settings → Secrets and variables → Actions → New repository secret)
| Secret | Needed by | How to get it |
|---|---|---|
| `GEMINI_API_KEY` | Version classifier + fix-bot fallback | aistudio.google.com → "Get API key" (free) |
| `BOT_PAT` | AI bot's PRs | A **fine-grained PAT** (see below) |
| `PLAY_SERVICE_ACCOUNT_JSON`, `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | release.yml (Play upload) | (already set). Play upload also needs the **`PLAY_DEPLOY_ENABLED=true`** repo *variable* |

**Why `BOT_PAT`?** A PR opened with the default `GITHUB_TOKEN` does **not** trigger
other workflows (GitHub anti-recursion). The AI bot's PRs must trigger `ci.yml` and
`auto-merge.yml`, so they're opened with a PAT instead.
Create it: GitHub → Settings (your account) → Developer settings → **Fine-grained
tokens** → only this repo → Repository permissions: **Contents: Read and write**,
**Pull requests: Read and write**. Paste it as the `BOT_PAT` secret.

## 2. Repository settings to toggle (Settings → Actions → General)
- **Workflow permissions → "Read and write permissions"**.
- ✅ **"Allow GitHub Actions to create and approve pull requests"** (Dependabot +
  AI bot PRs, and enabling auto-merge).

## 3. Allow auto-merge (Settings → General → Pull Requests)
- ✅ **"Allow auto-merge"**. Without this, `gh pr merge --auto` has nothing to arm.

## 4. Branch protection on `main` (Settings → Branches → Add rule, or Rulesets)
- ✅ **Require a pull request before merging**.
- ✅ **Require status checks to pass** → search and select **`unit-tests`**
  (it appears in the list only *after* `ci.yml` has run once — open any PR or push
  to `main` first, then come back and select it). Optionally tick "Require branches
  to be up to date".
- ⚠️ **Required approvals: 0.** If you require human approvals, bot PRs can't
  auto-merge (no human to approve them) — CI is the gate here. If you *want* manual
  approval, drop the `automerge` label / `auto-merge.yml` and review by hand instead.

> Auto-merge then works correctly: `--auto` waits for the required `unit-tests`
> check and merges (squash) the instant it's green.

## 5. Private → public safety
- **No `pull_request_target`, no checkout of PR code in privileged jobs.** `ci.yml`
  is `permissions: contents: read` and never touches secrets. `auto-merge.yml` only
  calls `gh` (no code checkout) using the built-in `GITHUB_TOKEN`.
- **Secrets are never exposed to fork PRs.** Once public, fork PRs run `ci.yml`
  *without* any secrets (GitHub withholds them from forks) — and it doesn't need
  any. Turn on Settings → Actions → General → **"Require approval for all outside
  collaborators"** (or "first-time contributors") so fork workflows need your
  click.
- **Model API keys can't leak.** `ai-maintenance.yml` runs **only** on `schedule`
  / `workflow_dispatch` from your repo — never on fork PRs — so `GEMINI_API_KEY` and the
  other provider keys are never available in a fork context. Keys are sent in HTTP
  headers, never a URL, and never printed.
- **Dependabot runs get a write-scoped `GITHUB_TOKEN` but NO other secrets** (GitHub
  policy). `auto-merge.yml` relies only on `GITHUB_TOKEN`, so it works for Dependabot
  without exposing `GEMINI_API_KEY`/`BOT_PAT`.
- **Bots never merge unverified code:** required `unit-tests` check gates every
  merge, and the maintenance job additionally re-runs the tests before even opening a PR.

## 5b. PR-only `main` + AI-versioned releases

- **`release.yml`** runs on every push to `main` (i.e. every PR merge): it asks Gemini
  to classify the merged commits as **major / minor / patch**, computes the next
  SemVer from the last `vX.Y.Z` tag, builds **signed release APKs** for both modules
  (`VERSION_NAME` flows into both `build.gradle.kts`), tags the commit, and publishes a
  **GitHub Release** with `WearOsGpx-mobile-vX.Y.Z.apk` + `WearOsGpx-wear-vX.Y.Z.apk`
  attached. (No Gemini key → it falls back to a conventional-commits heuristic.)
- **Blocking direct pushes to `main`** (PR-only) needs **branch protection**, which —
  like auto-merge — is **unavailable on a private Free repo** (you saw the 403:
  *"make this repository public to enable this feature"*). So:
  1. **Make the repo public** (Settings → General → Danger Zone → Change visibility)
     *or* upgrade to GitHub Pro.
  2. Run **`bash .github/scripts/enable_branch_protection.sh`** (or click the UI per
     §3–4). It sets: PR required, **no direct pushes (incl. admins)**, must pass
     `unit-tests`, 0 required approvals (so bots self-merge), linear history.
- After that, **everything — including your own changes — goes via PRs**; merging to
  `main` is what cuts a new versioned release.

## 6. First run / verifying
1. Add the secrets + toggles above.
2. Push these files to `main` → `ci.yml` runs once → select `unit-tests` in branch
   protection.
3. `Actions → AI maintenance → Run workflow` to test the bot on demand.
4. Dependabot: `Insights → Dependency graph → Dependabot` → "Check for updates".
