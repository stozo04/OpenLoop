# OPERATING_INSTRUCTIONS.md — how to work here

Execution rules, shared by every LLM that works in this repo (Claude Code, Cursor, Codex, …).
The root `CLAUDE.md` / `AGENTS.md` pointers send each tool here. Edit this file, not a per-tool copy.
What OpenLoop is — architecture, lessons, Definition of Done — is in [`docs/OPENLOOP_INSTRUCTIONS.md`](OPENLOOP_INSTRUCTIONS.md).

## Purpose

Complete the current task with the minimal sufficient solution.
Prohibit over-engineering.
Planning can be aggressive, but execution must be lightweight.
Designs that cannot prove necessity are not done by default.
Tests that cannot prove necessity are not added by default.

## Workflow

1. Understand the requirements first, then take action. Do not modify code first and guess the intent afterward.
2. Match reasoning effort to the difficulty and uncertainty of the work. Keep the configured model unless the user requests a change; do not claim to switch models or effort without a supported tool.
3. Use the smallest sufficient solution after tracing the affected flow and its callers. Reuse existing code, standard libraries, and installed tools before adding anything.
4. Work in one thread by default. Delegate only when requested by the user or an applicable project workflow, with independent tasks, clear ownership, and a final review of the combined result.
5. Only enable skills that are necessary to complete the task. Do not install heavy-process skills.
6. Produce a minimal plan first, then execute. The plan must clearly state:
   - Goals
   - Non-goals
   - Acceptance criteria
   - Scope not to be changed

## Failure Modes

1. Failing to truly understand the intent and only fixing surface issues.
2. When a clean root-cause fix could have been done once, instead piling on historical patches, compatibility layers, dual tracks, duplicates, and branches to bloat the code.
3. Over-designing for rare cases, increasing daily maintenance costs.
4. Wrong judgment basis: even if reasoning is complete, the conclusion is wrong.
5. Instead of directly reading the code to locate the issue, substituting with search or guesswork.
6. Using "add tests" as an excuse to keep adding abstraction, expanding scope, and making things seem complete.

## Action Boundaries

1. Treat "can you...", "I want to...", and "help me..." as requests to act. Infer scope from the current request and prior context, then finish the authorized work. Do not stop at a plan, a capability answer, or an offer to continue. Respect explicit limits such as "review only", "no code", "no commits", and "one phase at a time".
2. Authorization persists across turns. Routine edits, fixes, read-only checks, isolated branches, and preparing a reviewable diff do not need another approval when covered by the request. A request to create a PR authorizes its commit, push, and creation after the required checks; it does not authorize merging or releasing.
3. Before seeking approval for a remaining action, finish the already-authorized preparation and verification so the user can review the concrete result. Ask only for missing information that materially changes the outcome or for an action outside the existing authorization. Continue independent work while waiting. Never treat silence as approval.
   - Use reasonable assumptions for routine choices and state material assumptions briefly. Do not invent confirmation codewords or hypothetical-risk approval flows.
   - Check the actual effect before destructive operations: a reset or rollback can discard uncommitted work; a move can overwrite a destination. Preserve unrelated work and data. Unapproved deletion of user data, history rewriting, external communications, financial actions, merges, and releases need explicit authorization.
   - Required repository reviews, Play upload confirmation, and tool/sandbox approval gates still apply. Complete the preparation before the gate and never bypass it.
4. If you find yourself doing any of the following, must stop and switch to a smaller solution:
   - Adding new abstractions, frameworks, or config layers that the current requirement doesn't need
   - Designing ahead for possible future use
   - Continuing to stack more constraints to satisfy existing ones
   - Modifying many unrelated files at once
   - Creating a second implementation to accommodate old logic
   - Using the opportunity to add a complete test suite

## Instruction Priority and Follow-through

The user's current instructions and prior authorization take precedence over project skill guidelines, subject to system/developer instructions and enforced tool permissions. Read skills as procedures within the authorized scope, not as a reason to re-request permission. A triage-only request ends with findings; an authorized fix continues through implementation and verification.

If an instruction or skill causes a pause, permission question, unfinished task, or departure from the request, name and link the exact file, quote the relevant instruction, and explain why it applies. Distinguish an explicit requirement from your interpretation. If automatic approval review rejects an action, identify the action and summarize its stated reason.

Incorporate corrections and new constraints into the active task. Answer side questions briefly, then continue the original objective unless the user cancels or replaces it. Keep a short checkpoint of completed work, evidence, decisions, and remaining steps for long tasks; context changes are not a reason to restart or abandon the task.

## Testing

Tests only serve to verify the current changes.
Tests are not responsible for filling historical coverage gaps or designing future test systems.

1. Prioritize running existing tests related to this change.
2. If existing tests can prove the change is correct, do not add new tests.
3. Only add new tests in the following two cases:
   - This change modified behavior, but existing tests don't cover it
   - User explicitly requires adding tests
4. Add the smallest meaningful check for the changed behavior and its material failure paths. Do not add tests that merely repeat the implementation or assert prose wording.
5. Prohibit expanding test scope for completeness.
6. Prohibit using the opportunity to fill tests for unrelated modules.
7. Prohibit introducing new test frameworks, tools, or infrastructure.
8. Prohibit writing large snapshots, parameterized matrices, or end-to-end suites.
9. Prohibit writing tests for boundaries not required by the current needs.
10. Prohibit modifying tests first and then forcing product behavior to become more complex.
11. Prohibit using green tests as a reason to continue adding abstraction.

Before adding any test, must be able to answer:

- Which accepted requirement is this test verifying
- If removed, can existing tests no longer detect this regression
- Is it more complex than the implementation itself

Prefer a simple test setup, but judge a test by the regression it detects rather than its line count. Do not delete meaningful coverage merely because it is longer than the implementation.

Complete the required Definition of Done checks. Once they pass, repeat or broaden testing only after a new change, failure, or unresolved concern. Capture the exit code of the command that matters and independently verify its result (artifact contents, test counts, or remote PR state); a shell exiting 0 is not proof that the task succeeded. Report skipped checks and the limits of the evidence.

## Communication

Lead with the result or intended action. Use concise, plain paragraphs; use lists or tables when they make steps or comparisons clearer. Explain findings with concrete evidence and consequences. Avoid filler, stock phrases, and repeated plans. During sustained work, give brief progress updates; the final answer states what changed, what was verified, and any remaining blocker.

## Pre-Completion Checklist

- Intent and acceptance criteria have been restated
- Solution is the minimal one, not the maximal one
- Non-goals have been marked
- Prioritized reading relevant code, rather than piecing conclusions from search
- Only modified the minimal set of files needed to complete the task
- Related existing tests have been run
- No tests added for scenarios the change did not require
- If tests added, only lock this behavior, and in very small numbers
- Tests did not introduce new dependencies or directory structures
- Diff is small, no extra files, no leftover debug code
- No extra construction done to make it look complete

## General Principles

Understand intent first, then complete acceptance with minimal changes.
Designs that cannot prove necessity are not done by default.
Tests that cannot prove necessity are not added by default.

Audit record: [project instructions and skills, 2026-09-06](guides/project-agent-audit.md).
