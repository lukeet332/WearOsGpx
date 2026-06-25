#!/usr/bin/env python3
"""
Fix deprecated APIs / build & CI failures found in the Gradle/CI log and apply the
changes to the working tree.

Model: heavy-lift reasoning via **GitHub Models (GPT-4.1)** using the repo's
GITHUB_TOKEN (needs `models: read`), with **Gemini as an automatic fallback** if
GitHub Models is unavailable or rate-limited. Both return the same JSON shape, so
the rest of the script is provider-agnostic.

Safety (unchanged): standard library only; no-ops on missing creds / clean log /
API failure; only OVERWRITES existing .kt/.kts/.toml files inside the repo
(path-traversal guarded). The workflow re-runs the tests afterwards, so a bad edit
can never merge.
"""
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

REPO = Path.cwd().resolve()
MAX_FILES = 8
MAX_FILE_BYTES = 16_000
ALLOWED_SUFFIXES = (".kt", ".kts", ".toml")
CONTEXT_FILE = (REPO / ".github" / "AI_CONTEXT.md").resolve()

GH_TOKEN = os.environ.get("GH_MODELS_TOKEN", "").strip()
GH_MODEL = os.environ.get("GH_MODEL", "openai/gpt-4.1")
GEMINI_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")


def done(msg: str) -> None:
    print(msg)
    sys.exit(0)  # always succeed; "no changes" is a valid outcome


def read_log() -> str:
    for name in ("build-log.trunc.txt", "build-log.txt"):
        p = REPO / name
        if p.exists():
            return p.read_text(errors="ignore")
    return ""


def is_safe(f: Path) -> bool:
    rf = f.resolve()
    try:
        rf.relative_to(REPO)
    except ValueError:
        return False
    # Source files the bot may patch, plus its own context note (append-only, see prompt).
    return rf.suffix in ALLOWED_SUFFIXES or rf == CONTEXT_FILE


def candidate_files(log: str) -> list[Path]:
    found, seen = [], set()
    for m in re.finditer(r"((?:mobile|wear)/[\w./-]+\.(?:kt|kts))", log):
        rel = m.group(1)
        if rel in seen:
            continue
        seen.add(rel)
        f = (REPO / rel).resolve()
        if f.is_file() and is_safe(f) and f.stat().st_size <= MAX_FILE_BYTES:
            found.append(f)
        if len(found) >= MAX_FILES:
            break
    return found


PROMPT_HEADER = """You are a senior Android/Kotlin engineer maintaining a Wear OS + phone app.
Read the PROJECT CONTEXT first (purpose, features, theme, conventions, gotchas) so your
fix stays on-brand and respects existing decisions. Then below is a Gradle/CI failure
log followed by the current contents of the files it references. Fix ONLY: the cause of
the failure, deprecated Jetpack Compose / Kotlin / AndroidX APIs, and clear, safe bugs.
Make the SMALLEST change that resolves it. Do NOT refactor, rename, reformat, change
behaviour, or touch anything unrelated. If you are not confident a change is correct and
safe, leave that file out.

Respond with STRICT JSON only:
{"summary": "<one line>", "changes": [{"path": "<repo-relative path>", "content": "<full new file contents>"}]}
Return an empty "changes" array if nothing should change. "content" must be the
COMPLETE file, not a diff. Only include files present below.

You MAY also return a change to `.github/AI_CONTEXT.md` ONLY to APPEND a single concise,
durable learning under its "## Learnings (appended by the bot)" section — e.g. a gotcha
you just discovered that would help a future fix. Append only: reproduce the existing
file verbatim and add your one bullet at the end. Skip it unless the learning is genuinely
reusable. A human reviews every PR, so never remove or rewrite existing context."""


def build_prompt(log: str, files: list[Path]) -> str:
    parts = [PROMPT_HEADER]
    if CONTEXT_FILE.exists():
        parts += ["\n\n===== PROJECT CONTEXT (.github/AI_CONTEXT.md) =====\n",
                  CONTEXT_FILE.read_text(errors="ignore")[:12_000]]
    parts += ["\n\n===== LOG =====\n", log[-40_000:]]
    for f in files:
        rel = f.relative_to(REPO).as_posix()
        parts.append(f"\n===== FILE: {rel} =====\n")
        parts.append(f.read_text(errors="ignore"))
    return "".join(parts)


def _post(url: str, headers: dict, payload: dict) -> dict:
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(),
        headers={**headers, "Content-Type": "application/json"}, method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read())


def call_github_models(prompt: str) -> dict:
    data = _post(
        "https://models.github.ai/inference/chat/completions",
        {"Authorization": f"Bearer {GH_TOKEN}"},
        {
            "model": GH_MODEL,
            "temperature": 0.1,
            "response_format": {"type": "json_object"},
            "messages": [{"role": "user", "content": prompt}],
        },
    )
    return json.loads(data["choices"][0]["message"]["content"])


def call_gemini(prompt: str) -> dict:
    data = _post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent",
        {"x-goog-api-key": GEMINI_KEY},
        {
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": {"temperature": 0.1, "responseMimeType": "application/json"},
        },
    )
    return json.loads(data["candidates"][0]["content"]["parts"][0]["text"])


def get_fix(prompt: str) -> dict | None:
    """GitHub Models (GPT-4.1) first; Gemini as fallback. None if all unavailable."""
    if GH_TOKEN:
        try:
            print(f"Asking GitHub Models ({GH_MODEL})…")
            return call_github_models(prompt)
        except Exception as e:  # rate-limit / error → fall through to Gemini
            print(f"GitHub Models unavailable ({e.__class__.__name__}); falling back to Gemini.")
    if GEMINI_KEY:
        try:
            print(f"Asking Gemini ({GEMINI_MODEL})…")
            return call_gemini(prompt)
        except Exception as e:
            print(f"Gemini unavailable ({e.__class__.__name__}).")
    return None


def apply_changes(result: dict) -> int:
    changed = 0
    for change in result.get("changes", []):
        rel = str(change.get("path", "")).lstrip("/")
        content = change.get("content")
        if not rel or content is None:
            continue
        target = (REPO / rel).resolve()
        if not is_safe(target):
            print(f"  skip (unsafe path): {rel}"); continue
        if not target.is_file():
            print(f"  skip (not an existing file): {rel}"); continue
        target.write_text(content)
        print(f"  patched: {rel}")
        changed += 1
    return changed


def main() -> None:
    if not GH_TOKEN and not GEMINI_KEY:
        done("No model credentials — skipping (no changes).")
    log = read_log()
    if not log or not re.search(r"warning:|error:|deprecat|FAILED|FAILURE|exception|unresolved", log, re.IGNORECASE):
        done("No actionable failure in the log — nothing to do.")
    files = candidate_files(log)
    if not files:
        done("No referenced source files found in the log — nothing to do.")
    result = get_fix(build_prompt(log, files))
    if result is None:
        done("All model providers unavailable — skipping (no changes).")
    print("Fix summary:", result.get("summary", "(none)"))
    done(f"Applied {apply_changes(result)} file change(s).")


if __name__ == "__main__":
    main()
