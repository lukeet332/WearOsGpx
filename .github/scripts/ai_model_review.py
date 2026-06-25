#!/usr/bin/env python3
"""
Self-assessment: ask the current deep-thinking model which model is best *today* for
BOTH roles of the fix bot — the primary ("deep thinking") and the fallback — choosing
only from the configured providers in ai_fix.PROVIDERS. If a change to either role is
warranted, rewrite `.github/ai_model.json` and emit decision outputs for the workflow.

Every proposed pick is validated with a live call before it's written, so a model that
doesn't actually work can't be selected. Never breaks: any error / no usable / no
warranted change -> changed=false (keep current). Standard library only.
"""
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ai_fix  # PROVIDERS registry + OpenAI-compatible caller

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


PROMPT = """You configure the TWO models behind an automated code-fix bot for a Kotlin /
Android repo (a Wear OS watch app + phone companion). The bot reads a CI failure log plus a
few source files and must return STRICT JSON edits.

Two roles:
- PRIMARY ("deep thinking"): the BEST code-quality / reasoning model for Kotlin + Android /
  Jetpack Compose. It does the real work.
- FALLBACK: used only when the primary errors or is rate-limited. Prioritise reliability,
  high request capacity and speed over peak quality; ideally a DIFFERENT provider from the
  primary for resilience.

HARD CONSTRAINTS for BOTH roles: the model must be FREE to use (never a paid model) AND have
enough request-rate headroom to comfortably cover our usage — several automated runs per week
(twice-weekly maintenance, on-demand self-heal and broken-main fixes) plus this weekly review
— without realistically hitting free-tier limits. Reject any model that is paid or whose free
quota is too tight for that.

Pick the best model for EACH role, RIGHT NOW, choosing ONLY from these providers:
%(providers)s

Current configuration: %(current)s

Respond with STRICT JSON only:
{"primary": {"provider": "<one of the providers>", "model": "<exact model id>"},
 "fallback": {"provider": "<one of the providers>", "model": "<exact model id>"},
 "reason": "<one or two sentences: what you changed and why, or that nothing should change>"}
Keep a role's current provider+model UNLESS a clearly better option exists — change a role
ONLY when genuinely warranted."""


def main() -> None:
    current = ai_fix.load_model_config()
    cur_short = {r: {"provider": current[r]["provider"], "model": current[r]["model"]}
                 for r in ("primary", "fallback")}
    listing = "\n".join(f"- {p} (base_url {base})" for p, (base, _) in ai_fix.PROVIDERS.items())
    prompt = PROMPT % {"providers": listing, "current": json.dumps(cur_short)}

    # Ask via the current primary, then the fallback.
    rec = None
    for role in ("primary", "fallback"):
        slot = current[role]
        if os.environ.get(slot["api_key_env"], "").strip():
            try:
                rec = ai_fix.call_openai_compatible(slot, prompt)
                break
            except Exception as e:
                print(f"Recommendation via {role} failed ({e.__class__.__name__}).")
    if not isinstance(rec, dict):
        stop("No usable recommendation — keeping current models.")

    new = {}
    for role in ("primary", "fallback"):
        r = rec.get(role)
        if not isinstance(r, dict):
            stop(f"Recommendation missing {role} — keeping current.")
        provider = str(r.get("provider", "")).strip()
        model = str(r.get("model", "")).strip()
        if provider not in ai_fix.PROVIDERS or not model:
            stop(f"Recommended {role} is not a configured provider — keeping current.")
        new[role] = {"provider": provider, "model": model}

    changed = [role for role in ("primary", "fallback") if new[role] != cur_short[role]]
    if not changed:
        stop("Both the primary and fallback are still optimal — no change.")

    # Validate only the role(s) that changed with a live call.
    for role in changed:
        base_url, key_env = ai_fix.PROVIDERS[new[role]["provider"]]
        if not os.environ.get(key_env, "").strip():
            stop(f"{role} provider key not configured — keeping current.")
        try:
            test = ai_fix.call_openai_compatible(
                {"base_url": base_url, "model": new[role]["model"], "api_key_env": key_env},
                'Reply with the JSON {"ok": true} and nothing else.',
            )
            if not isinstance(test, dict):
                stop(f"{role} validation returned unexpected output — keeping current.")
        except Exception as e:
            stop(f"Recommended {role} failed a live validation call ({e.__class__.__name__}) — keeping current.")

    comment = ""
    try:
        comment = json.loads(CONFIG.read_text()).get("_comment", "")
    except Exception:
        pass
    CONFIG.write_text(json.dumps({"primary": new["primary"], "fallback": new["fallback"],
                                  "_comment": comment}, indent=2) + "\n")

    reason = str(rec.get("reason", "")).replace("\n", " ").strip()[:500]
    emit(changed="true",
         changed_roles=" & ".join(changed),
         primary=f'{new["primary"]["provider"]}/{new["primary"]["model"]}',
         fallback=f'{new["fallback"]["provider"]}/{new["fallback"]["model"]}',
         reason=reason)
    print(f"Proposing change to: {', '.join(changed)}")


if __name__ == "__main__":
    main()
