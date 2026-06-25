#!/usr/bin/env python3
"""
Weekly: refresh the diverse FREE model menu for the phone app's AI running-route chatbot
(a strict-JSON tool-calling agent) in mobile/src/main/res/raw/recommended_models.json.

Each entry is self-describing (provider + OpenAI-compatible base_url + key page + model), so
the menu can span providers. The CURRENT model proposes a best-first, DIVERSE shortlist; each
candidate is VALIDATED with a live call against its real provider before it's written. The
FIRST entry is the recommended default — we prefer keeping it on the current default provider
so most users never need a new key; other entries can come from any provider (the app prompts
for that provider's key if the user picks one).

Standard library only; any error / no change -> changed=false (keep current).
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
MAX_ENTRIES = 4

# Candidate providers (OpenAI-compatible, free tier). The model picks among the ones we
# hold a key for; each entry we write is self-describing.
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


PROMPT = """Curate the model menu for an automated RUNNING-ROUTE chatbot. It chats with the
user, asks clarifying questions, and drives a STRICT JSON tool-calling loop (geocode, routing,
elevation) — so models must be excellent at precise instruction / JSON following, and fast.

Return a DIVERSE, best-first shortlist of up to %(n)d FREE models drawn from these providers
(we hold a key for each): %(providers)s
- The FIRST entry is the recommended default — PREFER keeping it on "%(cur_default)s" so users'
  keys keep working; only change the default if another is clearly better.
- The remaining entries should span DIFFERENT providers / strengths (genuine variety, not
  variants of one model).

Current menu: %(current)s

Respond with STRICT JSON only:
{"models": [{"provider": "<one of: %(keys)s>", "model": "<exact id>", "label": "<short label incl. provider>"}]}
""".strip()


def main():
    if not GH_TOKEN:
        stop("No GitHub Models token — skipping.")
    current = json.loads(CONFIG.read_text()) if CONFIG.exists() else {"models": []}
    cur_models = current.get("models", [])
    avail = {k: v for k, v in PROVIDERS.items() if os.environ.get(v["key_env"], "").strip()}
    if not avail:
        stop("No provider keys configured — skipping.")

    cur_default = (cur_models[0].get("provider") if cur_models else None) or "Google Gemini"
    listing = "\n".join(f"- {k} ({v['name']})" for k, v in avail.items())
    prompt = PROMPT % {
        "n": MAX_ENTRIES, "providers": listing, "keys": ", ".join(avail), "cur_default": cur_default,
        "current": json.dumps([{"provider": m.get("provider"), "model": m.get("model")} for m in cur_models]),
    }
    try:
        rec = json.loads(chat(GH_BASE, GH_TOKEN, GH_MODEL, prompt, json_mode=True))
    except Exception as e:
        stop(f"Recommendation call failed ({e.__class__.__name__}) — keeping current.")

    entries = []
    seen = set()
    for it in (rec.get("models") or [])[:MAX_ENTRIES + 2]:
        if not isinstance(it, dict):
            continue
        pkey = str(it.get("provider", "")).strip()
        model = str(it.get("model", "")).strip()
        label = str(it.get("label", "")).strip() or model
        if pkey not in avail or not model or model in seen:
            continue
        spec = avail[pkey]
        if validate(spec["base"], os.environ[spec["key_env"]].strip(), model):
            entries.append({"provider": spec["name"], "base_url": spec["base"], "key_url": spec["key_url"],
                            "model": model, "label": label})
            seen.add(model)
            print(f"  validated {pkey}/{model}")
            if len(entries) >= MAX_ENTRIES:
                break
        else:
            print(f"  dropped (failed validation) {pkey}/{model}")

    if not entries:
        stop("No recommended models validated — keeping current.")
    new = {"models": entries}
    if new == current:
        stop("Model menu unchanged — no update.")

    CONFIG.write_text(json.dumps(new, indent=2) + "\n")
    switched = (cur_models[0].get("provider") if cur_models else None) != entries[0]["provider"]
    emit(changed="true", default=entries[0]["provider"], switched=str(switched).lower())
    print(f"Updated menu; default = {entries[0]['provider']} ({entries[0]['model']})")


if __name__ == "__main__":
    main()
