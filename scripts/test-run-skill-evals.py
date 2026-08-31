#!/usr/bin/env python3
"""Self-check for scripts/run-skill-evals.py — the eval runner itself.

    python scripts/test-run-skill-evals.py

An eval suite is only worth having if its verdicts are trustworthy, and the ways a suite lies are
quieter than the ways a skill breaks: it reports green when nothing ran, or red when the skill was
fine. Every case below is a lie this runner actually told, kept here so it cannot tell it twice.

`score_run` is pure — it takes a transcript and returns a verdict — so all of this runs on
synthetic events, offline and free. That is the point: a runner whose own tests cost $1.50 a case
does not get run.

Provenance of each case:

  * fired_* — Cursor Bugbot on PR #161: `fired` was `skill in json.dumps(data)`, a text search
    over the transcript. It conflates the skill being USED with the skill being MENTIONED, and is
    wrong in both directions — a correct refusal that explains what harness-sync is for looked
    fired, and a correct use that only ever named `sync-harness-skills.py` looked not-fired.
  * usage_limit / is_error — observed live: a run that hit the session limit was scored as a
    content FAIL, and worse, the `should_trigger: false` case PASSED on it, because a run that
    never happened trivially satisfies "the skill did not fire".
  * unmatched_filter — Cursor Bugbot on PR #161: `--case` with a typo printed `0/0 passed` and
    exited 0. A mistyped filter looked exactly like a clean run.
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
run_skill_evals = __import__("run-skill-evals")
score_run = run_skill_evals.score_run

SKILL = "harness-sync"


def transcript(*, tool_uses=(), answer="", is_error=False, subtype="success"):
    """A minimal stream-json transcript: assistant turns, then the final result event."""
    events = []
    for name, inp in tool_uses:
        events.append({"type": "assistant",
                       "message": {"content": [{"type": "tool_use", "name": name, "input": inp}]}})
    events.append({"type": "result", "subtype": subtype, "is_error": is_error,
                   "result": answer, "total_cost_usd": 0.5, "num_turns": 4})
    return events


def case(name):
    def decorator(fn):
        fn()
        print(f"  ok  {name}")
    return decorator


def main():
    print("testing run-skill-evals.py")
    positive = {"name": "p", "prompt": "x", "should_trigger": True, "expects": [], "rejects": []}
    negative = {"name": "n", "prompt": "x", "should_trigger": False}

    @case("fired_by_tool_use — the Skill tool is what counts, not the wording")
    def _():
        # Answer never names the skill; only the tool event does. Must still read as fired.
        r = score_run(transcript(tool_uses=[("Skill", {"skill": SKILL})],
                                 answer="Run sync-harness-skills.py --fix and commit all three."),
                      SKILL, positive)
        assert r.get("fired") is True, r

    @case("fired_by_skill_file_read — loading SKILL.md directly also counts")
    def _():
        r = score_run(transcript(tool_uses=[("Read", {"file_path": r"C:\repo\.cursor\skills\harness-sync\SKILL.md"})],
                                 answer="here is what to do"), SKILL, positive)
        assert r.get("fired") is True, r

    @case("not_fired_when_only_mentioned — a refusal that names the skill has not used it")
    def _():
        # The negative case's whole job: correctly declining, while explaining the skill's scope.
        r = score_run(transcript(answer="That is not harness-sync's job — settings.json differs "
                                        "between harnesses by design, so do not sync it."),
                      SKILL, negative)
        assert r.get("fired") is False, r
        assert r.get("fails") == [], r

    @case("usage_limit — a run that never executed is an ERROR, never a pass or a fail")
    def _():
        limit = transcript(answer="You've hit your session limit · resets 12:40am")
        # Scored as a positive case it must not read as a content failure...
        assert "error" in score_run(limit, SKILL, positive), score_run(limit, SKILL, positive)
        # ...and as a negative case it must not read as a pass, which is the dangerous direction.
        r = score_run(limit, SKILL, negative)
        assert "error" in r and "fails" not in r, r

    @case("is_error — a hard failure is an ERROR too, not a silent negative pass")
    def _():
        bad = transcript(answer="boom", is_error=True, subtype="error_during_execution")
        assert "error" in score_run(bad, SKILL, negative), score_run(bad, SKILL, negative)

    @case("no_result_event — a truncated transcript does not score as anything")
    def _():
        assert "error" in score_run([{"type": "assistant", "message": {"content": []}}], SKILL, positive)

    @case("expects_and_rejects — content assertions still apply on a real run")
    def _():
        events = transcript(tool_uses=[("Skill", {"skill": SKILL})], answer="propagate from .cursor")
        good = dict(positive, expects=["propagate"], rejects=["delete"])
        bad = dict(positive, expects=["byte-identical"], rejects=[])
        assert score_run(events, SKILL, good)["fails"] == []
        assert score_run(events, SKILL, bad)["fails"], "a missing expects substring must fail"

    @case("unmatched_filter — a --case typo exits 1, never '0/0 passed' with exit 0")
    def _():
        with tempfile.TemporaryDirectory() as d:
            suite = Path(d) / SKILL
            suite.mkdir()
            (suite / "cases.json").write_text(json.dumps(
                {"skill": SKILL, "cases": [dict(positive, name="real-case")]}), encoding="utf-8")
            original, run_skill_evals.EVALS = run_skill_evals.EVALS, Path(d)
            try:
                # No case matches, so no agent run is spawned — this stays free.
                assert run_skill_evals.main(["--case", "typo-here"]) == 1
            finally:
                run_skill_evals.EVALS = original

    print("all cases passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
