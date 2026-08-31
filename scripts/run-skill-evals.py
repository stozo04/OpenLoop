#!/usr/bin/env python3
"""Behavioural evals for this repo's skills — does a skill fire when it should, and answer right?

    python scripts/run-skill-evals.py                       # every case
    python scripts/run-skill-evals.py --skill harness-sync  # one skill
    python scripts/run-skill-evals.py --case gate-6d-red    # one case

Cases live in `.claude/evals/<skill>/cases.json`, once, for all three harnesses — see
`.claude/evals/README.md` for why they are not copied per harness.

Each case spends a real agent run via `claude -p` (measured: roughly $1-2 and 10-15 turns), so
this is opt-in and filterable, never a sweep gate. Run the cases you touched.

Two things get scored per case:

  * fired    - was the skill actually consulted? This is what the skill's `description` controls,
               and the half that rots invisibly as the way people phrase requests drifts.
  * content  - `expects` substrings present, `rejects` absent. Kept loose on purpose: they check
               that the model reached the right conclusion, not that it worded it a certain way,
               because a suite that pins phrasing fails on every harmless reword and gets ignored.

A `should_trigger: false` case inverts the first check - firing IS the failure. Those near-misses
earn their keep: a description broad enough to catch every real request usually also reaches into
adjacent work it should stay out of.

Write tools are disallowed in the child run so an eval can never mutate the repo it is testing.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EVALS = ROOT / ".claude" / "evals"
# The child agent may read and investigate freely; it may not write. An eval that edits the tree
# would dirty the very harness trees the skill under test is about to be judged on.
DISALLOWED = "Write,Edit,NotebookEdit"


def run_case(skill, case, timeout):
    p = subprocess.run(
        ["claude", "-p", case["prompt"], "--output-format", "json", "--disallowedTools", DISALLOWED],
        cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    try:
        data = json.loads(p.stdout)
    except json.JSONDecodeError:
        return {"error": (p.stdout or p.stderr)[:300]}

    answer = data.get("result") or ""
    # Check if skill was consulted: first try dedicated field, then check metadata (not result text)
    if "skills_read" in data:
        fired = skill in data.get("skills_read", [])
    else:
        # Exclude result field to avoid false positives from answer text mentioning the skill
        metadata = {k: v for k, v in data.items() if k != "result"}
        fired = skill in json.dumps(metadata)
    want = bool(case.get("should_trigger", True))

    fails = []
    if fired != want:
        fails.append(f"skill {'fired but should not have' if fired else 'did not fire'}")
    # Content is only meaningful when the skill was supposed to drive the answer.
    if want:
        low = answer.lower()
        fails += [f"missing {s!r}" for s in case.get("expects", []) if s.lower() not in low]
        fails += [f"contains {s!r}" for s in case.get("rejects", []) if s.lower() in low]
    return {"fired": fired, "fails": fails, "cost": data.get("total_cost_usd"),
            "turns": data.get("num_turns"), "answer": answer}


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--skill", help="only this skill's cases")
    ap.add_argument("--case", help="only this case name")
    ap.add_argument("--timeout", type=int, default=600, help="per-case seconds (default 600)")
    args = ap.parse_args(argv)

    suites = sorted(EVALS.glob("*/cases.json"))
    if args.skill:
        suites = [s for s in suites if s.parent.name == args.skill]
    if not suites:
        print(f"no cases found under {EVALS.relative_to(ROOT)}"
              + (f" for skill {args.skill!r}" if args.skill else ""))
        return 1

    failed = total = 0
    cost = 0.0
    for suite in suites:
        spec = json.loads(suite.read_text(encoding="utf-8"))
        skill = spec["skill"]
        for case in spec["cases"]:
            if args.case and case["name"] != args.case:
                continue
            total += 1
            print(f"\n=== {skill} / {case['name']} "
                  f"(should_trigger={case.get('should_trigger', True)}) ===")
            print(f"  prompt: {case['prompt'][:100]}...")
            try:
                r = run_case(skill, case, args.timeout)
            except subprocess.TimeoutExpired:
                r = {"error": f"timed out after {args.timeout}s"}

            if "error" in r:
                failed += 1
                print(f"  ERROR  {r['error']}")
                continue
            cost += r["cost"] or 0
            if r["fails"]:
                failed += 1
                print(f"  FAIL   {'; '.join(r['fails'])}")
                print(f"  answer: {r['answer'][:300]}")
            else:
                print(f"  pass   (fired={r['fired']}, turns={r['turns']}, ${r['cost']:.2f})")

    if args.case and total == 0:
        print(f"no case named {args.case!r} found")
        return 1
    print(f"\n{total - failed}/{total} passed  ·  ${cost:.2f} spent")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
