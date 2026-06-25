#!/usr/bin/env python3
"""
Decide the next semantic version from the commits since the last release tag.

Asks Gemini to classify the change as major / minor / patch (it reads the commit
messages); falls back to a conventional-commits keyword heuristic if the API key
is missing or the call fails. Writes `new_version` and `bump` to $GITHUB_OUTPUT.

Inputs (env): LAST_VERSION (e.g. "1.2.3"), COMMIT_LOG (commit subjects, one per
line), GEMINI_API_KEY (optional), GEMINI_MODEL (default gemini-2.5-flash).
"""
import json
import os
import re
import sys
import urllib.request

MODEL = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")
API_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
LAST = os.environ.get("LAST_VERSION", "0.0.0").lstrip("v").strip() or "0.0.0"
COMMITS = os.environ.get("COMMIT_LOG", "").strip()


def parse(v: str) -> tuple[int, int, int]:
    m = re.match(r"(\d+)\.(\d+)\.(\d+)", v)
    return (int(m[1]), int(m[2]), int(m[3])) if m else (0, 0, 0)


def bump_version(v: str, bump: str) -> str:
    a, b, c = parse(v)
    if bump == "major":
        return f"{a + 1}.0.0"
    if bump == "minor":
        return f"{a}.{b + 1}.0"
    return f"{a}.{b}.{c + 1}"


def heuristic(commits: str) -> str:
    text = commits.lower()
    if re.search(r"breaking change|!:|^feat!|major", text, re.MULTILINE):
        return "major"
    if re.search(r"(^|\n)\s*feat(\(|:)", text):
        return "minor"
    return "patch"


def ask_gemini(commits: str) -> str:
    prompt = (
        "You decide the next Semantic Version bump for an Android app from its commit "
        "messages. Rules: 'major' = breaking/incompatible change; 'minor' = new feature, "
        "backward compatible; 'patch' = bug fix, docs, deps, chores, refactors. When unsure, "
        "prefer the smaller bump. Respond with STRICT JSON: {\"bump\":\"major|minor|patch\"}.\n\n"
        f"Commits since the last release:\n{commits or '(none)'}"
    )
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"
    body = json.dumps({
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.0, "responseMimeType": "application/json"},
    }).encode()
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": "application/json", "x-goog-api-key": API_KEY},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        text = json.loads(resp.read())["candidates"][0]["content"]["parts"][0]["text"]
    bump = json.loads(text).get("bump", "patch")
    return bump if bump in ("major", "minor", "patch") else "patch"


def main() -> None:
    bump = "patch"
    if API_KEY:
        try:
            bump = ask_gemini(COMMITS)
            print(f"Gemini chose: {bump}")
        except Exception as e:
            bump = heuristic(COMMITS)
            print(f"Gemini unavailable ({e.__class__.__name__}); heuristic chose: {bump}")
    else:
        bump = heuristic(COMMITS)
        print(f"No GEMINI_API_KEY; heuristic chose: {bump}")

    new_version = bump_version(LAST, bump)
    print(f"{LAST} --{bump}--> {new_version}")
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as fh:
            fh.write(f"new_version={new_version}\n")
            fh.write(f"bump={bump}\n")


if __name__ == "__main__":
    main()
