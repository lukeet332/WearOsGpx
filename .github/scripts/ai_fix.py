#!/usr/bin/env python3
"""
Fix deprecated APIs / build & CI failures found in the Gradle/CI log and apply the
changes to the working tree.

Two models are configured in `.github/ai_model.json`:
  * primary  ("deep thinking") — the high code-quality model that does the work.
  * fallback — used only if the primary errors / is rate-limited.
Each is a {provider, model} pair; the provider's OpenAI-compatible base_url and key env
var are resolved from the PROVIDERS registry below. The monthly `ai-model-review.yml`
job re-evaluates BOTH and opens a PR if a change is warranted.

Which model actually produced the fix is written to $GITHUB_OUTPUT (model_used,
provider_used, bot_label) so the workflow can label the PR with the responsible bot.

Safety (unchanged): standard library only; no-ops on missing creds / clean log / API
failure; only OVERWRITES existing .kt/.kts/.toml files inside the repo (plus its own
AI_CONTEXT.md note), path-traversal guarded. The workflow re-runs the tests afterwards,
so a bad edit can never merge.
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

# Configured providers the bot may run on: name -> (OpenAI-compatible base_url, key env
# var). The monthly ai-model-review job picks the best primary + fallback from THIS list.
# To add a provider: add a row here and supply its key as the repo secret named below
# (and pass it in the fix/review workflow env).
PROVIDERS = {
    "github-models": ("https://models.github.ai/inference", "GH_MODELS_TOKEN"),
    "gemini":        ("https://generativelanguage.googleapis.com/v1beta/openai", "GEMINI_API_KEY"),
    "openrouter":    ("https://openrouter.ai/api/v1", "OPENROUTER_API_KEY"),
    "groq":          ("https://api.groq.com/openai/v1", "GROQ_API_KEY"),
    "mistral":       ("https://api.mistral.ai/v1", "MISTRAL_API_KEY"),
}
DEFAULT_PRIMARY = {"provider": "github-models", "model": "openai/gpt-4.1"}
DEFAULT_FALLBACK = {"provider": "gemini", "model": "gemini-2.5-flash"}


def done(msg: str) -> None:
    print(msg)
    sys.exit(0)  # always succeed; "no changes" is a valid outcome


def resolve(slot, default: dict) -> dict:
    """{provider, model} -> {provider, model, base_url, api_key_env} via PROVIDERS."""
    slot = slot if isinstance(slot, dict) else {}
    provider = slot.get("provider")
    if provider not in PROVIDERS:
        provider = default["provider"]
    model = slot.get("model")
    if not (isinstance(model, str) and model.strip()):
        model = default["model"]
    base_url, api_key_env = PROVIDERS[provider]
    return {"provider": provider, "model": model.strip(), "base_url": base_url, "api_key_env": api_key_env}


def load_model_config() -> dict:
    """Read .github/ai_model.json -> {primary: {...resolved...}, fallback: {...}}.
    Accepts the legacy flat {provider, model} shape as the primary."""
    data = {}
    try:
        if MODEL_CONFIG.exists():
            data = json.loads(MODEL_CONFIG.read_text())
    except Exception as e:
        print(f"Could not read ai_model.json ({e.__class__.__name__}); using defaults.")
    primary = data.get("primary")
    if not isinstance(primary, dict) and isinstance(data.get("provider"), str):
        primary = {"provider": data.get("provider"), "model": data.get("model")}  # legacy flat shape
    return {
        "primary": resolve(primary, DEFAULT_PRIMARY),
        "fallback": resolve(data.get("fallback"), DEFAULT_FALLBACK),
    }


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


def call_openai_compatible(slot: dict, prompt: str) -> dict:
    """Any OpenAI-compatible /chat/completions provider (GitHub Models, Gemini's OpenAI
    endpoint, OpenRouter, Groq, Mistral, …)."""
    key = os.environ.get(slot["api_key_env"], "").strip()
    if not key:
        raise RuntimeError(f"no key in env {slot['api_key_env']}")
    url = slot["base_url"].rstrip("/") + "/chat/completions"
    data = _post(url, {"Authorization": f"Bearer {key}"}, {
        "model": slot["model"],
        "temperature": 0.1,
        "response_format": {"type": "json_object"},
        "messages": [{"role": "user", "content": prompt}],
    })
    return json.loads(data["choices"][0]["message"]["content"])


def get_fix(prompt: str):
    """Try the primary model, then the fallback. None if both are unavailable."""
    cfg = load_model_config()
    for role in ("primary", "fallback"):
        slot = cfg[role]
        if not os.environ.get(slot["api_key_env"], "").strip():
            continue
        try:
            print(f"Asking {role}: {slot['provider']} ({slot['model']})…")
            result = call_openai_compatible(slot, prompt)
            record_model(slot["provider"], slot["model"])
            return result
        except Exception as e:
            print(f"{role} {slot['provider']} unavailable ({e.__class__.__name__}).")
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
    if not any(os.environ.get(cfg[r]["api_key_env"], "").strip() for r in ("primary", "fallback")):
        done("No model credentials available — skipping (no changes).")
    log = read_log()
    if not log or not re.search(r"warning:|error:|deprecat|FAILED|FAILURE|exception|unresolved", log, re.IGNORECASE):
        done("No actionable failure in the log — nothing to do.")
    files = candidate_files(log)
    if not files:
        done("No referenced source files found in the log — nothing to do.")
    result = get_fix(build_prompt(log, files))
    if result is None:
        done("Primary and fallback models both unavailable — skipping (no changes).")
    print("Fix summary:", result.get("summary", "(none)"))
    done(f"Applied {apply_changes(result)} file change(s).")


if __name__ == "__main__":
    main()
