---
name: harness-sync
description: Check and repair byte-drift between the three LLM harness skill trees — `.claude/skills/`, `.cursor/skills/` and `.codex/skills/` — which the Definition of Done (M5) requires to be byte-identical. Use this skill whenever you edit, add, delete or rename ANYTHING under any of those three directories, and whenever the user says "sync harness", "sync the skills", "/harness-sync", "harness drift", "gate 6d", "M5", "sweep gate 6d is red", "the skill trees have drifted", "propagate this skill to the other LLMs", or asks whether the three harness folders match. Also use it proactively before committing a skill change and when a pre-PR sweep or CI run reports the "Harness skill trees byte-identical" gate as failing — a skills edit that reaches only one harness is the exact failure this catches.
---

# harness-sync — keep the three harness skill trees identical

This repo is driven by three LLM providers, and each harness auto-discovers skills **only** under
its own directory: `.claude/skills/`, `.cursor/skills/`, `.codex/skills/`. The content is the same
project knowledge, so those three trees are copies of one thing and must never diverge. A skill
fixed for one LLM that stays broken for the other two is worse than not fixing it — nothing on
disk says which copy is current, so the next agent to read it has no way to know it is stale.

`scripts/sync-harness-skills.py` is the implementation; this skill is how to drive it and how to
read what it says. The rule it enforces is **M5** in `docs/DEFINITION_OF_DONE.md`.

## Check for drift

Run this first, always — it is read-only and takes under a second:

```bash
python scripts/sync-harness-skills.py
```

`--check` is the default. Exit 0 prints `harness skills in sync (N files x 3 harnesses)` and there
is nothing to do. Exit 1 lists every path that differs, and for each one says whether each harness
is `same`, `differs`, or `missing`.

## Repair drift

**Aligning means the harness that changed wins and the other two catch up.** That direction is the
whole point and it is easy to get backwards: overwriting the edited copy to match the two untouched
ones also makes the trees agree, so the gate goes green while the change is gone. That failure
reports nothing — which is why the script works out the direction from git rather than trusting
whoever ran it.

```bash
python scripts/sync-harness-skills.py --fix
```

With no `--from`, it asks git which tree actually changed (committed on this branch, or still
dirty) and propagates from that one, printing what it inferred. The three outcomes:

- **One tree changed** — that is the source. It proceeds.
- **No tree changed** — nothing to propagate; the drift is something else (a stray file, a bad
  merge). It stops rather than picking a winner.
- **Two or three changed separately** — it stops. They were edited independently, so no copy is
  the truth; reconcile the content by hand into one correct tree, then run it again.

Pass `--from <harness>` only to override that inference. If it contradicts git — you name
`.claude` while the change is in `.cursor` — the script refuses and names the tree whose work
would have been deleted. That refusal is the guard rail, not an obstacle: getting it means the
direction was about to go the wrong way. `--force` overrides it, and is only correct when you have
decided to genuinely discard that change.

`--fix` copies changed files and mirrors deletions, printing every path it writes or removes. It
touches only the other two trees, never the source.

## After a repair

The three trees are one logical change, so they belong in one commit:

```bash
git add .claude/skills .cursor/skills .codex/skills
python scripts/sync-harness-skills.py    # must be green before you commit
```

Then say plainly in the commit or PR which harness was the source. That sentence is the only
record of which copy was authoritative, and the next person to hit a conflict will want it.

## Scope — what is and is not compared

Only `skills/**` is compared. Two things inside those directories are deliberately excluded and
must NOT be synced:

- **`settings.json`** is harness-specific. Claude's carries marketplaces, plugins and hooks; the
  other two carry a plugin stub. Making them identical would break two harnesses.
- **`.claude/commands/`** and `.claude/evals/` have no counterpart in the other two directories.
  They are Claude-only by design and live outside `skills/`, which is why the script never sees
  them.

If you are adding a fourth harness, create `.<name>/skills/`, add the name to `HARNESSES` in the
script, and add the allowlist entries in `.github/workflows/doc-layout.yml` and
`docs/README.md` § Enforcement — in the same PR, per Definition of Done M1.

## Where this is enforced

Both are hard gates, so drift cannot reach `main`:

- **Locally** — `scripts/pre-pr-sweep.ps1` gate **6d**, which also runs under `-DocsOnly`.
- **In CI** — the "Harness skill trees byte-identical" step in
  `.github/workflows/static-analysis.yml`.

Never satisfy either gate by editing the gate. If drift is real, propagate it; if the two trees
should genuinely differ, that is a change to M5 and an owner decision, not a workaround.
