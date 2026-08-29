# OPERATING_INSTRUCTIONS.md

## Purpose
Complete the current task with the minimal sufficient solution.
Prohibit over-engineering.
Planning can be aggressive, but execution must be lightweight.
Designs that cannot prove necessity are not done by default.
Tests that cannot prove necessity are not added by default.

## Workflow
1. Understand the requirements first, then take action. Do not modify code first and guess the intent afterward.
2. Higher reasoning can be used in the planning phase. In the execution phase, default to medium-low reasoning, or switch to a lighter model for implementation.
3. Do not keep the highest reasoning mode on throughout.
4. Do not default to spinning up multiple Agents in parallel. Complete one task in a single thread first, then decide whether to split it.
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
1. Before taking action, restate:
   - What the user truly wants
   - The scope of this time
   - Things explicitly not to do
   - What counts as completion
2. For any irreversible operation, must wait for user reply with confirmation codeword before executing.
   - Confirmation codeword is specified by the user.
   - Without codeword, wrong codeword, or other replies, refuse execution outright.
3. The following operations are not considered irreversible by default and can be executed:
   - Git rollback, revert, branch switch
   - Moving files to the backup directory of the current repository
   - Running tests, viewing diffs, generating plans, read-only analysis
4. If you find yourself doing any of the following, must stop and switch to a smaller solution:
   - Adding new abstractions, frameworks, or config layers that the current requirement doesn't need
   - Designing ahead for possible future use
   - Continuing to stack more constraints to satisfy existing ones
   - Modifying many unrelated files at once
   - Creating a second implementation to accommodate old logic
   - Using the opportunity to add a complete test suite

## Testing
Tests only serve to verify the current changes.
Tests are not responsible for filling historical coverage gaps or designing future test systems.

1. Prioritize running existing tests related to this change.
2. If existing tests can prove the change is correct, do not add new tests.
3. Only add new tests in the following two cases:
   - This change modified behavior, but existing tests don't cover it
   - User explicitly requires adding tests
4. New tests cover at most 1 main path of the actual change this time, and if necessary, add 1 key failure path.
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

If test code is longer or more convoluted than the implementation code, default to considering it over-engineering; delete the test or shrink the implementation.

## Model Division of Labor
- Requirement clarification and solution review: Use stronger models
- Writing code, modifying code, running tests: Use medium-low spec models, or lighter execution models
- When the execution model starts stacking architecture, adding compatibility, expanding scope, or adding large test suites: Stop immediately and rewrite the minimal plan

## Pre-Completion Checklist
- Intent and acceptance criteria have been restated
- Solution is the minimal one, not the maximal one
- Non-goals have been marked
- Prioritized reading relevant code, rather than piecing conclusions from search
- Only modified the minimal set of files needed to complete the task
- Related existing tests have been run
- No tests added for unrequired scenarios
- If tests added, only lock this behavior, and in very small numbers
- Tests did not introduce new dependencies or directory structures
- Diff is small, no extra files, no leftover debug code
- No extra construction done to make it look complete

## General Principles
Confirm intent first, then complete acceptance with minimal changes.
Designs that cannot prove necessity are not done by default.
Tests that cannot prove necessity are not added by default.
