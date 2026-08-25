# Lesson 038 — An inspection is only as honest as its scope; gate on what git tracks, and sweep before every PR

> Origin: the 2026-08-25 "ton of errors" Inspect Code export — 82,752 items, of which ~1,400
> were in files git tracks. Fixed on `feature/multi-face-lenses`; the sweep it produced is now the
> pre-PR gate (`scripts/pre-pr-sweep.ps1`).

## What went wrong

Android Studio's **Analyze → Inspect Code** was run over the *whole project directory*. That
directory is not the repo. It also holds:

- `.claude/worktrees/` — four git worktrees, i.e. **full copies of the repo** from parallel sessions
  (two registered, two stale). Every one of their `.kt`, `.md` and `.xml` files was inspected again.
- the gitignored DeepAR reference bundle under `twisted-tounge/`, `docs/local/`, the swarm
  message-bus `.jsonl` logs, `.kotlin/errors/*.log`, `keystore.properties`, a service-account key.

The export came to **9 GB of HTML** and 82,752 items. 98% of them were phantom: the same finding
reported four times over, or a finding in a file nobody can commit. The real population — findings
in tracked files — was ~1,400, and *that* list was fixable in a day.

Underneath the noise, the tracked findings had accumulated because nothing ran the checks that find
them: Kotlin-redundancy inspections, Markdown table alignment, ordered-list numbering, directory
links that resolve to `''`, Grazie grammar/dialect rules, and ~400 "typos" that were mostly
project terms the IDE had never been taught.

## Pattern

1. **Scope every analysis to `git ls-files`.** A finding in an untracked or ignored file cannot be
   fixed by a PR, so it is not a finding. The committed scope `.idea/scopes/OpenLoop_Tracked.xml`
   does this for Inspect Code (select **Custom scope → OpenLoop Tracked** in the dialog), and
   `scripts/inspect-report.py` re-applies the rule to the HTML export — it counts only tracked
   files, so a run that forgot the scope still yields a truthful number.
2. **One sweep, every class, zero.** `scripts/pre-pr-sweep.ps1` runs each gate the repo knows how
   to run — clean debug+release build with `w:` fatal, 16 KB zipalign, Lint at 0/0, unit +
   instrumented tests counted from XML, markdownlint, IDE-faithful table alignment, link check,
   cspell over every tracked text file, JSON validity, dictionary sync, and the Inspect Code export
   — and writes `build/sweep-receipt.json` only when all of them are green.
3. **The receipt is the ticket.** `.claude/settings.json` wires `scripts/hooks/require-sweep.mjs`
   as a `PreToolUse` hook: `gh pr create` and the GitHub `create_pull_request` tool are refused
   unless a receipt exists for the current `HEAD` on a clean tree. So the sweep is, by construction,
   the last thing that runs after the final commit.
4. **Teach the tools the vocabulary; never silence the check.** `cspell.json` `words` is the single
   dictionary; `scripts/sync-ide-dictionary.py` derives `.idea/dictionaries/project.xml` from it so
   the IDE stops flagging `Exynos`, `playhead`, `matcap`… on every clone. Real typos get fixed.
5. **Version-freshness checks are advisory, everything else is hard.** `GradleDependency`,
   `NewerVersionAvailable`, `AndroidGradlePluginVersion` and the IDE's "newer Gradle available"
   flip whenever upstream publishes. A gate that goes red on someone else's release calendar is a
   flaky gate; the sweep reports them and moves on (`docs/STATIC_ANALYSIS.md`).

## Detection checklist

- An Inspect Code item count in the tens of thousands, or an export over a few hundred MB, means
  the scope swallowed worktrees or vendor bundles — re-run with **OpenLoop Tracked**, or just run
  `python scripts/inspect-report.py <export>/index.html` and read the "skipped (not tracked)" line.
- `git worktree list` vs `ls .claude/worktrees` — directories that are not registered worktrees are
  stale copies; delete them (they are not part of the repo and inflate every whole-tree tool).
- `python scripts/md-table-align.py` — any output means a table the IDE will flag. Markdownlint's
  `MD060 aligned` disagrees with the IDE on emoji rows (display width vs UTF-16 units), which is why
  the repo carries its own aligner.
- A PR opened without `build/sweep-receipt.json` for its HEAD is a process failure, whoever opened
  it. The hook makes that impossible from a Claude Code session; humans run the same script.

## Reference

- `scripts/pre-pr-sweep.ps1`, `scripts/inspect-report.py`, `scripts/md-table-align.py`,
  `scripts/sync-ide-dictionary.py`, `scripts/hooks/require-sweep.mjs`, `.idea/scopes/OpenLoop_Tracked.xml`
- [`docs/DEFINITION_OF_DONE.md`](../DEFINITION_OF_DONE.md) → "The gate";
  [`docs/STATIC_ANALYSIS.md`](../STATIC_ANALYSIS.md) → Tier 2 (scope + export) and the sweep.
- Related: [[009-toml-inline-tables-single-line]], [[010-markdown-code-fences-are-inspected]] — the
  earlier, smaller instances of "the IDE inspects more than you think".
