#!/usr/bin/env python3
"""
Fix deprecated APIs / build & CI failures found in the Gradle/CI log and apply the
changes to the working tree.

The active model is read from `.github/ai_model.json` (provider, OpenAI-compatible
base_url, model, and which env var holds its key). Default = GitHub Models GPT-4.1
via the workflow GITHUB_TOKEN. The monthly `ai-model-review.yml` job can switch it to
any OpenAI-compatible provider by editing that file. **Gemini stays as an automatic
fallback** if the primary is unavailable.

Which model actually produced the fix is written to $GITHUB_OUTPUT (model_used,
provider_used, bot_label) so the workflow can label the PR with the responsible bot.

Safety (unchanged): standard library only; no-ops on missing creds / clean log /
API failure; only OVERWRITES existing .kt/.kts/.toml files inside the repo (plus its
own AI_CONTEXT.md note), path-traversal guarded. The workflow re-runs the tests
afterwards, so a bad edit can never merge.
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
MODEL_CONFIG = (REPO / ".github" / "ai_model.json").resolve()

# Sensible default if ai_model.json is missing/unreadable: GitHub Models GPT-4.1.
DEFAULT_CONFIG = {
    "provider": "github-models",
    "base_url": "https://models.github.ai/inference",
    "model": os.environ.get("GH_MODEL", "openai/gpt-4.1"),
    "api_key_env": "GH_MODELS_TOKEN",
}

GEMINI_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")


def done(msg: str) -> None:
    print(msg)
    sys.exit(0)  # always succeed; "no changes" is a valid outcome


def load_model_config() -> dict:
    cfg = dict(DEFAULT_CONFIG)
    try:
        if MODEL_CONFIG.exists():
            data = json.loads(MODEL_CONFIG.read_text())
            for k in ("provider", "base_url", "model", "api_key_env"):
                if isinstance(data.get(k), str) and data[k].strip():
                    cfg[k] = data[k].strip()
    except Exception as e:
        print(f"Could not read ai_model.json ({e.__class__.__name__}); using default model.")
    return cfg


def bot_label(model: str) -> str:
    """openai/gpt-4.1 -> gpt-4.1-bot ; gemini-2.5-flash -> gemini-2.5-flash-bot."""
    name = re.sub(r"[^A-Za-z0-9._-]", "-", model.split("/")[-1])
    return f"{name}-bot"


def record_model(provider: str, model: str) -> None:
    label = bot_label(model)
    print(f"Model used: {provider} / {model}  ({label})")
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as f:
            f.write(f"model_used={model}\nprovider_used={provider}\nbot_label={label}\n")


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
    return rf.suffix in ALLOWED_SUFFIXES or rf == CONTEXT_FILE


def candidate_files(log: str) -> list:
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


def build_prompt(log: str, files: list) -> str:
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


def call_openai_compatible(cfg: dict, prompt: str) -> dict:
    """Any OpenAI-compatible /chat/completions provider (GitHub Models, Groq, Mistral,
    OpenRouter, DeepSeek, Gemini's OpenAI endpoint, …)."""
    key = os.environ.get(cfg["api_key_env"], "").strip()
    if not key:
        raise RuntimeError(f"no key in env {cfg['api_key_env']}")
    url = cfg["base_url"].rstrip("/") + "/chat/completions"
    data = _post(url, {"Authorization": f"Bearer {key}"}, {
        "model": cfg["model"],
        "temperature": 0.1,
        "response_format": {"type": "json_object"},
        "messages": [{"role": "user", "content": prompt}],
    })
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


def get_fix(prompt: str) -> dict:
    """Configured provider first; Gemini as fallback. None if all unavailable."""
    cfg = load_model_config()
    if os.environ.get(cfg["api_key_env"], "").strip():
        try:
            print(f"Asking {cfg['provider']} ({cfg['model']})…")
            result = call_openai_compatible(cfg, prompt)
            record_model(cfg["provider"], cfg["model"])
            return result
        except Exception as e:
            print(f"{cfg['provider']} unavailable ({e.__class__.__name__}); falling back to Gemini.")
    if GEMINI_KEY:
        try:
            print(f"Asking Gemini ({GEMINI_MODEL})…")
            result = call_gemini(prompt)
            record_model("gemini", GEMINI_MODEL)
            return result
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
    cfg = load_model_config()
    if not os.environ.get(cfg["api_key_env"], "").strip() and not GEMINI_KEY:
        done("No model credentials available — skipping (no changes).")
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
