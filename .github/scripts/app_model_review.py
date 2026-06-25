#!/usr/bin/env python3
"""
Weekly: pick the best FREE provider + models for the phone app's AI running-route chatbot
(a strict-JSON tool-calling agent) and refresh mobile/src/main/res/raw/recommended_models.json.

The plumbing is OpenAI-compatible, so we're free to switch provider when a clearly better
free option appears — but we PREFER the current provider (so users rarely need a new key),
and every candidate model is VALIDATED with a live call against the real provider before
it's written. If the provider changes, the app prompts the user for a key for the new one.

Standard library only; any error / no change -> changed=false (keep current config).
"""
import json
import os
import sys
import urllib.request
from pathlib import Path

CONFIG = Path("mobile/src/main/res/raw/recommended_models.json")
GH_TOKEN = os.environ.get("GH_MODELS_TOKEN", "").strip()
GH_MODEL = os.environ.get("GH_MODEL", "openai/gpt-4.1")
GH_BASE = "https://models.github.ai/inference"
MAX_MODELS = 3

# Candidate providers (all OpenAI-compatible, with a free tier). name/base/key page +
# which repo secret validates them. The model chooses among the ones we hold a key for.
PROVIDERS = {
    "gemini": {"name": "Google Gemini", "base": "https://generativelanguage.googleapis.com/v1beta/openai",
               "key_url": "https://aistudio.google.com/app/apikey", "key_env": "GEMINI_API_KEY"},
    "openrouter": {"name": "OpenRouter", "base": "https://openrouter.ai/api/v1",
                   "key_url": "https://openrouter.ai/keys", "key_env": "OPENROUTER_API_KEY"},
    "groq": {"name": "Groq", "base": "https://api.groq.com/openai/v1",
             "key_url": "https://console.groq.com/keys", "key_env": "GROQ_API_KEY"},
}


def emit(**kv):
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as f:
            for k, v in kv.items():
                f.write(f"{k}={v}\n")
    for k, v in kv.items():
        print(f"{k}={v}")


def stop(reason):
    print(reason)
    emit(changed="false")
    sys.exit(0)


def chat(base, key, model, prompt, json_mode=False):
    payload = {"model": model, "temperature": 0.2, "messages": [{"role": "user", "content": prompt}]}
    if json_mode:
        payload["response_format"] = {"type": "json_object"}
    req = urllib.request.Request(
        base.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())["choices"][0]["message"]["content"]


def validate(base, key, model):
    try:
        return bool(chat(base, key, model, "Reply with the single word: ok"))
    except Exception:
        return False


PROMPT = """Pick the AI model provider for an automated RUNNING-ROUTE chatbot. It chats with the
user, asks clarifying questions, and drives a STRICT JSON tool-calling loop (geocode, routing,
elevation) — so it must be excellent at precise instruction / JSON following, and fast.

Choose ONE provider and its best models from ONLY these (all FREE-tier, we hold a key for each):
%(providers)s

Current config: provider=%(cur_provider)s, models=%(cur_models)s
STRONGLY PREFER keeping the current provider — only switch if another is CLEARLY better for this
use case, because switching makes users get a new key. List up to %(n)d models, best-first
(the first becomes the recommended default).

Respond with STRICT JSON only:
{"provider": "<one of: %(keys)s>", "models": [{"model": "<exact id>", "label": "<short label>"}]}
""".strip()


def main():
    if not GH_TOKEN:
        stop("No GitHub Models token — skipping.")
    current = json.loads(CONFIG.read_text()) if CONFIG.exists() else {}
    avail = {k: v for k, v in PROVIDERS.items() if os.environ.get(v["key_env"], "").strip()}
    if not avail:
        stop("No provider keys configured — skipping.")

    listing = "\n".join(f"- {k} ({v['name']})" for k, v in avail.items())
    prompt = PROMPT % {
        "providers": listing, "keys": ", ".join(avail), "n": MAX_MODELS,
        "cur_provider": current.get("provider", "(none)"),
        "cur_models": json.dumps([m.get("model") for m in current.get("models", [])]),
    }
    try:
        rec = json.loads(chat(GH_BASE, GH_TOKEN, GH_MODEL, prompt, json_mode=True))
    except Exception as e:
        stop(f"Recommendation call failed ({e.__class__.__name__}) — keeping current.")

    pkey = str(rec.get("provider", "")).strip()
    if pkey not in avail:
        stop(f"Recommended provider '{pkey}' not available — keeping current.")
    spec = avail[pkey]
    key = os.environ[spec["key_env"]].strip()

    validated = []
    for it in (rec.get("models") or [])[:MAX_MODELS]:
        if not isinstance(it, dict):
            continue
        model = str(it.get("model", "")).strip()
        label = str(it.get("label", "")).strip() or model
        if model and validate(spec["base"], key, model):
            validated.append({"model": model, "label": label})
            print(f"  validated {pkey}/{model}")
        elif model:
            print(f"  dropped (failed validation) {pkey}/{model}")
    if not validated:
        stop("No recommended model validated — keeping current.")

    new = {
        "_comment": current.get("_comment", "AI route-generation provider config; refreshed weekly by app-model-review."),
        "provider": spec["name"],
        "base_url": spec["base"],
        "key_url": spec["key_url"],
        "models": validated,
    }
    if new.get("provider") == current.get("provider") and new.get("models") == current.get("models"):
        stop("Provider + models unchanged — no update.")

    CONFIG.write_text(json.dumps(new, indent=2) + "\n")
    switched = new["provider"] != current.get("provider")
    emit(changed="true", provider=spec["name"], switched=str(switched).lower())
    print(f"Updated -> {spec['name']} ({'provider switched' if switched else 'models refreshed'})")


if __name__ == "__main__":
    main()
