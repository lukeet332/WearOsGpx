#!/usr/bin/env python3
"""
Ask Gemini to fix deprecated Jetpack Compose / Kotlin APIs and obvious bugs found
in the Gradle lint/compile log, and apply the changes to the working tree.

Design / safety:
  * Standard library only (urllib) — no pip installs.
  * Reads the API key from the GEMINI_API_KEY env var and sends it in a header,
    never in a URL or any printed line.
  * No-ops (exit 0, no changes) if the key is missing, the log is clean, or the
    API/parse fails — so forks without the secret and "nothing to fix" weeks
    never break the workflow or open empty PRs.
  * Only OVERWRITES files that already exist and are .kt/.kts/.toml inside the
    repo (path-traversal guarded) — Gemini cannot create or escape the tree.
  * The workflow re-runs the unit tests after this, so a bad edit blocks the PR.
"""
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

MODEL = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")
API_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
REPO = Path.cwd().resolve()
MAX_FILES = 8
MAX_FILE_BYTES = 16_000
ALLOWED_SUFFIXES = (".kt", ".kts", ".toml")


def done(msg: str) -> None:
    print(msg)
    sys.exit(0)  # always succeed; "no changes" is a valid outcome


def read_log() -> str:
    for name in ("build-log.trunc.txt", "build-log.txt"):
        p = REPO / name
        if p.exists():
            return p.read_text(errors="ignore")
    return ""


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


def is_safe(f: Path) -> bool:
    try:
        f.resolve().relative_to(REPO)
    except ValueError:
        return False
    return f.suffix in ALLOWED_SUFFIXES


PROMPT_HEADER = """You are a senior Android/Kotlin engineer maintaining a Wear OS + phone app.
Below is a Gradle lint/compile log followed by the current contents of the files it
references. Fix ONLY: deprecated Jetpack Compose / Kotlin / AndroidX APIs and clear,
safe bugs. Make the SMALLEST change that resolves a warning/error. Do NOT refactor,
rename, reformat, change behaviour, or touch anything unrelated. If you are not
confident a change is correct and safe, leave that file out.

Respond with STRICT JSON only, matching:
{"summary": "<one line>", "changes": [{"path": "<repo-relative path>", "content": "<full new file contents>"}]}
Return an empty "changes" array if nothing should change. "content" must be the
COMPLETE file, not a diff. Only include files present below.
"""


def build_prompt(log: str, files: list[Path]) -> str:
    parts = [PROMPT_HEADER, "\n===== GRADLE LOG =====\n", log[-40_000:]]
    for f in files:
        rel = f.relative_to(REPO).as_posix()
        parts.append(f"\n===== FILE: {rel} =====\n")
        parts.append(f.read_text(errors="ignore"))
    return "".join(parts)


def call_gemini(prompt: str) -> dict:
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"
    body = json.dumps({
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.1, "responseMimeType": "application/json"},
    }).encode()
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": "application/json", "x-goog-api-key": API_KEY},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read())
    text = data["candidates"][0]["content"]["parts"][0]["text"]
    return json.loads(text)


def apply_changes(result: dict) -> int:
    changed = 0
    for change in result.get("changes", []):
        rel = str(change.get("path", "")).lstrip("/")
        content = change.get("content")
        if not rel or content is None:
            continue
        target = (REPO / rel).resolve()
        if not is_safe(target):
            print(f"  skip (unsafe path): {rel}")
            continue
        if not target.is_file():
            print(f"  skip (not an existing file): {rel}")
            continue
        target.write_text(content)
        print(f"  patched: {rel}")
        changed += 1
    return changed


def main() -> None:
    if not API_KEY:
        done("GEMINI_API_KEY not set — skipping (no changes).")
    log = read_log()
    if not log or not re.search(r"warning:|error:|deprecat|FAILED|FAILURE|exception|unresolved", log, re.IGNORECASE):
        done("No actionable warnings/errors in the build log — nothing to do.")
    files = candidate_files(log)
    if not files:
        done("No referenced source files found in the log — nothing to do.")
    print(f"Sending {len(files)} file(s) + log to {MODEL}…")
    try:
        result = call_gemini(build_prompt(log, files))
    except Exception as e:  # network/quota/parse — fail soft
        done(f"Gemini call failed ({e.__class__.__name__}) — skipping (no changes).")
    print("Gemini summary:", result.get("summary", "(none)"))
    n = apply_changes(result)
    done(f"Applied {n} file change(s).")


if __name__ == "__main__":
    main()
