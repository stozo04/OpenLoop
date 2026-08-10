# E2E proof — Play in-app review gate (Issue #121, Step 4)

**Date:** 2026-08-09 · **Device:** `Pixel_8` AVD (`sdk_gphone16k_x86_64`, API 37, `emulator-5556`)
**Build:** debug, from `feature/aso-landing-and-review` · **App version:** 1.0.38 (versionCode 38)

## What was driven

A full real-UI round trip through the path this change touches — the save-success branch:

`camera → record ~3 s → Trim → SAVE → editor → Save boomerang → render → share sheet → back → snackbar`

| Step | Result |
|---|---|
| Launch | Camera viewfinder, permissions already granted, app focused |
| Record + stop | Auto-routed to Trim ("TRIM YOUR VIDEO", 2.9 s clip) |
| Advance | Editor with the four direction chips + "Save boomerang" |
| Save | Render completed; share sheet opened on `boom_1786324342162_from_1786324342018.mp4` |
| Back from chooser | **"Saved to Photos" snackbar** on the camera screen — screenshot below |
| Review card | **Not shown — correct.** Counter is at 1; the gate is 3 |

![Saved snackbar after the share sheet closed](2026-08-09-in-app-review-saved-snackbar-pixel8.png)

## The review-specific evidence

The screenshot proves the save path still completes with `incrementSavedLoopCount()` in it. The
counter itself is only observable in DataStore, so it was read straight off the device:

```
$ adb shell run-as io.github.stozo04.openloop \
    cat files/datastore/openloop_preferences.preferences_pb | xxd
00000000: 0a1e 0a18 6861 735f 636f 6d70 6c65 7465  ....has_complete
00000010: 645f 6f6e 626f 6172 6469 6e67 1202 0801  d_onboarding....
00000020: 0a16 0a10 7361 7665 645f 6c6f 6f70 5f63  ....saved_loop_c
00000030: 6f75 6e74 1202 1801                      ount....
```

`saved_loop_count` → `12 02 18 01` — a value message holding varint **1**. One save, one increment,
against the real `preferencesDataStore`.

## Logcat

No `FATAL` and no `ANR` from `io.github.stozo04.openloop`; the pid (5563) was unchanged across the
whole run, so nothing crashed and restarted. The only `Failed to query component store for system
resources: 6` lines are pre-existing emulator noise unrelated to this change. The single SIGABRT in
the log is `com.android.bluetooth` (emulator infrastructure, pid 1126), not the app.

## What this run does NOT prove

**The review card itself was never rendered, and cannot be on an emulator.** The API only surfaces
a card for an account that installed the app from Play; a sideloaded debug build gets a silent
no-op, which is exactly what `launchInAppReview` swallows. Reaching the gate here would only have
proved that the no-op doesn't crash.

Verifying the card is **internal-test-track work**, and quotas are not enforced there:
1. Upload to Internal testing; install as a tester account that is the primary Play account.
2. Save three loops, dismissing the share sheet each time.
3. The card must appear after the third — once, after the "Saved" snackbar clears, with nothing
   drawn over it and no question asked beforehand.
4. Save a fourth loop: **no** second card.

Source: [Test in-app reviews](https://developer.android.com/guide/playcore/in-app-review/test).
