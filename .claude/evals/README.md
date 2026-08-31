# Skill evals — one suite, not one per harness

Behavioural tests for the skills in this repo: does a skill **fire** on the prompts it should,
stay quiet on the near-misses, and give the right answer when it does fire.

## Why this folder is here and not in all three harness directories

The skills themselves exist three times — `.claude/skills/`, `.cursor/skills/`, `.codex/skills/` —
because each LLM harness auto-discovers skills only under its own directory, and Definition of Done
**M5** requires those three trees to stay byte-identical.

The evals are the opposite case. They test *one* body of content, they are not read by any harness
at runtime, and three copies would mean three things to keep in step for no benefit — a second
sync problem invented to serve the first one.

So they live **once**, at `.claude/evals/`, which works out for three independent reasons:

1. **`skills/` is the whole M5 surface.** `scripts/sync-harness-skills.py` only ever compares
   `<harness>/skills/**`, so anything beside `skills/` is invisible to it. `.claude/evals/` is
   never copied, and gate 6d never looks at it.
2. **It is where the first-party tooling looks.** `claude plugin eval` resolves `.claude` as a
   skills-dir plugin and reads its eval cases from `evals/` below that plugin — the default this
   folder already satisfies, no `--eval-dir` flag needed.
3. **`.claude/*` is already allowlisted** in [`doc-layout.yml`](../../.github/workflows/doc-layout.yml),
   so Markdown here does not trip the "new `.md` must live under `docs/`" gate (M1).

`.claude/` is the host by convention, not by ownership. Nothing in here is Claude-specific — the
cases are provider-neutral, and a Cursor or Codex session should run and edit them exactly as a
Claude session does.

## Running the suite

```bash
python scripts/run-skill-evals.py                       # every case
python scripts/run-skill-evals.py --skill harness-sync  # one skill's cases
python scripts/run-skill-evals.py --case gate-6d-red    # one case
```

**These cost real money** — each case spends a full agent run (measured: roughly $1–2 and 10–15
turns apiece), which is why the runner is opt-in and filterable rather than a sweep gate. Run the
cases you touched, not the whole suite, unless you are changing a skill's description.

The runner asks for `--output-format stream-json` — it needs the per-message `tool_use` events,
not just the final answer — and scores two things per case:

- **fired** — whether the skill was actually consulted, read from the `tool_use` events rather
  than from the wording of the answer. This is the part a skill's `description`
  controls, and the part that silently rots when the description drifts from how people really ask.
- **expects** — substrings that must appear in the answer, and `rejects` that must not. Deliberately
  loose: these check that the model reached the right conclusion, not that it phrased it a
  particular way. Pinning phrasing produces a suite that fails on every harmless reword.

A `should_trigger: false` case is scored the other way — firing is the failure. Those near-misses
are the ones worth having: a description broad enough to catch every real request usually also
catches adjacent work it should stay out of.

## The runner has its own tests

A suite lies more quietly than a skill breaks — it reports green when nothing ran, or red when
the skill was fine — so the runner is itself tested, free and offline:

```bash
python scripts/test-run-skill-evals.py
```

Every case in it is a lie this runner actually told, kept so it cannot tell it twice. Three came
out of the first real run and the Bugbot review of it:

- **A text search is not an invocation.** `fired` used to be the skill name appearing anywhere in
  the transcript, which is wrong in both directions: a correct refusal that explains what the
  skill is for reads as fired, and a correct use that only names the underlying script reads as
  not fired. It now reads `tool_use` events.
- **A run that never happened must not be scored.** A session limit was reported as a content
  failure on one case and as a *pass* on the negative case — because a run that never fires the
  skill trivially satisfies "the skill did not fire". Those are `ERROR` now, counted separately.
- **A filter that matches nothing exits 1.** `--case` with a typo printed `0/0 passed` and exited
  0, which looks like proof and contains none.

When adding an assertion, ask what it would take for it to pass while the skill is broken. If the
answer is "the API being down", it is not an assertion yet.

## The deterministic half

Behaviour tests answer "does the skill do the right thing". They cannot tell you whether the tool
underneath it still works, and they cost money, so the mechanism gets its own free, instant test:

```bash
python scripts/test-sync-harness-skills.py
```

Eleven cases against a throwaway git repo, no API calls. Two real bugs came out of writing it — a
new skill that was not yet `git add`ed passed the gate, and a file deleted from all three
harnesses crashed the comparison. Prefer this layer whenever a check can be made deterministic;
save the paid layer for what genuinely needs a model.

## Adding cases for a new skill

Create `<skill-name>/cases.json` beside this file. Each case:

```json
{
  "name": "short-kebab-name",
  "prompt": "what a person would actually type, in their own words",
  "should_trigger": true,
  "expects": ["substring that proves the right conclusion"],
  "rejects": ["substring that would mean it got it wrong"]
}
```

Write prompts the way the owner actually writes them — half a sentence, lowercase, a path pasted
in, no mention of the skill by name. A case that names the skill tests nothing, because naming it
is what makes it fire.

## Known gap

`claude plugin eval` is the first-party harness for this and is **early-access gated** on this
account (`claude plugin eval` exits 1 with "currently in early access"). When it is enabled,
`claude plugin eval init` scaffolds its own `case.yaml` / `graders/*.md` format here and brings
ablation arms, repeat runs and a scored HTML report. `scripts/run-skill-evals.py` covers the same
ground with `claude -p` in the meantime; the cases in `cases.json` carry over as prompts.
