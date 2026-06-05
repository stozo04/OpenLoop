# Definition of Done — the "Ready for PR" verification gate

**A change is not "done" because it compiles. It is done when it has been built, tested, *run*, and honestly reported.** This is the gate every non-trivial change must clear before it is called done or opened as a PR. It applies to humans and to Claude Code agents working in this repo.

The guiding principle: **don't trust "it compiles" — prove it.** Prove it builds clean, prove the tests pass, prove the app actually *runs*, and be explicit about what you could not verify.

---

## The gate (run top to bottom)

### 0. Baseline — before you change anything
Capture a green build of the *starting* state so any later failure is unambiguously yours:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # Android Studio's bundled JDK
.\gradlew.bat clean assembleDebug --console=plain
```

### 1. Build — debug AND release, genuinely green
Release matters: it runs R8/shrinking and resource crunching that debug skips, and catches things debug never will (it's how we found mislabeled JPEG drawables and R8 issues).
```powershell
.\gradlew.bat clean assembleDebug assembleRelease --console=plain; echo "EXIT=$LASTEXITCODE"
```
See **["Genuinely green"](#what-genuinely-green-means)** below — a finished command is not the same as a passed build.

### 2. Requirement / artifact checks
Verify the things a build alone doesn't prove. Example for this app — **16 KB native-lib alignment** (see [Lesson 011](lessons_learned/011-16kb-uncompressed-native-libs.md)):
```
<sdk>/build-tools/<ver>/zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release-unsigned.apk
# .so lines must read "(OK)" at 16384-multiple offsets — NOT "(OK - compressed)"
```

### 3. Static analysis — code inspection (Android Studio "Inspect Code", headless)
Reproduce both Inspect Code engines and clear them. Full design + severity rules: **[`docs/STATIC_ANALYSIS.md`](STATIC_ANALYSIS.md)**.
```powershell
.\gradlew.bat :app:lintDebug --console=plain        # Engine 1 — Android Lint (the automated gate)
```
`app/lint-baseline.xml` filters out the project's pre-existing items, so the report must show **zero *new* errors** — a genuinely clean run's XML contains only the informational `LintBaseline` "Hint" line. Then run **Engine 2** (IDE inspections + proofreading) locally via `inspect.bat` (command in `STATIC_ANALYSIS.md`) — it's slow and needs Android Studio closed. **Never regenerate `lint-baseline.xml` to silence findings** — that swallows newly-introduced issues too.

### 4. Automated tests — unit and instrumented
```powershell
.\gradlew.bat testDebugUnitTest --console=plain                 # JVM, no device
$env:ANDROID_SERIAL = "emulator-XXXX"                            # pin the device if more than one is attached
.\gradlew.bat connectedDebugAndroidTest --console=plain         # needs a booted emulator/device
```
Read the actual counts (`tests=".." failures=".." errors=".."` in `app/build/.../*-results/`); confirm **0 failures**, not just `BUILD SUCCESSFUL`.

### 5. Run it for real — boot, install, launch, screenshot
This is the step that separates "should work" from "works." Automated tests miss crashes-on-launch, missing/mislabeled assets, and layout breakage. Boot an emulator, install the APK, launch the app, and capture a screenshot as **proof**.
```
EMU=<sdk>/emulator/emulator.exe ; ADB=<sdk>/platform-tools/adb.exe
"$EMU" -avd <name> -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect &
"$ADB" wait-for-device
# poll until: getprop sys.boot_completed == 1
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" logcat -c
"$ADB" shell am start -W -n com.OpenLoop.app/.MainActivity      # check Status: ok, no Error
"$ADB" exec-out screencap -p > proof.png                        # attach to the PR
"$ADB" logcat -d | grep -iE "FATAL|AndroidRuntime"              # confirm no crash
```
> AGP uninstalls the app after `connectedAndroidTest`, so re-`install` before launching. `pm clear com.OpenLoop.app` first if you need a fresh first-run (e.g. to see onboarding).

### 6. Be honest about what you could NOT verify
State the coverage gaps plainly and hand off a manual QA checklist. Camera capture (simulated on emulators), specific-API-level runtime behavior, and large-screen (>=600dp) layout often need a real device or a specific emulator + a human. **Never claim success for something you didn't actually exercise** (Lesson 007's spirit).

### 7. Attach the proof to the PR
Put the screenshot(s) from step 5 in the PR description, alongside the build/test results. Visual proof + green build + test counts = a reviewable "done."

---

## What "genuinely green" means

A command finishing is **not** a passed build. Confirm all three:
1. The verdict line says **`BUILD SUCCESSFUL`** (not `BUILD FAILED`).
2. Gradle's **exit code is `0`** — `echo $LASTEXITCODE` (PowerShell) / `echo $?` (bash), captured *right after* gradlew.
3. **Zero `e:` lines** (Kotlin compile errors). Then skim `w:` warnings and `Unable to strip ... .so` notes (benign) and decide which matter.

> **Gotcha:** piping Gradle through `| tail` (or any pipe) gives you the *pipe's* exit code, not Gradle's — a failed build can look like it "passed." Read the `BUILD SUCCESSFUL`/`BUILD FAILED` line itself. (This is also in the README; it bit us once.)

---

## Environment notes (this machine)
- **Java:** Gradle needs a JDK. Use Android Studio's bundled JBR: `JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`. There is no system `java` on PATH here.
- **Wrapper:** `.\gradlew.bat` on Windows (`./gradlew` on macOS/Linux).
- **SDK:** `C:\Users\gates\AppData\Local\Android\Sdk` (`build-tools/<ver>/zipalign.exe`, `platform-tools/adb.exe`, `emulator/emulator.exe`).
- **Multiple devices:** set `$env:ANDROID_SERIAL` (or `adb -s <serial>`) so commands aren't ambiguous; clear ghost `offline` devices with `adb kill-server; adb start-server`.

---

## Copy-paste checklist for a PR

```
- [ ] Baseline green before changes (clean assembleDebug)
- [ ] clean assembleDebug + assembleRelease: BUILD SUCCESSFUL, exit 0, zero e:
- [ ] Requirement checks pass (e.g. zipalign -c -P 16 shows real (OK))
- [ ] Static analysis: Android Lint 0 new errors (lintDebug); IDE Inspect Code run locally + findings addressed
- [ ] Unit tests: 0 failures (count: __)
- [ ] Instrumented tests: 0 failures (count: __)
- [ ] App installed + launched on an emulator; no FATAL in logcat
- [ ] Screenshot captured and attached to the PR
- [ ] Coverage gaps stated + manual QA checklist provided
```
