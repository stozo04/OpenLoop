# Verification loops

How OpenLoop proves a shipped screen on a real emulator, not in a test host.
The first loop is onboarding. This page is the pattern later loops copy, and the ordered list of what is still missing.

Agent recipes for dump/tap still live under `.cursor/skills/verify-openloop/features/`. A **recipe** tells an agent what to tap. A **loop** is a script that fails the process if the assertion is false. Recipes without a loop are not proof.

## What the owner is asking for

When Steven says "verify X" or "build an X loop", he wants the installed debug APK driven like a user:

1. Put the app in a known fixture (fresh DataStore, returning user, Video mode, booth armed, …).
2. Exercise the real UI (`am start`, uiautomator dump, tap by literal label).
3. Assert what the user can see or what logcat can prove when the dump cannot.
4. Keep dumps and screenshots under `$VERIFY_EVIDENCE_DIR`. Exit 0 only on pass.

He is **not** asking for:

- `createComposeRule().setContent { SomeButton() }` that never starts `MainActivity`
- A ViewModel test with a fake repository that never binds CameraX
- A recipe you walked by hand once and described in prose
- Setting `OpenLoopUiState` from a test-only host and calling that a user proof

Those isolated tests stay. They are the pyramid base. They do not replace a loop.

If you opened a recipe because the owner said "verify X", do not stop at walking the recipe. Write or run the matching loop in the table below.

## Golden example (onboarding)

Shipped in PR #162. Read these before writing the next loop:

| Piece                     | Path                                                                                                  |
| ------------------------- | ----------------------------------------------------------------------------------------------------- |
| Script                    | `.cursor/skills/verify-openloop/helpers/onboarding_loop.py`                                           |
| Recipe                    | `.cursor/skills/verify-openloop/features/onboarding.md`                                               |
| Reset                     | `.cursor/skills/reset-storage/SKILL.md` (DataStore file only)                                         |
| Shared dump/tap (Windows) | `.cursor/skills/verify-openloop/helpers/control.ps1` plus `.claude/skills/run-e2e/scripts/uiauto.ps1` |

Copy the skill file into `.claude/` and `.codex/` with `python scripts/sync-harness-skills.py --fix`. Edit `.cursor` first.

### Domain, not flags

Two launch fixtures, not a pile of booleans:

- **FirstRun.** Preferences file gone. `has_completed_onboarding` defaults false. Screen is `OpenLoopUiState.Onboarding`.
- **Returning.** File exists with the flag true. Init goes to `CheckingPermissions`, then `ReadyToCapture` once CAMERA is granted.

Defaults on the returning camera (not persisted):

- `CaptureMode.VIDEO` in `OpenLoopViewModel`
- `CameraSelector.LENS_FACING_BACK` in `CameraManager`

### Assertions (literals from `strings.xml`)

First run must show:

- `Free. Forever.`
- `No Subscriptions · No Ads`
- `Open source · 100% on your phone`
- `LET'S GO!`
- content-desc `Looping demo of a boomerang video` (the hero is `R.raw.onboarding_loop_1`, a roller-coaster POV)

Returning must show:

- `Start recording` (this is Video mode; the word `Camera` on the other chip does not mean stills)
- `Video`
- `Flip Camera`

Returning must **not** show `LET'S GO!`.

Facing is **not** in the Flip Camera label. Prove it from logcat: `Camera bound (lens=back)`.

### How `onboarding_loop.py` is built

Python 3 stdlib only. The same file is copied into `.cursor`, `.claude`, and `.codex`. `repo_root()` is five `.parent`s from `helpers/<name>_loop.py` so all three trees resolve the debug APK the same way.

Flow in `main()`:

1. `resolve_serial()`. `VERIFY_SERIAL` wins. Else exactly one `adb devices` line matching `emulator-\d+\s+device$` (spaces, not tabs). A physical serial is refused unless `VERIFY_ALLOW_DEVICE=1` **and** `VERIFY_SERIAL` are both set.
2. `adb get-state` must be `device`. `offline` is a fail, not a retry-forever.
3. `ensure_installed()`. If `pm path` lacks `package:`, `adb install -r -g` the debug APK. No Gradle inside the loop. `pm path` and DataStore `ls` must use `check=False` or a missing package/file crashes the process before the assertion.
4. Grant `CAMERA`. Permission is not an onboarding page. If returning launch shows `Grant Permission`, fail.
5. **FirstRun.** `reset_onboarding_store()` force-stops then `run-as` `rm -f files/datastore/openloop_preferences.preferences_pb`. `am start` `.MainActivity`. Poll dumps until every `ONBOARDING_MUST_HAVE` string is present (30 s).
6. Tap `LET'S GO!` at the center of the node's `bounds`. Wait until the CTA is gone **and** the preferences file exists (20 s). `onOnboardingCompleted()` flips UI first, then writes DataStore on a coroutine. Force-stop before the write lands and the next launch shows onboarding again.
7. **Returning.** Force-stop (do **not** delete DataStore). Clear logcat. Start again. Poll until `Start recording`. Assert `CAMERA_MUST_HAVE` / `CAMERA_MUST_NOT_HAVE`. Dump logcat; require `Camera bound (lens=back)`. If the dump shows only `lens=front`, fail.

Dump path: `uiautomator dump /sdcard/ui.xml`, then `cat`. If the dump command itself fails, treat as empty (do not `cat` a stale previous dump). Empty dumps while the activity is coming up are normal; `wait_until` retries. Parse XML; fall back to a regex over `<node …>` if the tree is malformed. `html.unescape` so middle dots and apostrophes match `strings.xml`.

Tap path: exact `text` or `content-desc` match with bounds, then `input tap` at the center. Do not add product `testTag`s just so the dump is easier.

Evidence (do not commit): `first-run.xml` / `.png`, `after-cta.xml`, `returning.xml` / `.png`, `returning-logcat.txt`. Default dir `/tmp/openloop-verify/<timestamp>/onboarding/` or `$VERIFY_EVIDENCE_DIR`.

Pass line: `PASS serial=... first-run=onboarding returning=video+back evidence=...`.

Run:

```bash
python3 .cursor/skills/verify-openloop/helpers/onboarding_loop.py
```

Wrong-surface is not a pass. `OnboardingScreenTest` going green does not ship an onboarding loop.

## Contract for the next loop

Do this, in order:

1. Read the matching `features/<name>.md` and the strings it names. If the recipe is stale vs `strings.xml`, fix the recipe first.
2. Name the fixtures in a comment-sized table in the script (same shape as FirstRun / Returning).
3. Put must-have / must-not-have string lists next to those fixtures. Copy from `strings.xml`, including punctuation (`LET'S GO!`, `B&W` vs `Black & White`).
4. Extract shared adb/dump/tap/serial helpers out of `onboarding_loop.py` into `helpers/loop_adb.py` **when the second loop lands**. Do not copy the 400-line file. Do not extract "for later" before the second loop exists.
5. Script name `helpers/<feature>_loop.py`. Recipe's "Driving it with control.ps1" section gets the one-liner, same as onboarding.
6. Harness-sync. One commit per loop. Prove it on a booted emulator (`adb devices` shows `device`) before calling it done.
7. If the dump cannot prove the claim (camera facing, lens on a face, Play review card), name the **other** signal (logcat line, file on disk, visual with an honest "no face on this AVD"). Missing signal is a fail or `verified-unreachable`, never a silent pass.

## Remaining loops (build order)

Smallest chrome first. Capture next. Editor after a clip exists. Play prompts last.
Each row is one script unless noted. Do not start wave 3 before wave 1 is a loop.

### Wave 0. Done

| Loop         | Prove                                                                      | Handles / signal |
| ------------ | -------------------------------------------------------------------------- | ---------------- |
| `onboarding` | First launch is the trust screen. Returning launch is Video + back camera. | Script above.    |

### Wave 1. Idle camera chrome

No recording. No files. Fixture is returning user (run `onboarding_loop.py` once, or leave DataStore intact).

| Loop            | Prove                                                                                                                           | Handles / signal                                          | Notes                                                                                                                                                                                                                                                    |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `capture-mode`  | Idle shutter is `Start recording`. Tap `Camera`. Shutter becomes `Take photo`. Tap `Video`. Shutter is `Start recording` again. | Labels `Camera`, `Video`. Shutter desc is the mode proof. | CaptureMode is **not** persisted. A process death resets to Video. The loop should include a force-stop after switching to Camera and assert Video is back. No dedicated recipe; see `photo-capture.md` `photo-mode` and `record-clip.md` Video confirm. |
| `flip-camera`   | From back, tap `Flip Camera`. Logcat shows `Camera bound (lens=front)`. Tap again. Logcat shows `lens=back`.                    | `Flip Camera`. Logcat, not the button label.              | Clear logcat between taps. Folded into `record-clip.md` `record-flip`.                                                                                                                                                                                   |
| `gallery-empty` | Fresh library. Tap `Gallery`. Dump has `NO LOOPS YET`. `Back to camera` returns to `Start recording`.                           | `Gallery`, `NO LOOPS YET`, `Back to camera`               | Empty copy also includes `Record your first loop to see it here!`. Destructive only of this run's later clips, never a personal library. Recipe: `gallery.md` `gallery-empty`.                                                                           |

### Wave 2. Drawer (no face required)

| Loop              | Prove                                                                                                                                                                                       | Handles / signal                                                                                 | Notes                                                                                                                                                                         |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `lenses-ui`       | Open `Lenses and Photo Booth`. Lenses tab shows catalogue names from `Lens.kt` (do not invent names). Pick `Broccoli`. Clear by tapping it again or `Close lenses`.                         | `Lenses and Photo Booth`, thumb names, `Photo Booth` tab still present                           | On an AVD with no face, this loop **stops** at open/pick/clear. Do not claim tracking or bake. Recipe: `lenses.md`.                                                           |
| `photo-booth-arm` | Open drawer, tap `Photo Booth`. Color and Black & White are both there. Pick each. Shutter desc is `Start photo booth`, not `Start recording`.                                              | `Photo Booth`, `Color`, `Black & White`, `Start photo booth`                                     | `Turn off photo booth` disarms. Do not tap it to start. Recipe: `photo-booth.md` `booth-arm`.                                                                                 |
| `photo-booth-run` | From armed Color, tap `Start photo booth`. A 5-4-3-2-1 countdown, `Shot N of 3`, and `Swap lenses between shots`. Three stills. Land on a strip / still preview, **not** `TRIM YOUR VIDEO`. | `Start photo booth`, countdown digit, `Shot 1 of 3` … `Shot 3 of 3`, `Swap lenses between shots` | ~15 s of countdown. Do not background the app. Emulator with no face still proves the UI sequence. Recipe: `photo-booth.md` `booth-start` / `booth-countdown` / `booth-done`. |

### Wave 3. Capture

| Loop            | Prove                                                                                                                                           | Handles / signal                                       | Notes                                                                                                                                          |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `record-clip`   | Video mode. Tap `Start recording`. After ~3 s dump has `Stop recording`. Stop. Dump has `TRIM YOUR VIDEO` and `SAVE`.                           | `Start recording`, `Stop recording`, `TRIM YOUR VIDEO` | Too-short path (`That was quick!`) is a second assertion, not a substitute for Trim. Recipe: `record-clip.md`.                                 |
| `photo-capture` | Switch to Camera. Tap `Take photo`. Must **not** open Trim. Aftermath is share sheet and/or `Saved to Photos` / gallery still `Captured photo`. | `Take photo`, absence of `TRIM YOUR VIDEO`             | Preview-resolution stills. Product decision, not a bug. Recipe: `photo-capture.md`.                                                            |
| `pinch-zoom`    | Pinch updates `Zoom level,` / zoom chip.                                                                                                        | Gesture                                                | **verified-unreachable** is allowed if the AVD cannot inject a pinch. Do not fake Camera2 zoom and call it this loop. Recipe: `pinch-zoom.md`. |

### Wave 4. Editor (needs a clip)

Get a clip from `record-clip` or the import recipe at `.cursor/skills/verify-openloop/features/import-video.md` (`google-pro-fold-video.mp4` is often gitignored; mark unreachable if the fixture is missing).

| Loop          | Prove                                                                                                                                                                                                        | Notes                                                                 |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------- |
| `edit-trim`   | Land on `TRIM YOUR VIDEO` + `SAVE`. Optional handle drag. `SAVE` advances to editor chrome (`Speed` / `Loop` / `Filter` / `Save boomerang`). Discard dialog is `Discard this clip?` with `Discard` / `Keep`. | Recipe `features/edit-trim.md`.                                       |
| `edit-speed`  | Toolbar `Speed`. Constant slider (`Playback speed` / `Current speed`). Custom curve / presets (`Ease In`, …) if that is the claim.                                                                           | First Custom visit may show intro `Got it`.                           |
| `edit-loop`   | Toolbar `Loop`, title `Select loop direction`. Fast proof: `Forward loop` (no reverse encode). Default is already `Forward then reverse`; re-tapping it proves nothing.                                      | Reverse chips can sit on `Loopifying..` up to 120 s.                  |
| `edit-filter` | Toolbar `Filter`, title `Choose a look`. Pick a non-`Original` chip (`B&W`, `Warm`, …).                                                                                                                      | Memory-gate copy if Looks disable is a finding, not a skip.           |
| `edit-delete` | Toolbar `Delete` → `Discard this clip?` → `Keep editing` stays; `Discard` returns to camera.                                                                                                                 | Only delete this run's clip. Distinct from Gallery `Delete selected`. |
| `edit-save`   | `Save boomerang` / `Creating..` then share + library tile.                                                                                                                                                   | A toast is not enough. Gallery (or Photos) must show the file.        |

`features/edit-and-save.md` plus `run-e2e` already orchestrate the full editor path. A loop here is the fail-the-process version of that recipe, not a second pipeline.

### Wave 5. Library

| Loop                | Prove                                                       | Notes                                                                                                                                                                  |
| ------------------- | ----------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `import-video`      | Photo Picker → Trim (`TRIM YOUR VIDEO`).                    | Needs a seeded mp4 on the device. Opening the picker is not `import-success`. Length dialogs (`That clip's a bit long` / `That clip's a bit short`) are product gates. |
| `gallery-play`      | Open a tile this run created. Back to camera.               | Count tiles before/after a save. Opening Gallery is not proof a save landed.                                                                                           |
| `share-and-library` | Dual write (app Gallery + Photos) and share sheet / `SEND`. | Snackbar `Saved to Photos`. Do not complete a send to a personal contact.                                                                                              |

### Wave 6. Play prompts

| Loop                   | Prove                                                                                                                                                                             | Notes                                                                                                                                                                                                                                                                                           |
| ---------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `in-app-review`        | After the 3rd lifetime save (`REVIEW_FIRST_ASK_AFTER_SAVES`), logcat contains the in-app review request (`InAppReview` / `requestInAppReview`). Re-ask every 10 saves after that. | Play often draws **nothing** on an emulator or sideloaded APK. The API does not say whether the card showed. Dump-of-a-dialog is not required. Absence of the request line is a fail. Do not fake a rating UI. JVM tests (`ReviewCadenceTest`, `InAppReviewRobolectricTest`) are not this loop. |
| in-app update snackbar | Leave **out of loop scope** unless a Play-signed build is the target.                                                                                                             | Same reason as today's inventory row.                                                                                                                                                                                                                                                           |

Permission rationale / denied screens stay support chrome. Drive them only when they block another loop.

## Recipe to loop

| Owner says / recipe                               | Loop                                                      | Wave |
| ------------------------------------------------- | --------------------------------------------------------- | ---- |
| `onboarding.md`                                   | `onboarding` (shipped)                                    | 0    |
| Camera vs Video / `photo-capture.md` `photo-mode` | `capture-mode`                                            | 1    |
| Front vs back / `record-clip.md` `record-flip`    | `flip-camera`                                             | 1    |
| `gallery.md` empty state                          | `gallery-empty`                                           | 1    |
| `lenses.md` open/pick/clear                       | `lenses-ui`                                               | 2    |
| `photo-booth.md` arm                              | `photo-booth-arm`                                         | 2    |
| `photo-booth.md` countdown                        | `photo-booth-run`                                         | 2    |
| `record-clip.md` start/stop                       | `record-clip`                                             | 3    |
| `photo-capture.md` still                          | `photo-capture`                                           | 3    |
| `pinch-zoom.md`                                   | `pinch-zoom`                                              | 3    |
| `edit-trim.md`                                    | `edit-trim`                                               | 4    |
| `edit-speed.md`                                   | `edit-speed`                                              | 4    |
| `edit-loop.md`                                    | `edit-loop`                                               | 4    |
| `edit-filter.md`                                  | `edit-filter`                                             | 4    |
| `edit-delete.md`                                  | `edit-delete`                                             | 4    |
| `edit-save.md` / `edit-and-save.md`               | `edit-save` (or `run-e2e` if they named the orchestrator) | 4    |
| `import-video.md`                                 | `import-video`                                            | 5    |
| `gallery.md` play a tile                          | `gallery-play`                                            | 5    |
| `share-and-library.md`                            | `share-and-library`                                       | 5    |
| Rate the app / in-app review                      | `in-app-review`                                           | 6    |

When the request names a surface that has a recipe but no `*_loop.py` yet, **write the loop**. Do not only re-walk the recipe.

## Emulator honesty

- Face tracking, hand flick, and "lens on a person" need a detectable face. The stock virtual scene may have none. UI open/pick is still a valid loop. Tracking is not.
- Nested virtualization on some cloud VMs leaves the emulator idle and `adb` `offline`. A loop that never attached is unverified. Say that. Do not pass on compiling the script.
- One AVD at a time.

## Routing

| User says                               | Do                                                                                                 |
| --------------------------------------- | -------------------------------------------------------------------------------------------------- |
| "verify onboarding" / "onboarding loop" | Run `onboarding_loop.py`. Do not add a Compose test.                                               |
| "verify Camera vs Record"               | Wave 1 `capture-mode`.                                                                             |
| "verify front/back"                     | Wave 1 `flip-camera`.                                                                              |
| "verify lenses"                         | Wave 2 `lenses-ui`. Say unreachable for on-face if the AVD has no face.                            |
| "verify photo booth"                    | Wave 2 arm, then run. Include Color / Black & White and the swap-lenses banner.                    |
| "verify the editor" / "full e2e"        | Wave 4, or `run-e2e` if they want the existing orchestrator. Prefer a loop if they used that word. |
| "verify rate the app"                   | Wave 6. Logcat, not a screenshot of Play's card.                                                   |
