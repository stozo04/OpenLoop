---
name: create-verifier
description: Create a verification loop (helpers/<feature>_loop.py) that drives the installed OpenLoop debug APK on a real emulator and fails the process on a false assertion. Use when the user says "/create-verifier", "create a verifier", "add a verification loop", or "build an X loop" for a FEATURE_NAME. Do not use for Compose setContent tests, ViewModel fakes, or walking a dump/tap recipe by hand and calling that proof.
---

# /create-verifier FEATURE_NAME

Build a **loop**: a Python 3 stdlib script that installs/drives the debug APK and exits 1 if the assertion is false. FEATURE_NAME is the loop id from `docs/guides/verification-loops.md` (example: `capture-mode`, `photo-booth-arm`).

Read that guide in full before writing a line. The golden example is `.cursor/skills/verify-openloop/helpers/onboarding_loop.py`.

## What the owner is asking for

Steven wants the installed app driven like a user. He is **not** asking for:

- `createComposeRule().setContent { SomeButton() }`
- A ViewModel test with a fake repository
- A recipe walked once by hand
- Setting `OpenLoopUiState` from a test-only host

Those stay as the pyramid base. They do not replace this loop.

## Failures are product bugs

A red loop means the work is **not done**. Keep going until the product matches the assertion, or escalate.

**Do not** make the loop pass by changing the loop, unless you can show the loop itself is wrong (stale copy vs `strings.xml`, `adb devices` regex, empty-dump false fail). Forbidden "fixes":

- Deleting or weakening an assertion that failed
- Catching FAIL and printing PASS
- Stretching timeouts until a flake hides
- Skipping a must-have string
- Adding product `testTag`s just so the dump is easier
- Pointing the loop at a Compose host instead of `MainActivity`

Allowed loop edits: literals that `strings.xml` actually changed; bugs in dump/tap/serial matching like the onboarding `check=False` / stale-dump fixes.

## Do this, in order

1. Parse FEATURE_NAME from the user message (`/create-verifier capture-mode` → `capture-mode`). If it is missing, stop and ask. If it is not a row in `docs/guides/verification-loops.md`, stop and say which wave it would belong in — do not invent a loop the plan deferred (do not start wave 3 before wave 1 exists).
2. Read `docs/guides/verification-loops.md` (contract + that row). Read the matching `features/<name>.md`. If the recipe is stale vs `app/src/main/res/values/strings.xml`, fix the recipe first.
3. **Second loop in the tree:** extract shared adb/dump/tap/serial helpers from `onboarding_loop.py` into `helpers/loop_adb.py` and make both scripts import it. Do not copy the 400-line file. Do not extract before this second loop exists.
4. Write `.cursor/skills/verify-openloop/helpers/<feature>_loop.py` (Python 3 stdlib, mode `100755`). Fixtures named in a small table. Must-have / must-not-have lists copied from `strings.xml`, punctuation included (`LET'S GO!`, `B&W` vs `Black & White`).
5. Register the loop in `scripts/run-verification-loops.py` `LOOPS` (id, script, path substrings). That is how Definition of Done selects it.
6. Point the recipe's **Driving it with control.ps1** section at the one-liner, same shape as `features/onboarding.md`. Update `features/README.md` and `features/INVENTORY.md` loop status. Mark the wave row shipped in the guide.
7. Edit `.cursor` first. `python scripts/sync-harness-skills.py --fix`. Check-only must be green. One commit for the loop.
8. Prove it on a booted emulator (`adb devices` shows `device`):

   ```bash
   python3 .cursor/skills/verify-openloop/helpers/<feature>_loop.py
   ```

   Or `python3 scripts/run-verification-loops.py --only <feature>`. Compiling the script is not proof. Nested virtualization with `adb` `offline` is unverified — say that.

## Script contract (copy from onboarding)

- Package `io.github.stozo04.openloop`, activity `.MainActivity`. Ignore ghost `com.OpenLoop.app`.
- `adb devices` matches `emulator-\d+\s+device$` (spaces, not tabs). Physical phone only with `VERIFY_ALLOW_DEVICE=1` and `VERIFY_SERIAL`.
- No Gradle inside the loop. `adb install -r -g` if the package is missing. Debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- `repo_root()` is five `.parent`s from `helpers/<name>_loop.py`.
- Dump: `uiautomator dump /sdcard/ui.xml` then `cat`. If dump itself fails, treat as empty (do not cat a stale file). Empty dumps retry. Parse XML; regex fallback. `html.unescape`.
- Tap: exact text or content-desc with bounds, `input tap` at the center.
- Evidence under `$VERIFY_EVIDENCE_DIR` or `/tmp/openloop-verify/<timestamp>/<feature>/`. Do not commit dumps.
- Grant CAMERA up front when the surface is the camera. `Grant Permission` on a returning launch is a fail.
- If the dump cannot prove the claim, name the other signal (logcat, file on disk) or `verified-unreachable`. Never a silent pass.

## Parallelism (DoD)

Loops are a Definition of Done gate (`docs/DEFINITION_OF_DONE.md`). The sweep starts `scripts/run-verification-loops.py --changed` in the background after a green debug APK so it overlaps lint, JVM tests, and text gates. **One emulator.** Do not start a second AVD. Do not overlap a loop with `connectedDebugAndroidTest`.

While you wait on the emulator, do the other gates. A fail still means keep fixing the product.

## Routing leftovers

- `/verify-openloop` / "verify X" when the loop already exists: **run** it, do not recreate it.
- "full e2e" / editor pipeline: `run-e2e` is the orchestrator until those wave-4 loops exist.
- In-app review: logcat (`InAppReview` / `requestInAppReview`), not Play's card.
