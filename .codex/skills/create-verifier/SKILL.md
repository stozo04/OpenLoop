---
name: create-verifier
description: Create one autonomous installed-APK verifier for a named OpenLoop feature. Use when the user says /create-verifier, create a verifier, add a verification loop, or automate verification for a feature. Do not use for ordinary unit or Compose-host tests.
---

# Create Verifier

Create exactly one verifier for the feature the user named. Do not plan or implement other feature verifiers.

## Outcome

The verifier drives `io.github.stozo04.openloop/.MainActivity` on an emulator like a user, saves evidence, and exits nonzero when any acceptance assertion is false. A passing Compose `setContent` test is not this proof.

## Workflow

1. Read `docs/OPERATING_INSTRUCTIONS.md`, `docs/OPENLOOP_INSTRUCTIONS.md`, the matching `.codex/skills/verify-openloop/features/<feature>.md`, and the relevant product code/strings. Use `.codex/skills/verify-openloop/helpers/onboarding_loop.py` as the proven reference.
2. Derive observable acceptance criteria from the shipped product: precondition, entry point, exact must-have/must-not-have UI, user actions, persisted/resulting state, and any required non-UI proof such as logcat or a file.
3. Create `.codex/skills/verify-openloop/helpers/<feature>_loop.py` with Python's standard library. Reuse an existing helper when one fits; extract shared code only after two real loops demonstrate material duplication.
4. Update the matching feature recipe with the direct run command.
5. Build the current debug APK if needed and run the new loop on a booted emulator. Do not report completion from syntax checks or exit code alone; independently confirm its final PASS marker and evidence artifacts.
6. Only once it has been seen passing, mark the surface `automated <YYYY-MM-DD>` in `features/INVENTORY.md` — add a row when the loop covers a surface the table does not list yet, and leave neighbouring rows `mapped` rather than overstating what the loop drives. Then run `python scripts/sync-harness-skills.py --fix` (it takes the direction from git and retargets each copy's own paths), followed by `--check`; the sync must come last so every edit above reaches all three harnesses.

`scripts/run-verification-loops.py --changed` discovers shipped `*_loop.py` files automatically, so there is no registry or roadmap to update.

## Verifier contract

- Default to one online emulator; require `VERIFY_SERIAL` when ambiguous.
- Never drive a physical phone unless both `VERIFY_SERIAL` and `VERIFY_ALLOW_DEVICE=1` are set.
- Install the current `app/build/outputs/apk/debug/app-debug.apk` when present.
- Reset only state required by this feature; preserve user media and unrelated preferences.
- Use UI hierarchy XML for visible state and exact labels from current resources. Use content descriptions that are also valid accessibility labels, never test-only product tags.
- Poll bounded asynchronous transitions and save the last XML, screenshot, and relevant logcat/file evidence on failure.
- Print one final `PASS ...` line only after every assertion succeeds; any false assertion exits nonzero.
- Treat a red verifier as a product bug unless its captured evidence proves the verifier is stale or incorrect. Do not weaken assertions or stretch timeouts to hide a failure.

Stop after the named verifier passes. Do not create adjacent verifiers for completeness.
