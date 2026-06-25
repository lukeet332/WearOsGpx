#!/usr/bin/env python3
"""
Weekly: refresh the model menu for the phone app's AI running-route chatbot in
mobile/src/main/res/raw/recommended_models.json.

Policy: the DEFAULT (first entry) is ALWAYS a Google Gemini model, so a user's single
Gemini key never breaks — an update can only move it to a newer *Gemini* model. The
remaining entries are a DIVERSE set of the best free models from OTHER providers, offered
as opt-in alternatives (the app prompts for that provider's key only if the user picks one).
Every candidate is VALIDATED with a live call against its real provider before it's written.

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
MAX_ALTERNATIVES = 3

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


def entry(pkey, model, label):
    s = PROVIDERS[pkey]
    return {"provider": s["name"], "base_url": s["base"], "key_url": s["key_url"], "model": model, "label": label}


PROMPT = """Curate the model menu for an automated RUNNING-ROUTE chatbot (it chats, asks
clarifying questions, and drives a STRICT JSON tool-calling loop). Models must be excellent
at precise instruction / JSON following, and fast.

The DEFAULT must ALWAYS be a Google Gemini model (we never change the default's provider, so
users' keys keep working) — pick the best current FREE Gemini model.
Then give up to %(n)d DIVERSE alternatives: the best FREE models from OTHER providers
(%(others)s), spanning different strengths, as opt-in options.

Current menu: %(current)s

Respond with STRICT JSON only:
{"default": {"model": "<gemini model id>", "label": "<short label>"},
 "alternatives": [{"provider": "<one of: %(other_keys)s>", "model": "<id>", "label": "<label incl. provider>"}]}
""".strip()


def main():
    if not GH_TOKEN:
        stop("No GitHub Models token — skipping.")
    if not os.environ.get(PROVIDERS["gemini"]["key_env"], "").strip():
        stop("No Gemini key to validate the default — skipping.")  # default must stay Gemini
    current = json.loads(CONFIG.read_text()) if CONFIG.exists() else {"models": []}
    cur_models = current.get("models", [])
    others = {k: v for k, v in PROVIDERS.items() if k != "gemini" and os.environ.get(v["key_env"], "").strip()}

    prompt = PROMPT % {
        "n": MAX_ALTERNATIVES, "others": ", ".join(f"{k} ({v['name']})" for k, v in others.items()) or "(none)",
        "other_keys": ", ".join(others) or "none",
        "current": json.dumps([{"provider": m.get("provider"), "model": m.get("model")} for m in cur_models]),
    }
    try:
        rec = json.loads(chat(GH_BASE, GH_TOKEN, GH_MODEL, prompt, json_mode=True))
    except Exception as e:
        stop(f"Recommendation call failed ({e.__class__.__name__}) — keeping current.")

    gem = PROVIDERS["gemini"]
    gkey = os.environ[gem["key_env"]].strip()
    # Default: validated Gemini model, else keep the current Gemini default, else fallback.
    d = rec.get("default") or {}
    dmodel = str(d.get("model", "")).strip()
    dlabel = str(d.get("label", "")).strip() or f"{dmodel} · recommended"
    if not (dmodel and validate(gem["base"], gkey, dmodel)):
        cur_default = cur_models[0] if cur_models and cur_models[0].get("provider") == gem["name"] else None
        if cur_default:
            dmodel, dlabel = cur_default["model"], cur_default.get("label", cur_default["model"])
        else:
            dmodel, dlabel = "gemini-2.5-flash", "Gemini 2.5 Flash · recommended"
    entries = [entry("gemini", dmodel, dlabel)]

    # Alternatives from other providers (diverse, validated).
    seen = {dmodel}
    for it in (rec.get("alternatives") or [])[:MAX_ALTERNATIVES + 2]:
        if not isinstance(it, dict):
            continue
        pkey = str(it.get("provider", "")).strip()
        model = str(it.get("model", "")).strip()
        label = str(it.get("label", "")).strip() or model
        if pkey not in others or not model or model in seen:
            continue
        s = others[pkey]
        if validate(s["base"], os.environ[s["key_env"]].strip(), model):
            entries.append(entry(pkey, model, label))
            seen.add(model)
            print(f"  validated {pkey}/{model}")
            if len(entries) >= 1 + MAX_ALTERNATIVES:
                break
        else:
            print(f"  dropped (failed validation) {pkey}/{model}")

    new = {"models": entries}
    if new == current:
        stop("Model menu unchanged — no update.")
    CONFIG.write_text(json.dumps(new, indent=2) + "\n")
    emit(changed="true", default_model=dmodel)
    print(f"Updated menu; default = Google Gemini / {dmodel}, {len(entries) - 1} alternative(s)")


if __name__ == "__main__":
    main()
