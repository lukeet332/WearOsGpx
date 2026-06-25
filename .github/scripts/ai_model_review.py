#!/usr/bin/env python3
"""
Monthly self-assessment: ask the CURRENT model which model is best *today* for the AI
fix bot (any provider), and — if it recommends a change — rewrite `.github/ai_model.json`
and emit decision outputs for the workflow to open a PR.

Autonomy rule (set by the project owner):
  * If the recommended model runs on credentials ALREADY in the repo
    (GH_MODELS_TOKEN = GitHub Models, or GEMINI_API_KEY), and a live validation call
    succeeds -> safe to auto-merge.
  * If it needs a NEW key (api_key_env = AI_PROVIDER_KEY and that secret isn't set) ->
    needs_new_key = true: the workflow opens a review-required PR and pings the owner.

Never breaks: any error -> changed=false (keep the current model). Standard library only.
"""
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ai_fix  # reuse config loading + OpenAI-compatible / Gemini callers

ALLOWED_KEY_ENVS = ("GH_MODELS_TOKEN", "GEMINI_API_KEY", "AI_PROVIDER_KEY")
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


REVIEW_PROMPT = """You choose the LLM that powers an automated code-fix bot for a Kotlin /
Android repo (a Wear OS watch app + phone companion). The bot reads a CI failure log plus a
few source files and must return STRICT JSON edits. Pick the single BEST model available
RIGHT NOW, optimising in this order:
1. Code-quality / reasoning on Kotlin + Android / Jetpack Compose.
2. FREE (or near-free) with enough request capacity for ~10 fix calls per month.
3. Reliable JSON output via an OpenAI-compatible POST {base_url}/chat/completions with a
   Bearer token.

You may choose ANY provider, not only the current one. The current configuration is:
%(current)s

Respond with STRICT JSON only:
{"provider": "<short name>", "base_url": "<OpenAI-compatible root, e.g. https://models.github.ai/inference>",
 "model": "<exact model id for that endpoint>",
 "api_key_env": "<one of: GH_MODELS_TOKEN | GEMINI_API_KEY | AI_PROVIDER_KEY>",
 "reason": "<one or two sentences: why this beats the current model for THIS use case>",
 "confidence": <0..1>}

Rules for api_key_env:
 - GitHub Models -> "GH_MODELS_TOKEN" (already configured, free).
 - Google Gemini -> "GEMINI_API_KEY" (already configured).
 - ANY other provider (Groq, Mistral, OpenRouter, DeepSeek, Together, …) -> "AI_PROVIDER_KEY"
   (a single generic secret the owner must supply for that provider).
If the CURRENT model is still the best choice, return its exact values unchanged."""


def main() -> None:
    current = ai_fix.load_model_config()
    prompt = REVIEW_PROMPT % {"current": json.dumps(current)}

    # Ask via whatever model is currently active (OpenAI-compatible), else Gemini.
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

    # Validate / normalise the recommendation.
    need = ("provider", "base_url", "model", "api_key_env")
    if not all(isinstance(rec.get(k), str) and rec[k].strip() for k in need):
        stop("Recommendation missing fields — keeping current model.")
    if rec["api_key_env"] not in ALLOWED_KEY_ENVS:
        rec["api_key_env"] = "AI_PROVIDER_KEY"  # unknown provider -> generic slot
    if not rec["base_url"].startswith("https://"):
        stop("Recommended base_url is not https — keeping current model.")

    same = (rec["provider"] == current["provider"] and rec["model"] == current["model"]
            and rec["base_url"].rstrip("/") == current["base_url"].rstrip("/"))
    if same:
        stop(f"Current model ({current['model']}) is still the best — no change.")

    key_present = bool(os.environ.get(rec["api_key_env"], "").strip())
    needs_new_key = not key_present

    # If we DO have the key, prove the model actually works before proposing it.
    if key_present:
        try:
            test = ai_fix.call_openai_compatible(
                {"base_url": rec["base_url"], "model": rec["model"], "api_key_env": rec["api_key_env"]},
                'Reply with the JSON {"ok": true} and nothing else.',
            )
            if not isinstance(test, dict):
                stop("Validation call returned unexpected output — keeping current model.")
        except Exception as e:
            stop(f"Recommended model failed a live validation call ({e.__class__.__name__}) — keeping current model.")

    new_cfg = {
        "provider": rec["provider"].strip(),
        "base_url": rec["base_url"].strip(),
        "model": rec["model"].strip(),
        "api_key_env": rec["api_key_env"],
        "_comment": current.get("_comment", ""),
    }
    # Preserve the explanatory comment from the existing file if present.
    try:
        existing = json.loads(CONFIG.read_text())
        if isinstance(existing.get("_comment"), str):
            new_cfg["_comment"] = existing["_comment"]
    except Exception:
        pass
    CONFIG.write_text(json.dumps(new_cfg, indent=2) + "\n")

    reason = str(rec.get("reason", "")).replace("\n", " ").strip()[:500]
    emit(changed="true",
         needs_new_key=str(needs_new_key).lower(),
         new_provider=new_cfg["provider"],
         new_model=new_cfg["model"],
         api_key_env=new_cfg["api_key_env"],
         reason=reason)
    print(f"Proposing switch: {current['model']} -> {new_cfg['model']} "
          f"({'NEEDS NEW KEY' if needs_new_key else 'auto-merge OK'})")


if __name__ == "__main__":
    main()
