# Lesson 040 — A new native/JNI dependency is not verified until the *release* APK has run: R8 failures are invisible in debug

> Origin: the hand-flick lenses (`docs/PRD-lens-hand-flick.md`), 2026-08-26. The debug build
> tracked hands on the emulator and on the owner's Fold; the first **release** build crashed on
> the first Football tap, 100 % of the time, inside a library static initializer. It was caught
> only because the release APK was installed and driven before the PR — not by any build gate.

## What went wrong

MediaPipe `tasks-vision` came up clean everywhere the Definition of Done looks: `assembleRelease`
was `BUILD SUCCESSFUL`, `zipalign -P 16` said `(OK)` on every `.so`, Lint was 0/0, the unit and
connected suites were green, and the debug APK worked on two devices. Then the release APK, driven
the same way on the emulator:

```text
E/AndroidRuntime: at com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker.createFromOptions(…)
Caused by: java.lang.IllegalStateException: no caller found on the stack for: sn1
    at com.google.mediapipe.framework.Graph.<clinit>(…)
```

`sn1` is Flogger's `FluentLogger` after obfuscation. `forEnclosingClass()` finds the class that
called it by walking the stack and comparing class **names**; R8 renamed the class, the comparison
failed, and the static initializer of MediaPipe's `Graph` threw. Two things hid it:

1. **R8 only runs on release.** Debug is unminified, so nothing name-based can break there, and
   every "run the app" step in the gate had been done on debug.
2. **The AARs ship no consumer ProGuard rules** (checked: `tasks-vision` / `tasks-core 1.0.0`
   carry no `proguard.txt`), so R8 had nothing to go on but the app's own rules — which kept
   `com.google.mediapipe.**` and `com.google.protobuf.**` but not the logging library MediaPipe
   pulls in transitively.

A second, quieter failure of the same shape was already in `missing_rules.txt`: two MediaPipe proto
classes referenced by the framework but absent from the artifact, which R8 refuses to build
without an explicit `-dontwarn`.

## Pattern

- **When a dependency brings native code, JNI, reflection, or a logging framework, the gate
  includes installing and driving the release APK** — on the emulator is enough. `assembleRelease`
  succeeding proves R8 *compiled*; it proves nothing about what R8 *removed or renamed*. Drive the
  exact code path the dependency serves (here: select a flickable lens, watch for
  `Hand tracking on`).
- **Look for consumer rules before writing any** — unzip the AAR and check for `proguard.txt`.
  None → the app owns every keep rule for that library *and* its transitive dependencies. Start from
  R8's own `missing_rules.txt` for the `-dontwarn` set, then add keeps for anything reached by name
  from native code or by stack inspection (Flogger, any `Class.forName`, any JNI-registered class).
- **Make the feature survive its own dependency.** The tracker now catches `MediaPipeException`
  and `LinkageError` (`ExceptionInInitializerError`, `UnsatisfiedLinkError`, `NoClassDefFoundError`
  — the JVM's own class of "the library could not come up"), turns the verb off, and reports a
  Crashlytics non-fatal. Not a catch-all: a bug in our code still propagates (Lesson 013).

## Detection checklist

- `rg -n "implementation\(libs\." app/build.gradle.kts` after a dependency bump: for each new
  library with a `jni/` folder or a `.task`/`.tflite` asset, the PR must cite a release-APK run.
- `app/build/outputs/mapping/release/missing_rules.txt` after `assembleRelease` — non-empty means
  R8 needed rules you did not write; copy them in with a comment, never `-ignorewarnings`.
- Logcat on the release APK: any `AndroidRuntime` line naming a `<clinit>` of a library class is
  this lesson. `no caller found on the stack` is Flogger specifically.
- The pre-PR sweep builds release but does not run it; the emulator step in
  `docs/DEFINITION_OF_DONE.md` must be done on the **release** APK when this lesson applies.

## Reference

- [Shrink, obfuscate, and optimize your app](https://developer.android.com/build/shrink-code) —
  keep rules, `missing_rules.txt`, and why library-internal reflection needs explicit keeps.
- `app/proguard-rules.pro` (the MediaPipe / Flogger block, with the measured failure quoted),
  `camera/lens/HandTracker.kt` (`unavailable`), `diagnostics/ReverseCrashlytics.reportHandTrackerUnavailable`.
- Builds on [[011-16kb-uncompressed-native-libs]] (the other release-only native-lib check) and
  [[013-media-start-failure-return-and-narrow-catch]] (which failures a boundary may catch).
