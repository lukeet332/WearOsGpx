#!/usr/bin/env python3
"""
Monthly self-assessment: ask the CURRENT model which model is best *today* for the AI
fix bot, choosing only from providers whose API key is configured in this repo, and — if
it recommends a change — rewrite `.github/ai_model.json` and emit decision outputs.

Because the choice is limited to providers with keys already present, every proposed
switch can auto-merge (it's validated with a live call first). To make a new provider
selectable, add its key as the repo secret named in ai_fix.PROVIDERS and pass it in the
workflow env.

Never breaks: any error / no usable recommendation -> changed=false (keep current model).
Standard library only.
"""
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ai_fix  # PROVIDERS registry + OpenAI-compatible / Gemini callers

CONFIG = (Path.cwd() / ".github" / "ai_model.json").resolve()


def emit(**kv) -> None:
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as f:
            for k, v in kv.items():
                f.write(f"{k}={v}\n")
    for k, v in kv.items():
        print(f"{k}={v}")


def stop(reason: str) -> None:
    print(reason)
    emit(changed="false")
    sys.exit(0)


def available_providers() -> dict:
    """Providers from the registry whose key env var is populated right now."""
    return {p: spec for p, spec in ai_fix.PROVIDERS.items()
            if os.environ.get(spec[1], "").strip()}


PROMPT = """You choose the LLM that powers an automated code-fix bot for a Kotlin / Android
repo (a Wear OS watch app + phone companion). The bot reads a CI failure log plus a few
source files and must return STRICT JSON edits. Pick the single BEST model available RIGHT
NOW, optimising in this order:
1. Code-quality / reasoning on Kotlin + Android / Jetpack Compose.
2. Free (or near-free) with enough capacity for ~10 fix calls per month.
3. Reliable JSON output via an OpenAI-compatible POST {base_url}/chat/completions.

You may ONLY choose from these configured providers (their keys are already set):
%(providers)s

The current configuration is: %(current)s

Respond with STRICT JSON only:
{"provider": "<one of the provider names listed above>",
 "model": "<exact model id for that provider's API>",
 "reason": "<one or two sentences: why this beats the current model for THIS use case>",
 "confidence": <0..1>}
If the CURRENT model is still the best choice, return its exact provider and model."""


def main() -> None:
    current = ai_fix.load_model_config()
    avail = available_providers()
    if not avail:
        stop("No provider keys configured — keeping current model.")

    listing = "\n".join(f"- {p} (base_url {spec[0]})" for p, spec in avail.items())
    prompt = PROMPT % {"providers": listing, "current": json.dumps({k: current[k] for k in ("provider", "model")})}

    # Ask via the current model (OpenAI-compatible), else Gemini.
    rec = None
    if os.environ.get(current["api_key_env"], "").strip():
        try:
            rec = ai_fix.call_openai_compatible(current, prompt)
        except Exception as e:
            print(f"Recommendation call failed on current model ({e.__class__.__name__}).")
    if rec is None and ai_fix.GEMINI_KEY:
        try:
            rec = ai_fix.call_gemini(prompt)
        except Exception as e:
            print(f"Recommendation call failed on Gemini ({e.__class__.__name__}).")
    if not isinstance(rec, dict):
        stop("No usable recommendation — keeping current model.")

    provider = str(rec.get("provider", "")).strip()
    model = str(rec.get("model", "")).strip()
    if provider not in avail or not model:
        stop("Recommendation not among configured providers — keeping current model.")
    if provider == current["provider"] and model == current["model"]:
        stop(f"Current model ({current['provider']}/{current['model']}) is still the best — no change.")

    # Validate the recommended model with a live call before switching.
    base_url, api_key_env = ai_fix.PROVIDERS[provider]
    try:
        test = ai_fix.call_openai_compatible(
            {"base_url": base_url, "model": model, "api_key_env": api_key_env},
            'Reply with the JSON {"ok": true} and nothing else.',
        )
        if not isinstance(test, dict):
            stop("Validation call returned unexpected output — keeping current model.")
    except Exception as e:
        stop(f"Recommended model failed a live validation call ({e.__class__.__name__}) — keeping current model.")

    comment = ""
    try:
        comment = json.loads(CONFIG.read_text()).get("_comment", "")
    except Exception:
        pass
    CONFIG.write_text(json.dumps({"provider": provider, "model": model, "_comment": comment}, indent=2) + "\n")

    reason = str(rec.get("reason", "")).replace("\n", " ").strip()[:500]
    emit(changed="true", new_provider=provider, new_model=model, reason=reason)
    print(f"Proposing switch: {current['provider']}/{current['model']} -> {provider}/{model}")


if __name__ == "__main__":
    main()
