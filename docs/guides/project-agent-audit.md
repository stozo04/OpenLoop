# OpenLoop project instruction and skill audit

Audited 2026-09-06 against the [GPT-6 Astra model guide](https://developers.openai.com/api/docs/guides/latest-model?model=gpt-6-astra).
The guide identifies approval pauses, instruction conflicts, excessive testing, and unclear
delegation or writing expectations as prompt concerns. This audit applies those concerns to
OpenLoop's existing workflows; it does not change model configuration.

## Scope

Only this repository: root `AGENTS.md` and `CLAUDE.md`, their two shared instruction documents,
all ten project skills and their bundled Markdown references and recipes across `.codex`,
`.cursor`, and `.claude`, plus project command adapters, settings, and `.cursor/BUGBOT.md`.
Helper paths and the synchronization mechanism were checked where they affect harness parity.
Global skills, installed plugin packages, other projects, application code, and release
configuration are outside this change.

## Shared instruction findings

- **Approval conflicts:** blanket PRD sign-off, a confirmation codeword, and treating normal
  source edits as destructive contradicted authorized implementation. The shared operating
  rules now treat "can you..." as an action request, preserve authorization across turns, and
  require a concrete diff or artifact before seeking approval for a remaining unapproved step.
  Explicit review-only, no-code, no-commit, and phase limits still apply.
- **Instruction precedence:** every project skill now links the same operating policy. If a
  rule blocks work, the agent must identify the exact source, quote the instruction, and explain
  its applicability. No silent interpretation of a guideline as a new approval requirement.
- **Scope and continuity:** routine choices use reasonable assumptions; material missing inputs
  get focused questions while independent work continues. Corrections and side questions retain
  the active task. Model changes are not claimed without supported controls; delegation stays
  limited to requested or explicitly configured project workflows.
- **Verification:** required gates remain. Tests must detect a meaningful regression, not merely
  duplicate prose or implementation. Existing evidence is tied to the reviewed commit; passing
  checks do not trigger repeated broader sweeps without a new reason. Completion requires
  independent result checks, not just a process exit code.

## Skill-by-skill result

All rows refer to the corresponding `skills/<name>/SKILL.md` in each harness. Codex was the
editing source; synchronization carries the changes to Claude Code and Cursor.

| Skill                 | Finding and disposition                                                                                                                                                                                                                   |
| --------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `crashlytics-triage`  | Removed mandatory reconfirmation before an already-authorized fix. Triage-only still returns findings; remote notes and issue updates require a request.                                                                                  |
| `create-verifier`     | Retained one named feature, observable assertions, evidence, and device boundaries. Added the shared policy pointer.                                                                                                                      |
| `cut-release`         | Retained required review and Play upload confirmation. A tag alone no longer proves completion; run the existing final artifact and release checks.                                                                                       |
| `harness-sync`        | Retained source inference, conflict protection, and per-harness paths. Fixed the comparator's identical-byte shortcut that hid foreign harness paths.                                                                                     |
| `pr-reviewer`         | Replaced whole-app review and unrelated research mandates with affected-flow review. Fixed stale document paths and inspection guidance. Unreviewed categories are N/A or NOT RUN, never PASS. Review publication requires authorization. |
| `reset-storage`       | Corrected the claim that only onboarding changes: the file also contains the speed-curve intro and saved-loop count. Added a successful directory read-back before reporting the reset.                                                   |
| `run-e2e`             | Replaced a harness-specific Monitor requirement with bounded log-file polling, stale shutter coordinates with labels, and four-device wording with risk-based lanes. Reports identify the actual driver.                                  |
| `run-e2e-pixel-sweep` | Retained Pixel 8 by default and the existing risk-triggered lanes. Helpers now derive this checkout's root and sibling skill paths instead of old OpenRang or Claude-only paths.                                                          |
| `verifier`            | Retained the issue 170 deadline, between-keyframe assertion, and failure evidence. Added the shared policy pointer.                                                                                                                       |
| `verify-openloop`     | Removed reliance on a global Cursor rule, scoped recipes to the requested claim, corrected own-tree paths, and allowed existing autonomous sections in the feature-file contract.                                                         |

## Other project surfaces

Root `AGENTS.md` and `CLAUDE.md` remain identical pointers, so shared changes reach all three
LLMs without separate policy copies. Settings remain provider-specific by design; the Claude
PR hook still enforces a current green receipt. `.cursor/BUGBOT.md` contains a narrow historical
exception and needs no change. The Claude `create-verifier` adapter already delegates to its
skill; the lens-swarm command remains an explicitly invoked workflow. The historical fold-loop
command now requires explicit invocation rather than automatically expanding every media change
into a three-device loop. Its old version and test-count assumptions were removed.

## Verification and limits

- The new synchronization regression first failed against the old comparator: three files
  pointing at `.claude` incorrectly returned "in sync". After the fix, all 16 offline cases
  pass, including repair to each destination's own slash and backslash paths.
- `python scripts/sync-harness-skills.py --check` reports 46 package files across three harnesses
  in sync. Markdown and helper changes are propagated together; root pointer equality is checked
  separately. Provider settings and command adapters are intentionally outside skill parity.
- The final PR records the pre-PR sweep receipt and any environment-limited checks. Because this
  change includes helper and comparator code, it requires the full sweep rather than `-DocsOnly`.
- This is an instruction audit with deterministic synchronization proof. It does not claim that
  all three models have been behaviorally evaluated, that every feature recipe matches today's
  UI, or that Samsung hardware behavior was tested. Existing device and release gates retain
  their authority. No paid model evaluations were run.
