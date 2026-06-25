#!/usr/bin/env python3
"""
Weekly: re-scan the best FREE models per provider for the phone app's AI route generator
and refresh mobile/src/main/res/raw/recommended_models.json (the dropdown's options + the
recommended default). The current model proposes candidates; each is VALIDATED with a live
call using that provider's own key before it's kept — so we never ship a model id that
doesn't work. Providers without a configured key keep their current entries.

Standard library only; any error / no change -> changed=false (keep the current list).
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

# Providers we can re-scan (we hold a key to validate against). id -> (base_url, key env).
PROVIDERS = {
    "gemini": ("https://generativelanguage.googleapis.com/v1beta/openai", "GEMINI_API_KEY"),
    "openrouter": ("https://openrouter.ai/api/v1", "OPENROUTER_API_KEY"),
    "groq": ("https://api.groq.com/openai/v1", "GROQ_API_KEY"),
}
MAX_PER_PROVIDER = 3


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
    """True if the model id actually works on its provider (a tiny live call)."""
    try:
        return bool(chat(base, key, model, 'Reply with the single word: ok'))
    except Exception:
        return False


PROMPT = """You curate the model menu for an automated route-planning agent that must follow a
STRICT JSON tool-calling protocol. For EACH provider below, list the %(n)d best models AVAILABLE
RIGHT NOW that are FREE (or have a usable free tier) and strong at precise instruction / JSON
following and reasonably fast. Order best-first (the first becomes the recommended default).

Providers: %(providers)s
Current list (keep entries if they're still among the best): %(current)s

Respond with STRICT JSON only, exactly this shape:
{"<provider>": [{"model": "<exact model id for that provider's API>", "label": "<short label>"}]}
Only include the providers listed above.""".strip()


def main():
    if not GH_TOKEN:
        stop("No GitHub Models token — skipping.")
    current = {}
    if CONFIG.exists():
        current = json.loads(CONFIG.read_text())

    avail = {p: spec for p, spec in PROVIDERS.items() if os.environ.get(spec[1], "").strip()}
    if not avail:
        stop("No provider keys configured — skipping.")

    cur_for_prompt = {p: current.get(p, []) for p in avail}
    prompt = PROMPT % {"n": MAX_PER_PROVIDER, "providers": ", ".join(avail), "current": json.dumps(cur_for_prompt)}
    try:
        rec = json.loads(chat(GH_BASE, GH_TOKEN, GH_MODEL, prompt, json_mode=True))
    except Exception as e:
        stop(f"Recommendation call failed ({e.__class__.__name__}) — keeping current.")
    if not isinstance(rec, dict):
        stop("Recommendation wasn't an object — keeping current.")

    new = dict(current)
    for p, (base, key_env) in avail.items():
        key = os.environ[key_env].strip()
        items = rec.get(p) or []
        validated = []
        for it in items[:MAX_PER_PROVIDER]:
            if not isinstance(it, dict):
                continue
            model = str(it.get("model", "")).strip()
            label = str(it.get("label", "")).strip() or model
            if model and validate(base, key, model):
                validated.append({"model": model, "label": label})
                print(f"  {p}: validated {model}")
            elif model:
                print(f"  {p}: dropped (failed validation) {model}")
        if validated:
            new[p] = validated  # else keep current entries for this provider

    if new == current:
        stop("Recommended models unchanged — no update.")

    new["_comment"] = current.get(
        "_comment",
        "Curated model options for AI route generation, per provider. First = recommended default. "
        "Kept fresh by the weekly app-model-review CI job.",
    )
    CONFIG.write_text(json.dumps(new, indent=2) + "\n")
    changed = [p for p in avail if new.get(p) != current.get(p)]
    emit(changed="true", providers=" & ".join(changed))
    print(f"Updated providers: {', '.join(changed)}")


if __name__ == "__main__":
    main()
